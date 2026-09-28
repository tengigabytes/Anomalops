// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.probe

import android.annotation.SuppressLint
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Handler
import android.util.Size
import android.view.Surface
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/**
 * Field-of-view experiment for the duplicate physical camera IDs (docs/test/g0-blazer.md, section 1).
 * For each pair (full, candidate crop) it compares the candidate frame with the full frame downscaled and with
 * the centre half of the full frame. It also records which physical camera the logical camera uses per zoom ratio.
 * Frames never leave memory; only correlation numbers are written.
 */
internal class FovProbe(private val manager: CameraManager, private val handler: Handler) {
    private val executor = Executor { handler.post(it) }

    fun run(): JSONObject {
        val device = open() ?: return jsonOf("error" to "openCamera failed")
        try {
            val frames = PAIRS.flatMap { listOf(it.first, it.second) }.associateWith { grab(device, it) }
            val pairs = PAIRS.map { (full, crop) -> compare(full, frames[full], crop, frames[crop]) }
            return jsonOf("pairs" to pairs.toJsonArray(), "zoomToActivePhysicalId" to zoomMap(device))
        } finally {
            device.close()
        }
    }

    private fun compare(full: String, a: Luma?, crop: String, b: Luma?): JSONObject {
        if (a == null || b == null) return jsonOf("pair" to "$full/$crop", "error" to "frame missing")
        val candidate = b.half()
        return jsonOf(
            "pair" to "$full/$crop",
            "nccWholeFrame" to Luma.ncc(a.half(), candidate),
            "nccCentreHalf" to Luma.ncc(a.centre(), candidate),
        )
    }

    private fun grab(device: CameraDevice, physicalId: String): Luma? {
        val size = pickSize(physicalId) ?: return null
        val reader = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, MAX_IMAGES)
        val done = CountDownLatch(1)
        var frames = 0
        var luma: Luma? = null
        reader.setOnImageAvailableListener({ r ->
            r.acquireLatestImage()?.use { image ->
                if (++frames == SETTLE_FRAMES) {
                    luma = Luma.from(image)
                    done.countDown()
                }
            }
        }, handler)
        val output = OutputConfiguration(reader.surface).apply { setPhysicalCameraId(physicalId) }
        val session = configure(device, output)
        try {
            session?.setRepeatingRequest(previewRequest(device, reader.surface).build(), null, handler)
            done.await(GRAB_TIMEOUT_S, TimeUnit.SECONDS)
        } finally {
            session?.close()
            reader.close()
        }
        return luma
    }

    private fun zoomMap(device: CameraDevice): JSONObject {
        val reader = ImageReader.newInstance(PREVIEW.width, PREVIEW.height, ImageFormat.YUV_420_888, MAX_IMAGES)
        reader.setOnImageAvailableListener({ it.acquireLatestImage()?.close() }, handler)
        val session = configure(device, OutputConfiguration(reader.surface))
        val result = JSONObject()
        var active: String? = null
        val listener = object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, t: TotalCaptureResult) {
                active = t.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID)
            }
        }
        try {
            for (ratio in ZOOM_RATIOS) {
                val request = previewRequest(device, reader.surface).apply { set(CaptureRequest.CONTROL_ZOOM_RATIO, ratio) }
                session?.setRepeatingRequest(request.build(), listener, handler)
                Thread.sleep(ZOOM_SETTLE_MS)
                result.put(ratio.toString(), active ?: JSONObject.NULL)
            }
        } finally {
            session?.close()
            reader.close()
        }
        return result
    }

    private fun previewRequest(device: CameraDevice, target: Surface) =
        device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply { addTarget(target) }

    private fun pickSize(cameraId: String): Size? =
        manager.getCameraCharacteristics(cameraId).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(ImageFormat.YUV_420_888)
            ?.firstOrNull { it == PREVIEW }

    private fun configure(device: CameraDevice, output: OutputConfiguration): CameraCaptureSession? {
        val latch = CountDownLatch(1)
        var configured: CameraCaptureSession? = null
        val callback = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                configured = session
                latch.countDown()
            }

            override fun onConfigureFailed(session: CameraCaptureSession) = latch.countDown()
        }
        device.createCaptureSession(
            SessionConfiguration(SessionConfiguration.SESSION_REGULAR, listOf(output), executor, callback),
        )
        latch.await(GRAB_TIMEOUT_S, TimeUnit.SECONDS)
        return configured
    }

    @SuppressLint("MissingPermission") // Only started after CAMERA is granted.
    private fun open(): CameraDevice? {
        val latch = CountDownLatch(1)
        var opened: CameraDevice? = null
        manager.openCamera(LOGICAL_BACK, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                opened = camera
                latch.countDown()
            }

            override fun onDisconnected(camera: CameraDevice) = latch.countDown()
            override fun onError(camera: CameraDevice, error: Int) = latch.countDown()
        }, handler)
        latch.await(GRAB_TIMEOUT_S, TimeUnit.SECONDS)
        return opened
    }

    private companion object {
        const val LOGICAL_BACK = "0"
        val PAIRS = listOf("2" to "5", "3" to "9", "4" to "6")
        val PREVIEW = Size(640, 480)
        val ZOOM_RATIOS = listOf(0.51f, 1f, 1.5f, 2f, 3f, 5f, 10f, 20f)
        const val MAX_IMAGES = 3
        const val SETTLE_FRAMES = 45
        const val GRAB_TIMEOUT_S = 6L
        const val ZOOM_SETTLE_MS = 1_500L
    }
}
