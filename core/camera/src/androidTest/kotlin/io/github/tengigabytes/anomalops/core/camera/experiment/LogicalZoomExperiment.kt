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
 * ADR-0011 experiment, first stage: the logical back camera as a single viewfinder. Covers the ADR's check 4 only
 * as far as whether the stream sets configure (preview 1920x1440 as PREVIEW_VIDEO_STILL plus a still output; no
 * 60 fps, no encoder surface), and parts of checks 1 and 3: how a continuous zoom from the widest ratio to 10x
 * switches lenses (frame gaps, active physical camera) and each ratio's still size and lens. Checks 2 (wrong
 * calibration across a lens switch) and 5 (stills while recording) are not covered, so this run alone cannot
 * accept ADR-0011. Each stream set reopens the camera, because a failed configuration can restart the camera HAL
 * (docs/test/m2-stream-combos.md). Results go to logcat under [TAG]; nothing is saved.
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
        // Without a JPEG_R set the lens switches are still worth measuring on the guaranteed JPEG set.
        if (!zoomDone) runSet(sets.first(), withZoom = true)
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
            // A failed configuration can restart the camera HAL (m2-stream-combos.md) and then no callback comes.
            val session = runCatching {
                withTimeoutOrNull(
                    CONFIGURE_TIMEOUT_MS,
                ) { device.configure(outputs, executor) {} }
            }
                .onFailure { Log.i(TAG, "${set.name}: configure FAILED: ${it.message?.take(MESSAGE_CHARS)}") }
                .getOrNull()
            if (session == null) Log.i(TAG, "${set.name}: no session (failed or timed out)")
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

    /** The widest ratio to 10x and back over [SWEEP_MS] each way, one zoom step per frame. */
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
        val (minZoom, maxZoom) = zoomRange()
        hold(minZoom)
        delay(SETTLE_MS)
        val steps = (SWEEP_MS / FRAME_MS).toInt()
        val zooms = List(steps + 1) { minZoom * (maxZoom / minZoom).pow(it.toFloat() / steps) }
        (zooms + zooms.reversed()).forEach { hold(it) }
        session.stopRepeating()
        report()
    }

    /**
     * Frame gaps at the sensor (dropped frames) and on arrival in the app (a frozen preview), and every lens change
     * with the sensor gaps around it. Frames without a timestamp or active lens are left out.
     */
    private fun report() {
        val usable = frames.filter { it.sensorNs > 0 }
        if (usable.size < 2) {
            Log.i(TAG, "zoom sweep: only ${usable.size} frames with a timestamp")
            return
        }
        val gaps = usable.zipWithNext { a, b -> (b.sensorNs - a.sensorNs) / NS_PER_MS }
        val arrivals = usable.zipWithNext { a, b -> (b.arrivedNs - a.arrivedNs) / NS_PER_MS }.sorted()
        val sorted = gaps.sorted()
        Log.i(
            TAG,
            "zoom sweep: ${usable.size} frames, sensor gap median %.1f max %.1f ms, arrival gap median %.1f max %.1f ms"
                .format(sorted[sorted.size / 2], sorted.last(), arrivals[arrivals.size / 2], arrivals.last()),
        )
        usable.zipWithNext().forEachIndexed { index, (a, b) ->
            if (a.lens != null && b.lens != null && a.lens != b.lens) {
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
        (listOf(zoomRange().first) + STILL_ZOOMS).forEach { zoom ->
            session.setRepeatingRequest(previewRequest(device, preview, zoom), null, handler)
            delay(SETTLE_MS)
            // A still that timed out at the previous ratio must not be counted at this one.
            generateSequence { still.acquireNextImage() }.forEach { it.close() }
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

    /** The logical camera's zoom range, capped at [MAX_ZOOM]; beyond that is digital crop only. */
    private fun zoomRange(): Pair<Float, Float> {
        val range = manager.getCameraCharacteristics(LOGICAL_BACK).get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)!!
        return range.lower to minOf(range.upper, MAX_ZOOM)
    }

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
        const val MAX_ZOOM = 10f
        const val CONFIGURE_TIMEOUT_MS = 5_000L
        const val SWEEP_MS = 6_000L
        const val FRAME_MS = 33L
        const val SETTLE_MS = 1_500L
        const val STILL_TIMEOUT_MS = 3_000L
        const val OPEN_ATTEMPTS = 8
        const val MESSAGE_CHARS = 90
        const val NS_PER_MS = 1e6
        val STILL_ZOOMS = listOf(1f, 2f, 3f, 5f, 10f)
    }
}
