// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.core.camera.experiment

import android.Manifest
import android.graphics.ImageFormat
import android.hardware.HardwareBuffer
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import io.github.tengigabytes.anomalops.core.camera.session.configure
import io.github.tengigabytes.anomalops.core.camera.session.openDevice
import io.github.tengigabytes.anomalops.core.profile.DeviceProfiles
import io.github.tengigabytes.anomalops.core.profile.PhysicalCamera
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.Executor

/**
 * M2 risk check (ADR-0005, FR-15, FR-68), per lens: a single session (preview + JPEG_R + RAW), a switch to a
 * burst session (preview + JPEG) and back. Measures configure time, time to the first burst JPEG and back to the
 * first preview frame, and the burst rate. Results go to logcat under [TAG]; nothing is saved.
 */
@RunWith(AndroidJUnit4::class)
class StreamComboExperiment {
    @get:Rule
    val cameraPermission: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.CAMERA)

    private val manager = InstrumentationRegistry.getInstrumentation().targetContext
        .getSystemService(CameraManager::class.java)
    private val thread = HandlerThread("experiment").apply { start() }
    private val handler = Handler(thread.looper)
    private val executor = Executor { handler.post(it) }
    private val preview = ImageReader.newInstance(
        ComboStreams.PREVIEW.width,
        ComboStreams.PREVIEW.height,
        ImageFormat.PRIVATE,
        SINK_IMAGES,
        HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE,
    )
    private var firstPreview = CompletableDeferred<Long>()

    @After
    fun tearDown() {
        preview.close()
        thread.quitSafely()
    }

    @Test
    fun m2_singleAndBurstSessionsPerLens() = runBlocking<Unit>(handler.asCoroutineDispatcher()) {
        preview.setOnImageAvailableListener({
            it.acquireLatestImage()?.close()
            firstPreview.complete(SystemClock.elapsedRealtimeNanos())
        }, handler)
        val profile = requireNotNull(DeviceProfiles.load(Build.DEVICE))
        LENSES.forEach { id -> Log.i(TAG, "lens $id: ${cycle(requireNotNull(profile.physicalCamera(id)))}") }
    }

    /** Single session, one JPEG_R + RAW still, switch to the burst session for [BURST_MS], switch back. */
    private suspend fun cycle(camera: PhysicalCamera): String {
        val device = manager.openDevice(LOGICAL_BACK, handler) { Log.e(TAG, "camera lost: $it") }
        val single = ComboStreams(camera, burst = false, handler)
        val burst = ComboStreams(camera, burst = true, handler)
        try {
            val (singleSession, configureMs) = timed { session(device, camera, single) }
            startPreview(device, singleSession, camera)
            delay(SETTLE_MS)
            val still = still(device, singleSession, camera, single)
            singleSession.abortCaptures()
            val switchStart = SystemClock.elapsedRealtimeNanos()
            val burstSession = session(device, camera, burst)
            burstSession.setRepeatingRequest(burstRequest(device, camera, burst), null, handler)
            val toBurstMs = elapsedMs(switchStart, burst.firstJpeg)
            delay(BURST_MS)
            burstSession.abortCaptures()
            val backStart = SystemClock.elapsedRealtimeNanos()
            val back = session(device, camera, single)
            firstPreview = CompletableDeferred()
            startPreview(device, back, camera)
            val backMs = elapsedMs(backStart, firstPreview)
            back.close()
            return "configure=%.0f ms $still toFirstBurstJpeg=%.0f ms ${rate(burst)} backToPreview=%.0f ms"
                .format(configureMs, toBurstMs, backMs)
        } finally {
            device.close()
            single.close()
            burst.close()
        }
    }

    private suspend fun session(device: CameraDevice, camera: PhysicalCamera, streams: ComboStreams) = device.configure(
        (listOf(preview.surface) + streams.stillSurfaces).map {
            OutputConfiguration(it).apply { setPhysicalCameraId(camera.id) }
        },
        executor,
    ) {}

    private fun startPreview(device: CameraDevice, session: CameraCaptureSession, camera: PhysicalCamera) {
        val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW, setOf(camera.id))
        request.addTarget(preview.surface)
        session.setRepeatingRequest(request.build(), null, handler)
    }

    private suspend fun still(
        device: CameraDevice,
        session: CameraCaptureSession,
        camera: PhysicalCamera,
        s: ComboStreams,
    ): String {
        val readers = listOfNotNull(s.jpegR, s.raw)
        val images = readers.map { s.next(it) }
        val request = manual(device, camera)
        readers.forEach { request.addTarget(it.surface) }
        session.capture(request.build(), null, handler)
        val done = withTimeoutOrNull(STILL_TIMEOUT_MS) { images.map { it.await() } }
        return "single=${done ?: "TIMEOUT"}"
    }

    private fun burstRequest(device: CameraDevice, camera: PhysicalCamera, s: ComboStreams) =
        manual(device, camera).apply {
            addTarget(preview.surface)
            addTarget(requireNotNull(s.jpeg).surface)
        }.build()

    /** FR-15: frames and median sensor-timestamp gap of the burst. */
    private fun rate(s: ComboStreams): String {
        val gaps = s.jpegTimestamps.zipWithNext { a, b -> (b - a) / NS_PER_MS }.sorted()
        return "burst=${s.jpegTimestamps.size} in $BURST_MS ms gap=%.1f ms".format(gaps.getOrNull(gaps.size / 2))
    }

    /** ADR-0009 still settings: AE off, 1/125 s, fixed ISO, frame duration at the JPEG stream minimum. */
    private fun manual(device: CameraDevice, camera: PhysicalCamera) =
        device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE, setOf(camera.id)).apply {
            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
            set(CaptureRequest.SENSOR_EXPOSURE_TIME, EXPOSURE_NS)
            set(CaptureRequest.SENSOR_SENSITIVITY, ISO)
            set(CaptureRequest.SENSOR_FRAME_DURATION, camera.outputs.getValue("JPEG").minFrameNs)
        }

    /** From [start] to [event], or NaN when it does not happen within [STILL_TIMEOUT_MS]. */
    private suspend fun elapsedMs(start: Long, event: CompletableDeferred<Long>): Double =
        withTimeoutOrNull(STILL_TIMEOUT_MS) { (event.await() - start) / NS_PER_MS } ?: Double.NaN

    private inline fun <T> timed(block: () -> T): Pair<T, Double> {
        val started = SystemClock.elapsedRealtimeNanos()
        val value = block()
        return value to (SystemClock.elapsedRealtimeNanos() - started) / NS_PER_MS
    }

    private companion object {
        const val TAG = "M2Experiment"
        const val LOGICAL_BACK = "0"
        val LENSES = listOf("2", "3", "9")
        const val SINK_IMAGES = 4
        const val EXPOSURE_NS = 8_000_000L
        const val ISO = 400
        const val SETTLE_MS = 1_000L
        const val STILL_TIMEOUT_MS = 3_000L
        const val BURST_MS = 3_000L
        const val NS_PER_MS = 1e6
    }
}
