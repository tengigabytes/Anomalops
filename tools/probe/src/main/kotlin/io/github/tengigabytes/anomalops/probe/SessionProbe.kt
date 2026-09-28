// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.probe

import android.annotation.SuppressLint
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.ImageReader
import android.os.Handler
import android.util.Size
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/**
 * Asks the HAL whether output combinations are supported, without starting a session or capturing.
 * Answers ADR-0005 (JPEG/JPEG_R + RAW in one request) and ADR-0001 / FR-95 (physical camera outputs).
 */
internal class SessionProbe(private val manager: CameraManager, private val handler: Handler) {

    private class Combo(val name: String, val formats: List<Int>)

    private val combos = listOf(
        Combo("JPEG+RAW", listOf(ImageFormat.JPEG, ImageFormat.RAW_SENSOR)),
        Combo("JPEG_R+RAW", listOf(ImageFormat.JPEG_R, ImageFormat.RAW_SENSOR)),
        Combo("YUV1080+JPEG_R+RAW", listOf(ImageFormat.YUV_420_888, ImageFormat.JPEG_R, ImageFormat.RAW_SENSOR)),
        Combo("YUV1080+JPEG", listOf(ImageFormat.YUV_420_888, ImageFormat.JPEG)),
    )

    fun probe(logicalId: String): JSONArray {
        val results = JSONArray()
        val device = open(logicalId)
            ?: return results.put(jsonOf("error" to "openCamera failed or timed out"))
        try {
            val logical = manager.getCameraCharacteristics(logicalId)
            val targets = listOf<String?>(null) + logical.physicalCameraIds.sorted()
            for (physicalId in targets) {
                val ch = physicalId?.let { manager.getCameraCharacteristics(it) } ?: logical
                val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                combos.forEach { results.put(test(device, map, physicalId, it)) }
            }
        } finally {
            device.close()
        }
        return results
    }

    private fun test(device: CameraDevice, map: StreamConfigurationMap?, physicalId: String?, combo: Combo): JSONObject {
        val sizes = combo.formats.map { it to pickSize(map, it) }
        val result = jsonOf(
            "physicalCameraId" to physicalId,
            "combo" to combo.name,
            "sizes" to sizes.map { (f, s) -> "${ConstantNames.format(f)}:${s ?: "unavailable"}" }.toJsonArray(),
        )
        if (sizes.any { it.second == null }) return result.put("supported", JSONObject.NULL)
        val readers = sizes.map { (format, size) -> ImageReader.newInstance(size!!.width, size.height, format, 2) }
        try {
            val outputs = readers.map { reader ->
                OutputConfiguration(reader.surface).apply { physicalId?.let { setPhysicalCameraId(it) } }
            }
            val config = SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outputs, executor, NoopSession)
            val answer = runCatching { device.isSessionConfigurationSupported(config) }
            result.put("supported", answer.getOrNull() ?: JSONObject.NULL)
            answer.exceptionOrNull()?.let { result.put("error", "${it.javaClass.simpleName}: ${it.message}") }
        } finally {
            readers.forEach { it.close() }
        }
        return result
    }

    /** YUV stands in for a 1080p preview stream; still formats use their largest size. */
    private fun pickSize(map: StreamConfigurationMap?, format: Int): Size? {
        val sizes = map?.getOutputSizes(format)?.toList().orEmpty()
        if (format != ImageFormat.YUV_420_888) return sizes.maxByOrNull { it.area() }
        return sizes.firstOrNull { it.width == 1920 && it.height == 1080 }
            ?: sizes.filter { it.width <= 1920 }.maxByOrNull { it.area() }
    }

    @SuppressLint("MissingPermission") // ProbeReport only calls this after CAMERA is granted.
    private fun open(id: String): CameraDevice? {
        val latch = CountDownLatch(1)
        var opened: CameraDevice? = null
        manager.openCamera(id, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                opened = camera
                latch.countDown()
            }

            override fun onDisconnected(camera: CameraDevice) {
                camera.close()
                latch.countDown()
            }

            override fun onError(camera: CameraDevice, error: Int) {
                camera.close()
                latch.countDown()
            }
        }, handler)
        latch.await(OPEN_TIMEOUT_S, TimeUnit.SECONDS)
        return opened
    }

    private val executor = Executor { handler.post(it) }

    private object NoopSession : CameraCaptureSession.StateCallback() {
        override fun onConfigured(session: CameraCaptureSession) = Unit
        override fun onConfigureFailed(session: CameraCaptureSession) = Unit
    }

    private companion object {
        const val OPEN_TIMEOUT_S = 5L
    }
}
