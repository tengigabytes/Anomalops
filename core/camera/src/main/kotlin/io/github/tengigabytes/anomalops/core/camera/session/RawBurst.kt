// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.core.camera.session

import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.ByteOrder

private const val BURST_TIMEOUT_MS = 3_000L

/**
 * One `RAW_SENSOR` frame copied out of its camera buffer (FR-17), so the buffer goes back at once and the RAW
 * reader's few images stay free for the FR-62 buffer. [samples] holds [rowStride] samples per row; [result] is the
 * physical lens's.
 */
class RawSamples(
    val samples: ShortArray,
    val width: Int,
    val height: Int,
    val rowStride: Int,
    val sensorTimestampNs: Long,
    val result: CaptureResult,
)

/**
 * A still and the RAW frames to merge with it (FR-17): [frames] starts with the still's own frame, followed by
 * the frames of the burst taken right after it, in exposure order; empty when the lens has no RAW output or the
 * RAW reader was full. [characteristics] are the physical lens's.
 */
class MultiFrameCapture(
    val still: StillCapture,
    val frames: List<RawSamples>,
    val characteristics: CameraCharacteristics,
)

/**
 * FR-17: [requests] as one burst into [rawReader]. Each frame is copied as it arrives and its buffer returned at
 * once, so the burst needs one free image, not one per frame. Returns the frames whose image and result both
 * came within [BURST_TIMEOUT_MS], in exposure order; a failed or late frame is left out.
 */
internal suspend fun CameraCaptureSession.captureRawBurst(
    requests: List<CaptureRequest>,
    rawReader: ImageReader,
    lensId: String,
    handler: Handler,
): List<RawSamples> {
    // The listener, the callback and this coroutine all run on the camera thread, so plain collections do.
    val copies = HashMap<Long, (CaptureResult) -> RawSamples>()
    val results = ArrayList<CaptureResult>()
    var failures = 0
    val settled = CompletableDeferred<Unit>()
    fun check() {
        val all = results.size + failures == requests.size
        if (all && results.all { it.get(CaptureResult.SENSOR_TIMESTAMP) in copies }) settled.complete(Unit)
    }
    rawReader.drain()
    rawReader.setOnImageAvailableListener({ r ->
        r.acquireOrNull()?.use { image -> copies[image.timestamp] = image.copySamples() }
        check()
    }, handler)
    val callback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, total: TotalCaptureResult) {
            results += total.physicalCameraTotalResults[lensId] ?: total
            check()
        }

        override fun onCaptureFailed(s: CameraCaptureSession, r: CaptureRequest, failure: CaptureFailure) {
            failures++
            check()
        }
    }
    captureBurst(requests, callback, handler)
    withTimeoutOrNull(BURST_TIMEOUT_MS) { settled.await() }
    rawReader.setOnImageAvailableListener({ r -> r.acquireOrNull()?.close() }, handler)
    return results.sortedBy { it.get(CaptureResult.SENSOR_TIMESTAMP) }
        .mapNotNull { result -> copies[result.get(CaptureResult.SENSOR_TIMESTAMP)]?.invoke(result) }
}

/** The samples of a `RAW_SENSOR` image, waiting for the result of the same frame. */
internal fun Image.copySamples(): (CaptureResult) -> RawSamples {
    val plane = planes[0]
    val buffer = plane.buffer.order(ByteOrder.nativeOrder()).asShortBuffer()
    val samples = ShortArray(buffer.remaining()).also { buffer.get(it) }
    val w = width
    val h = height
    val stride = plane.rowStride / Short.SIZE_BYTES
    val timestampNs = timestamp
    return { result -> RawSamples(samples, w, h, stride, timestampNs, result) }
}
