// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.core.camera.session

import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.params.OutputConfiguration
import android.media.ImageReader
import android.os.Handler
import android.os.SystemClock
import android.view.Surface
import io.github.tengigabytes.anomalops.core.profile.PhysicalCamera
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executor

/**
 * The burst session of one lens: preview plus a plain-JPEG reader (FR-15, FR-68). It cannot share a session with
 * the JPEG_R reader, which restarts the camera HAL (docs/test/m2-stream-combos.md). Every JPEG is copied out and
 * its image closed at once, so the reader never runs out of buffers.
 */
internal class BurstStream private constructor(
    val session: CameraCaptureSession,
    val surface: Surface,
    private val closed: CompletableDeferred<Unit>,
) {
    /**
     * Stops the burst and waits at most [timeoutMs] for the session to close; the reader closes with it. Returns
     * the elapsed-realtime nanoseconds when `abortCaptures()` returned, and whether `onClosed` came in time, for
     * the preview-stall measurement (docs/test/m2-burst-resume.md).
     */
    suspend fun closeAndWait(timeoutMs: Long): Closing {
        session.abortCaptures()
        val abortedNs = SystemClock.elapsedRealtimeNanos()
        session.close()
        val closedInTime = withTimeoutOrNull(timeoutMs) { closed.await() } != null
        return Closing(abortedNs, closedInTime)
    }

    class Closing(val abortedNs: Long, val closedInTime: Boolean)

    companion object {
        private const val BURST_IMAGES = 8

        suspend fun open(
            device: CameraDevice,
            camera: PhysicalCamera,
            preview: Surface,
            handler: Handler,
            onJpeg: (bytes: ByteArray, sensorTimestampNs: Long) -> Unit,
        ): BurstStream {
            val (width, height) = camera.outputs.getValue("JPEG").max.split('x').map(String::toInt)
            val reader = ImageReader.newInstance(width, height, ImageFormat.JPEG, BURST_IMAGES)
            reader.setOnImageAvailableListener({ r ->
                r.acquireNextImage()?.use { image ->
                    val buffer = image.planes[0].buffer
                    onJpeg(ByteArray(buffer.remaining()).also { buffer.get(it) }, image.timestamp)
                }
            }, handler)
            val outputs = listOf(preview, reader.surface).map {
                OutputConfiguration(it).apply { setPhysicalCameraId(camera.id) }
            }
            var configured = false
            try {
                val closed = CompletableDeferred<Unit>()
                val session = device.configure(outputs, Executor { handler.post(it) }) {
                    reader.close()
                    closed.complete(Unit)
                }
                configured = true
                return BurstStream(session, reader.surface, closed)
            } finally {
                if (!configured) reader.close()
            }
        }
    }
}
