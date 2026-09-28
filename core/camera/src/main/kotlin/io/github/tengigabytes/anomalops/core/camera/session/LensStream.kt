// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.core.camera.session

import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.OutputConfiguration
import android.media.ImageReader
import android.view.Surface
import io.github.tengigabytes.anomalops.core.camera.request.StillFormat
import io.github.tengigabytes.anomalops.core.profile.PhysicalCamera
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executor

/**
 * One capture session on one physical lens of the logical camera: the preview surface plus a full-resolution
 * still reader (FR-61a), both routed with `setPhysicalCameraId` (verified for every lens in G0).
 */
internal class LensStream private constructor(
    val camera: PhysicalCamera,
    val format: StillFormat,
    val sensorOrientation: Int,
    val reader: ImageReader,
    val session: CameraCaptureSession,
    private val closed: CompletableDeferred<Unit>,
) : AutoCloseable {
    /**
     * Asynchronous close for a lens switch: the reader is closed from the session's `onClosed`, once the HAL has
     * returned its buffers. Closing the reader right away made the HAL queue into an abandoned buffer queue.
     */
    override fun close() {
        // Drop the in-flight preview requests instead of letting them drain: session reconfiguration on a lens
        // switch fell from a 260 ms to a 122 ms median (docs/test/m1-instrumented.md). Stills are serialised
        // with switches, so only preview frames are dropped.
        session.abortCaptures()
        session.close()
    }

    /**
     * Close before the camera device closes: after `CameraDevice.close()` the session's `onClosed` was not
     * delivered on the Pixel 10 Pro, which leaked the reader. Waits at most [timeoutMs] for it.
     */
    suspend fun closeAndWait(timeoutMs: Long) {
        session.close()
        withTimeoutOrNull(timeoutMs) { closed.await() }
        reader.close()
    }

    companion object {
        private const val MAX_STILL_IMAGES = 2

        suspend fun open(
            manager: CameraManager,
            device: CameraDevice,
            camera: PhysicalCamera,
            preview: Surface,
            executor: Executor,
        ): LensStream {
            val format = StillFormat.bestFor(camera)
            val (width, height) = camera.outputs.getValue(format.name).max.split('x').map(String::toInt)
            val reader = ImageReader.newInstance(width, height, format.imageFormat(), MAX_STILL_IMAGES)
            val outputs = listOf(preview, reader.surface).map {
                OutputConfiguration(it).apply { setPhysicalCameraId(camera.id) }
            }
            var configured = false
            try {
                val closed = CompletableDeferred<Unit>()
                val session = device.configure(outputs, executor) {
                    reader.close()
                    closed.complete(Unit)
                }
                configured = true
                val orientation = manager.getCameraCharacteristics(camera.id)
                    .get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
                return LensStream(camera, format, orientation, reader, session, closed)
            } finally {
                if (!configured) reader.close()
            }
        }

        private fun StillFormat.imageFormat(): Int = when (this) {
            StillFormat.JPEG_R -> ImageFormat.JPEG_R
            StillFormat.JPEG -> ImageFormat.JPEG
        }
    }
}
