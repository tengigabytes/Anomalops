// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.core.camera.session

import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.Image
import android.os.Handler
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import io.github.tengigabytes.anomalops.core.camera.request.CaptureRequestWriter
import io.github.tengigabytes.anomalops.core.camera.request.RequestPlanner
import io.github.tengigabytes.anomalops.core.camera.request.RequestSpec
import java.util.Locale
import java.util.concurrent.Executor

/**
 * The Camera2 objects behind [CameraController]: the open logical camera, the session of the current
 * physical lens and the latest preview metering. Used only on the camera thread, one call at a time.
 */
internal class LensSwitcher(
    private val manager: CameraManager,
    private val handler: Handler,
    private val planner: RequestPlanner,
    private val onLost: (String) -> Unit,
    private val onFrame: (PreviewFrame) -> Unit,
    onScan: (FocusScan) -> Unit,
) {
    private val executor = Executor { handler.post(it) }
    private val focus = FocusScanner(planner, handler, onScan)
    private val meter = PreviewMeter(focus, onFrame)
    private var device: CameraDevice? = null
    private var stream: LensStream? = null
    private var surface: Surface? = null
    private var preview: RequestSpec? = null
    private val rawReaders = RawReaders()

    /** Previews [spec] on [target], or on the current surface when null; rebuilds the session only if needed. */
    suspend fun show(spec: RequestSpec, target: Surface?) {
        val next = target ?: checkNotNull(surface) { "no preview surface" }
        if (next != surface) closeStream()
        surface = next
        val lens = stream?.takeIf { it.camera.id == spec.physicalId } ?: openStream(spec, next)
        val withTarget: RequestBuilder = { s, template, extra ->
            request(s, template) { extra().also { addTarget(next) } }
        }
        focus.show(lens.session, spec, withTarget) { shown ->
            val repeating = withTarget(shown, CameraDevice.TEMPLATE_PREVIEW) {}
            lens.session.setRepeatingRequest(repeating, meter.callback(shown), handler)
            preview = shown
        }
    }

    /** One still with the ADR-0009 shutter-priority exposure derived from the latest preview frame. */
    suspend fun takeStill(): StillCapture {
        val lens = checkNotNull(stream) { "preview not started" }
        focus.settled()
        val spec = planner.still(checkNotNull(preview), meter.awaitMetered(), lens.format)
        // FR-62 frames held elsewhere may fill the RAW reader; then this still has no RAW rather than a crash.
        val rawReader = lens.rawReader?.takeIf { rawReaders.hasRoom(lens.camera.id) }
        val request = request(spec, CameraDevice.TEMPLATE_STILL_CAPTURE) {
            addTarget(lens.reader.surface)
            rawReader?.let { addTarget(it.surface) }
            // Upright for the portrait-locked M1 screen (docs/test/m1-mediastore.md); M3 revisits orientation.
            set(CaptureRequest.JPEG_ORIENTATION, lens.sensorOrientation)
        }
        val images = lens.session.captureStill(request, lens.reader, rawReader, handler)
        val result = images.result
        return StillCapture(
            bytes = images.encoded,
            format = lens.format,
            spec = spec,
            sensorTimestampNs = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: 0L,
            flashFired = result.get(CaptureResult.FLASH_STATE) == CaptureResult.FLASH_STATE_FIRED,
            reported = reportedSettings(result, spec.physicalId),
            raw = images.raw?.let { rawFrame(lens, it, result) },
        )
    }

    /**
     * FR-17: [takeStill], then [extraFrames] more RAW frames with the same manual exposure, focus and colour, for
     * a multi-frame merge. The still comes first so the shutter moment and a finished picture do not depend on
     * the merge. The extra frames ask for the lens shading map, which the merge's rendering needs.
     */
    suspend fun takeStillWithRawBurst(extraFrames: Int): MultiFrameCapture {
        val still = takeStill()
        val lens = checkNotNull(stream) { "preview not started" }
        val raw = still.raw
        val reader = lens.rawReader
        if (raw == null || reader == null) return MultiFrameCapture(still, emptyList(), lens.characteristics)
        val own = raw.image.copySamples()(raw.result)
        val request = request(still.spec, CameraDevice.TEMPLATE_STILL_CAPTURE) {
            addTarget(reader.surface)
            val on = CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE_ON
            set(CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE, on)
            // Only allowed when the key is among the physical camera's request keys.
            runCatching { setPhysicalCameraKey(CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE, on, lens.camera.id) }
        }
        val burst = lens.session.captureRawBurst(List(extraFrames) { request }, reader, lens.camera.id, handler)
        return MultiFrameCapture(still, listOf(own) + burst, lens.characteristics)
    }

    /**
     * FR-15: swaps the single session for the burst session, repeats one manual JPEG request at [fps] until [until]
     * returns, then restores the single session and its preview. Returns the number of frames delivered.
     */
    suspend fun burst(fps: Int, onFrame: (BurstFrame) -> Unit, until: suspend () -> Unit): Int {
        val lens = checkNotNull(stream) { "preview not started" }
        focus.settled()
        val base = checkNotNull(preview)
        val target = checkNotNull(surface)
        val spec = planner.burst(base, meter.awaitMetered(), fps)
        closeStream()
        var count = 0
        val burst = BurstStream.open(checkNotNull(device), lens.camera, target, handler) { bytes, timestamp ->
            onFrame(BurstFrame(count++, bytes, timestamp, spec))
        }
        try {
            val request = request(spec, CameraDevice.TEMPLATE_STILL_CAPTURE) {
                addTarget(target)
                addTarget(burst.surface)
                set(CaptureRequest.JPEG_ORIENTATION, lens.sensorOrientation)
            }
            burst.session.setRepeatingRequest(request, null, handler)
            until()
        } finally {
            val releasedNs = SystemClock.elapsedRealtimeNanos()
            val closing = burst.closeAndWait(SESSION_CLOSE_TIMEOUT_MS)
            val closedNs = SystemClock.elapsedRealtimeNanos()
            show(base, target)
            logResume(releasedNs, closing, closedNs, SystemClock.elapsedRealtimeNanos())
        }
        return count
    }

    /**
     * Steps B and C of the preview stall after a burst (docs/test/m2-burst-resume.md); the first preview frame
     * after release (step D) is timed by the caller from [PreviewFrame]s.
     */
    private fun logResume(releasedNs: Long, closing: BurstStream.Closing, closedNs: Long, shownNs: Long) {
        fun ms(from: Long, to: Long) = "%.1f".format(Locale.ROOT, (to - from) / NS_PER_MS)
        Log.i(
            TAG,
            "burst resume: abort ${ms(releasedNs, closing.abortedNs)} ms, close ${ms(closing.abortedNs, closedNs)} ms" +
                " (onClosed in time: ${closing.closedInTime}), reopen ${ms(closedNs, shownNs)} ms",
        )
    }

    /** ADR-0005: hand the RAW image over with the physical lens's characteristics and result, for DngCreator. */
    private fun rawFrame(lens: LensStream, image: Image, total: TotalCaptureResult): RawFrame {
        val lensId = lens.camera.id
        rawReaders.acquired(lensId)
        val physical = total.physicalCameraTotalResults[lensId] ?: total
        return RawFrame(image, lens.characteristics, physical, lens.sensorOrientation) {
            handler.post { rawReaders.released(lensId) }
        }
    }

    /** Orderly shutdown for stop(): the session closes before the device, so no reader is left open. */
    suspend fun shutdown() {
        stream?.closeAndWait(SESSION_CLOSE_TIMEOUT_MS)
        stream = null
        closeAll()
    }

    /** Immediate close, for release and for a lost camera. */
    fun closeAll() {
        closeStream()
        rawReaders.retireAll()
        device?.close()
        device = null
        surface = null
        preview = null
    }

    private fun closeStream() {
        stream?.close()
        stream = null
        focus.reset()
        meter.reset()
    }

    private suspend fun openStream(spec: RequestSpec, target: Surface): LensStream {
        closeStream()
        val camera = planner.camera(spec.preset)
        val opened = device ?: manager.openDevice(camera.logicalId, handler) { message ->
            closeAll()
            onLost(message)
        }.also { device = it }
        return LensStream.open(manager, opened, camera, target, rawReaders.readerFor(camera), executor)
            .also { stream = it }
    }

    private fun request(spec: RequestSpec, template: Int, targets: CaptureRequest.Builder.() -> Unit): CaptureRequest {
        val builder = checkNotNull(device).createCaptureRequest(template, setOf(spec.physicalId))
        builder.targets()
        builder.setTag(spec)
        CaptureRequestWriter.write(builder, spec)
        return builder.build()
    }

    private companion object {
        const val SESSION_CLOSE_TIMEOUT_MS = 1_000L
        const val TAG = "LensSwitcher"
        const val NS_PER_MS = 1e6
    }
}
