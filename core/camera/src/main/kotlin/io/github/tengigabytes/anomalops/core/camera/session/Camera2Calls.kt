// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.core.camera.session

import android.annotation.SuppressLint
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

// Suspending wrappers around the Camera2 callbacks. All callbacks run on the camera HandlerThread (ADR-0007).

private const val STILL_TIMEOUT_MS = 3_000L
private const val TAG = "Camera2Calls"

/** Opens [id]; after opening, a disconnect or error is reported through [onLost]. */
@SuppressLint("MissingPermission") // The app obtains CAMERA before it creates a CameraController.
internal suspend fun CameraManager.openDevice(id: String, handler: Handler, onLost: (String) -> Unit): CameraDevice =
    suspendCancellableCoroutine { cont ->
        val callback = object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) {
                if (cont.isActive) cont.resume(device) else device.close()
            }

            override fun onDisconnected(device: CameraDevice) = fail(device, "camera $id disconnected")

            override fun onError(device: CameraDevice, error: Int) = fail(device, "camera $id error $error")

            private fun fail(device: CameraDevice, message: String) {
                device.close()
                if (cont.isActive) cont.resumeWithException(CameraFailure(message)) else onLost(message)
            }
        }
        openCamera(id, callback, handler)
    }

internal suspend fun CameraDevice.configure(
    outputs: List<OutputConfiguration>,
    executor: Executor,
    onClosed: () -> Unit,
): CameraCaptureSession = suspendCancellableCoroutine { cont ->
    val callback = object : CameraCaptureSession.StateCallback() {
        override fun onConfigured(session: CameraCaptureSession) {
            if (cont.isActive) cont.resume(session) else session.close()
        }

        override fun onConfigureFailed(session: CameraCaptureSession) {
            if (cont.isActive) cont.resumeWithException(CameraFailure("session configuration failed: $outputs"))
        }

        // Also called when a newer session replaces this one or the device closes.
        override fun onClosed(session: CameraCaptureSession) = onClosed()
    }
    createCaptureSession(SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outputs, executor, callback))
}

/** The images and result of one still request; [raw] is null when the request had no RAW target. */
internal class StillImages(val encoded: ByteArray, val raw: Image?, val result: TotalCaptureResult)

/**
 * Captures one still into [reader], and into [rawReader] when given (ADR-0005). The RAW image is handed over
 * open; if the capture fails or times out it is closed here, and a late one is dropped.
 */
internal suspend fun CameraCaptureSession.captureStill(
    request: CaptureRequest,
    reader: ImageReader,
    rawReader: ImageReader?,
    handler: Handler,
): StillImages {
    val encoded = CompletableDeferred<ByteArray>()
    reader.setOnImageAvailableListener(
        { r -> r.acquireNextImage()?.use { encoded.complete(it.encodedBytes()) } },
        handler,
    )
    // The listener and this coroutine both run on the camera thread, so a plain variable is enough.
    var raw: Image? = null
    val rawArrived = CompletableDeferred<Unit>()
    rawReader?.drain()
    rawReader?.setOnImageAvailableListener({ r ->
        // A full reader means this still goes without RAW; an empty one is a spurious call and changes nothing.
        val image = r.acquireOrNull(onFull = { rawArrived.complete(Unit) })
        when {
            image == null -> Unit
            raw == null && !rawArrived.isCompleted -> raw = image.also { rawArrived.complete(Unit) }
            else -> image.close()
        }
    }, handler)
    val result = CompletableDeferred<TotalCaptureResult>()
    capture(request, stillCallback(result), handler)
    var delivered = false
    try {
        // withTimeoutOrNull rather than withTimeout: a timeout must surface as a failure, not as a cancellation.
        val images = withTimeoutOrNull(STILL_TIMEOUT_MS) {
            val total = result.await()
            if (rawReader != null) rawArrived.await()
            StillImages(encoded.await(), raw, total)
        } ?: throw CameraFailure("still capture timed out after $STILL_TIMEOUT_MS ms")
        delivered = true
        return images
    } finally {
        if (!delivered) {
            rawReader?.setOnImageAvailableListener({ r -> r.acquireOrNull()?.close() }, handler)
            raw?.close()
        }
    }
}

private fun stillCallback(result: CompletableDeferred<TotalCaptureResult>) =
    object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, total: TotalCaptureResult) {
            result.complete(total)
        }

        override fun onCaptureFailed(s: CameraCaptureSession, r: CaptureRequest, failure: CaptureFailure) {
            result.completeExceptionally(CameraFailure("still capture failed, reason ${failure.reason}"))
        }
    }

/**
 * The next image, or null when none is queued or the reader already has maxImages out. The second case is the
 * last guard behind [RawReaders.hasRoom]: throwing here, on the camera thread, would kill the app.
 */
internal fun ImageReader.acquireOrNull(onFull: () -> Unit = {}): Image? = try {
    acquireNextImage()
} catch (e: IllegalStateException) {
    Log.w(TAG, "RAW reader full, frame left queued", e)
    onFull()
    null
}

/** Drops RAW images left queued by an earlier full reader or a late frame, so this still gets its own. */
internal fun ImageReader.drain() {
    while (true) acquireOrNull()?.close() ?: return
}

/** JPEG and JPEG_R images carry the whole encoded file in their single plane. */
private fun Image.encodedBytes(): ByteArray {
    val buffer = planes[0].buffer
    return ByteArray(buffer.remaining()).also { buffer.get(it) }
}
