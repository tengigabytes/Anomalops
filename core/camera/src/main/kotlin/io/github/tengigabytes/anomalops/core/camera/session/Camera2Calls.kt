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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

// Suspending wrappers around the Camera2 callbacks. All callbacks run on the camera HandlerThread (ADR-0007).

private const val STILL_TIMEOUT_MS = 3_000L

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

/** Captures one still into [reader] and returns the encoded bytes with their result. */
internal suspend fun CameraCaptureSession.captureStill(
    request: CaptureRequest,
    reader: ImageReader,
    handler: Handler,
): Pair<ByteArray, TotalCaptureResult> {
    val image = CompletableDeferred<ByteArray>()
    reader.setOnImageAvailableListener(
        { r -> r.acquireNextImage()?.use { image.complete(it.encodedBytes()) } },
        handler,
    )
    val result = CompletableDeferred<TotalCaptureResult>()
    val callback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, total: TotalCaptureResult) {
            result.complete(total)
        }

        override fun onCaptureFailed(s: CameraCaptureSession, r: CaptureRequest, failure: CaptureFailure) {
            result.completeExceptionally(CameraFailure("still capture failed, reason ${failure.reason}"))
        }
    }
    capture(request, callback, handler)
    // withTimeoutOrNull rather than withTimeout: a timeout must surface as a failure, not as a cancellation.
    return withTimeoutOrNull(STILL_TIMEOUT_MS) {
        val total = result.await()
        image.await() to total
    } ?: throw CameraFailure("still capture timed out after $STILL_TIMEOUT_MS ms")
}

/** JPEG and JPEG_R images carry the whole encoded file in their single plane. */
private fun Image.encodedBytes(): ByteArray {
    val buffer = planes[0].buffer
    return ByteArray(buffer.remaining()).also { buffer.get(it) }
}
