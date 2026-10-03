// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.core.camera.session

import android.content.Context
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Surface
import io.github.tengigabytes.anomalops.core.camera.request.ColorSpec
import io.github.tengigabytes.anomalops.core.camera.request.RequestPlanner
import io.github.tengigabytes.anomalops.core.camera.request.RequestSpec
import io.github.tengigabytes.anomalops.core.profile.CalibrationKey
import io.github.tengigabytes.anomalops.core.profile.DeviceProfile
import io.github.tengigabytes.anomalops.core.profile.ScenePreset
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The camera as the UI sees it (ADR-0001): the logical back camera with one session per physical lens.
 * Camera2 work runs on a dedicated HandlerThread (ADR-0007); public calls are serialised onto it.
 */
class CameraController(context: Context, profile: DeviceProfile) {
    private val thread = HandlerThread("camera").apply { start() }
    private val handler = Handler(thread.looper)
    private val dispatcher = handler.asCoroutineDispatcher()
    private val planner = RequestPlanner(profile)
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(CameraState())
    val state: StateFlow<CameraState> = mutableState.asStateFlow()
    private val frames = MutableSharedFlow<PreviewFrame>(
        extraBufferCapacity = FRAME_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Every preview frame's result as it arrives; used to time preset switches (NFR-4). */
    val previewFrames: SharedFlow<PreviewFrame> = frames.asSharedFlow()
    private val scans = MutableSharedFlow<FocusScan>(extraBufferCapacity = FRAME_BUFFER)

    /** Every AUTO focus scan as it settles (FR-31, FR-35). */
    val focusScans: SharedFlow<FocusScan> = scans.asSharedFlow()

    private val lenses = LensSwitcher(
        manager = requireNotNull(context.getSystemService(CameraManager::class.java)),
        handler = handler,
        planner = planner,
        onLost = { message -> mutableState.value = CameraState(status = CameraStatus.FAILED, error = message) },
        onFrame = { frames.tryEmit(it) },
        onScan = { scans.tryEmit(it) },
    )

    /** Opens the camera if needed and previews [preset] on [surface], whose buffer size must be [PREVIEW_SIZE]. */
    suspend fun start(surface: Surface, preset: ScenePreset, conditions: CalibrationKey) =
        serial { show(planner.preview(preset, conditions), surface) }

    /** FR-11: switches preset; the session is rebuilt only when the physical lens changes. */
    suspend fun select(preset: ScenePreset, conditions: CalibrationKey) =
        serial { show(planner.preview(preset, conditions), surface = null) }

    /** One still with the ADR-0009 shutter-priority exposure derived from the latest preview frame. */
    suspend fun capture(): StillCapture = serial {
        lenses.takeStill().also {
            // A still came out, so the session works: clear an error left by an earlier failed call.
            if (mutableState.value.status == CameraStatus.FAILED) {
                mutableState.value = mutableState.value.copy(status = CameraStatus.PREVIEWING, error = null)
            }
        }
    }

    /**
     * FR-17: [capture], followed by [extraFrames] RAW frames of the same exposure to merge with it. The frames
     * are copies; only the still's own RAW frame holds a camera buffer, as after [capture].
     */
    suspend fun captureMultiFrame(extraFrames: Int): MultiFrameCapture = serial {
        lenses.takeStillWithRawBurst(extraFrames).also {
            if (mutableState.value.status == CameraStatus.FAILED) {
                mutableState.value = mutableState.value.copy(status = CameraStatus.PREVIEWING, error = null)
            }
        }
    }

    /**
     * FR-15, FR-68: bursts plain JPEG at [fps] on the current lens until [until] returns; [onFrame] runs on the
     * camera thread for every frame and must not block. Returns the frame count.
     */
    suspend fun burst(fps: Int, onFrame: (BurstFrame) -> Unit, until: suspend () -> Unit): Int =
        serial { lenses.burst(fps, onFrame, until) }

    /** Stops the preview and closes the camera; [start] opens it again. */
    suspend fun stop() = serial {
        lenses.shutdown()
        mutableState.value = CameraState()
    }

    /**
     * [stop] for `SurfaceHolder.Callback.surfaceDestroyed`, which must not return while the camera still draws
     * into the surface. Gives up after [STOP_TIMEOUT_MS], e.g. behind a still that is timing out.
     */
    fun stopBlocking() {
        runBlocking { withTimeoutOrNull(STOP_TIMEOUT_MS) { stop() } }
    }

    /** Final release after [stop]; the controller cannot be used afterwards. */
    fun release() {
        handler.post {
            lenses.closeAll()
            thread.quitSafely()
        }
    }

    private suspend fun show(spec: RequestSpec, surface: Surface?) {
        lenses.show(spec, surface)
        mutableState.value = CameraState(
            status = CameraStatus.PREVIEWING,
            preset = spec.preset,
            physicalId = spec.physicalId,
            colorApproximate = spec.color == ColorSpec.AutoApproximate,
        )
    }

    private suspend fun <T> serial(block: suspend () -> T): T = mutex.withLock {
        withContext(dispatcher) {
            try {
                block()
            } catch (e: CameraAccessException) {
                throw failed(CameraFailure("camera access: ${e.message}", e))
            } catch (e: IllegalStateException) {
                throw failed(e)
            }
        }
    }

    private fun failed(e: IllegalStateException): IllegalStateException {
        mutableState.value = mutableState.value.copy(status = CameraStatus.FAILED, error = e.message)
        return e
    }

    companion object {
        private const val STOP_TIMEOUT_MS = 2_000L
        private const val FRAME_BUFFER = 64

        /** 4:3 like the stills; the SurfaceView's buffer is fixed to this size. */
        // Configures with a 4080x3072 / 4032x3024 JPEG_R reader on lenses 2, 3 and 9 (docs/test/m1-camera-session.md).
        val PREVIEW_SIZE = Size(1440, 1080)
    }
}
