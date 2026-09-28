// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.core.camera.experiment

import android.Manifest
import android.graphics.ImageFormat
import android.hardware.HardwareBuffer
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.util.Size
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import io.github.tengigabytes.anomalops.core.camera.session.configure
import io.github.tengigabytes.anomalops.core.camera.session.openDevice
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
import kotlin.math.pow

/**
 * ADR-0011 experiment: the logical back camera as a single viewfinder. Checks which stream sets configure
 * (preview 1920x1440 as PREVIEW_VIDEO_STILL plus a still output), how a continuous 0.51x to 10x zoom switches
 * lenses (frame gaps, active physical camera), and what a still looks like at each zoom ratio. Each stream set
 * reopens the camera, because a failed configuration can restart the camera HAL (docs/test/m2-stream-combos.md).
 * Results go to logcat under [TAG]; nothing is saved.
 */
@RunWith(AndroidJUnit4::class)
class LogicalZoomExperiment {
    @get:Rule
    val cameraPermission: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.CAMERA)

    private val manager = InstrumentationRegistry.getInstrumentation().targetContext
        .getSystemService(CameraManager::class.java)
    private val thread = HandlerThread("experiment").apply { start() }
    private val handler = Handler(thread.looper)
    private val executor = Executor { handler.post(it) }
    private val frames = mutableListOf<Frame>()

    private class Frame(val arrivedNs: Long, val sensorNs: Long, val zoom: Float?, val lens: String?)

    private class StreamSet(val name: String, val stillFormat: Int, val raw: Boolean)

    @After
    fun tearDown() {
        thread.quitSafely()
    }

    @Test
    fun adr0011_logicalViewfinder() = runBlocking<Unit>(handler.asCoroutineDispatcher()) {
        // Lowest risk first: the JPEG set is in the STREAM_USE_CASE guarantee table, the JPEG_R sets are not.
        val sets = listOf(
            StreamSet("PVS1440+JPEG", ImageFormat.JPEG, raw = false),
            StreamSet("PVS1440+JPEG_R", ImageFormat.JPEG_R, raw = false),
            StreamSet("PVS1440+JPEG_R+RAW", ImageFormat.JPEG_R, raw = true),
        )
        var zoomDone = false
        sets.forEach { set ->
            val wantZoom = !zoomDone && set.stillFormat == ImageFormat.JPEG_R
            val configured = runSet(set, wantZoom)
            if (configured && wantZoom) zoomDone = true
        }
    }

    /** Configures [set] on a freshly opened camera; returns whether it configured. */
    private suspend fun runSet(set: StreamSet, withZoom: Boolean): Boolean {
        val device = openWithRetry()
        if (device == null) {
            Log.i(TAG, "${set.name}: camera did not open")
            return false
        }
        val preview = ImageReader.newInstance(
            PREVIEW.width,
            PREVIEW.height,
            ImageFormat.PRIVATE,
            SINK_IMAGES,
            HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE,
        ).apply { setOnImageAvailableListener({ it.acquireLatestImage()?.close() }, handler) }
        val still = reader(set.stillFormat, STILL_IMAGES)
        val raw = if (set.raw) reader(ImageFormat.RAW_SENSOR, STILL_IMAGES) else null
        try {
            val outputs = listOfNotNull(
                OutputConfiguration(preview.surface).apply {
                    setStreamUseCase(
                        CameraCharacteristics.SCALER_AVAILABLE_STREAM_USE_CASES_PREVIEW_VIDEO_STILL.toLong(),
                    )
                },
                OutputConfiguration(still.surface).apply {
                    setStreamUseCase(CameraCharacteristics.SCALER_AVAILABLE_STREAM_USE_CASES_STILL_CAPTURE.toLong())
                },
                raw?.let { OutputConfiguration(it.surface) },
            )
            val session = runCatching { device.configure(outputs, executor) {} }
                .onFailure { Log.i(TAG, "${set.name}: configure FAILED: ${it.message?.take(MESSAGE_CHARS)}") }
                .getOrNull()
            if (session != null) {
                Log.i(TAG, "${set.name}: configured (still ${still.width}x${still.height}, RAW ${raw != null})")
                if (withZoom) {
                    zoomSweep(device, session, preview)
                    stills(device, session, preview, still)
                }
                session.close()
            }
            return session != null
        } finally {
            device.close()
            listOfNotNull(preview, still, raw).forEach { it.close() }
            delay(SETTLE_MS)
        }
    }

    /** 0.51x to 10x and back over [SWEEP_MS] each way, one zoom step per frame. */
    private suspend fun zoomSweep(device: CameraDevice, session: CameraCaptureSession, preview: ImageReader) {
        frames.clear()
        val listener = object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult) {
                frames += Frame(
                    arrivedNs = SystemClock.elapsedRealtimeNanos(),
                    sensorNs = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: 0L,
                    zoom = result.get(CaptureResult.CONTROL_ZOOM_RATIO),
                    lens = result.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID),
                )
            }
        }
        suspend fun hold(zoom: Float) {
            session.setRepeatingRequest(previewRequest(device, preview, zoom), listener, handler)
            delay(FRAME_MS)
        }
        hold(MIN_ZOOM)
        delay(SETTLE_MS)
        val steps = (SWEEP_MS / FRAME_MS).toInt()
        val zooms = List(steps + 1) { MIN_ZOOM * (MAX_ZOOM / MIN_ZOOM).pow(it.toFloat() / steps) }
        (zooms + zooms.reversed()).forEach { hold(it) }
        session.stopRepeating()
        report()
    }

    /** Frame gaps, and every lens change with the gap around it. */
    private fun report() {
        val gaps = frames.zipWithNext { a, b -> (b.sensorNs - a.sensorNs) / NS_PER_MS }
        val sorted = gaps.sorted()
        Log.i(
            TAG,
            "zoom sweep: ${frames.size} frames, gap median %.1f max %.1f ms".format(
                sorted[sorted.size / 2],
                sorted.last(),
            ),
        )
        frames.zipWithNext().forEachIndexed { index, (a, b) ->
            if (a.lens != b.lens) {
                val around = gaps.subList((index - 2).coerceAtLeast(0), (index + 3).coerceAtMost(gaps.size))
                Log.i(
                    TAG,
                    "lens ${a.lens} -> ${b.lens} at zoom %.2f; gaps around: ${around.map {
                        "%.0f".format(
                            it,
                        )
                    }}".format(b.zoom),
                )
            }
        }
    }

    /** One still at each zoom ratio: output size, bytes and the lens that took it. */
    private suspend fun stills(
        device: CameraDevice,
        session: CameraCaptureSession,
        preview: ImageReader,
        still: ImageReader,
    ) {
        STILL_ZOOMS.forEach { zoom ->
            session.setRepeatingRequest(previewRequest(device, preview, zoom), null, handler)
            delay(SETTLE_MS)
            val image = CompletableDeferred<String>()
            still.setOnImageAvailableListener({ r ->
                r.acquireNextImage()?.use {
                    image.complete(
                        "${it.width}x${it.height} ${it.planes[0].buffer.remaining()} B",
                    )
                }
            }, handler)
            val result = CompletableDeferred<TotalCaptureResult>()
            val request = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(still.surface)
                set(CaptureRequest.CONTROL_ZOOM_RATIO, zoom)
                set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
            }.build()
            session.capture(
                request,
                object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, t: TotalCaptureResult) {
                        result.complete(t)
                    }
                },
                handler,
            )
            val done = withTimeoutOrNull(STILL_TIMEOUT_MS) { result.await() to image.await() }
            val lens = done?.first?.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID)
            Log.i(TAG, "still at %.2fx: lens=$lens ${done?.second ?: "TIMEOUT"}".format(zoom))
        }
    }

    private fun previewRequest(device: CameraDevice, preview: ImageReader, zoom: Float) =
        device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(preview.surface)
            set(CaptureRequest.CONTROL_ZOOM_RATIO, zoom)
            set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
        }.build()

    private fun reader(format: Int, images: Int): ImageReader {
        val sizes = manager.getCameraCharacteristics(LOGICAL_BACK)
            .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!.getOutputSizes(format)
        val largest = sizes.maxBy { it.width.toLong() * it.height }
        return ImageReader.newInstance(largest.width, largest.height, format, images)
    }

    /** After a HAL restart the camera is unavailable for a few seconds. */
    private suspend fun openWithRetry(): CameraDevice? {
        repeat(OPEN_ATTEMPTS) {
            runCatching { return manager.openDevice(LOGICAL_BACK, handler) { Log.e(TAG, "camera lost: $it") } }
            delay(SETTLE_MS)
        }
        return null
    }

    private companion object {
        const val TAG = "M7Experiment"
        const val LOGICAL_BACK = "0"
        val PREVIEW = Size(1920, 1440)
        const val SINK_IMAGES = 4
        const val STILL_IMAGES = 2
        const val MIN_ZOOM = 0.51f
        const val MAX_ZOOM = 10f
        const val SWEEP_MS = 6_000L
        const val FRAME_MS = 33L
        const val SETTLE_MS = 1_500L
        const val STILL_TIMEOUT_MS = 3_000L
        const val OPEN_ATTEMPTS = 8
        const val MESSAGE_CHARS = 90
        const val NS_PER_MS = 1e6
        val STILL_ZOOMS = listOf(0.51f, 1f, 2f, 3f, 5f, 10f)
    }
}
