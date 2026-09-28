// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.core.camera.session

import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.os.Handler
import android.view.Surface
import io.github.tengigabytes.anomalops.core.camera.exposure.Exposure
import io.github.tengigabytes.anomalops.core.camera.request.CaptureRequestWriter
import io.github.tengigabytes.anomalops.core.camera.request.RequestPlanner
import io.github.tengigabytes.anomalops.core.camera.request.RequestSpec
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
) {
    private val executor = Executor { handler.post(it) }
    private var device: CameraDevice? = null
    private var stream: LensStream? = null
    private var surface: Surface? = null
    private var preview: RequestSpec? = null
    private var metered: Exposure? = null

    /** Previews [spec] on [target], or on the current surface when null; rebuilds the session only if needed. */
    suspend fun show(spec: RequestSpec, target: Surface?) {
        val next = target ?: checkNotNull(surface) { "no preview surface" }
        if (next != surface) closeStream()
        surface = next
        val lens = stream?.takeIf { it.camera.id == spec.physicalId } ?: openStream(spec, next)
        val request = request(spec, CameraDevice.TEMPLATE_PREVIEW) { addTarget(next) }
        lens.session.setRepeatingRequest(request, meter(spec.physicalId), handler)
        preview = spec
    }

    /** One still with the ADR-0009 shutter-priority exposure derived from the latest preview frame. */
    suspend fun takeStill(): StillCapture {
        val lens = checkNotNull(stream) { "preview not started" }
        val exposure = metered ?: throw CameraFailure("no metered preview frame yet")
        val spec = planner.still(checkNotNull(preview), exposure, lens.format)
        val request = request(spec, CameraDevice.TEMPLATE_STILL_CAPTURE) {
            addTarget(lens.reader.surface)
            // Upright for the portrait-locked M1 screen (docs/test/m1-mediastore.md); M3 revisits orientation.
            set(CaptureRequest.JPEG_ORIENTATION, lens.sensorOrientation)
        }
        val (bytes, result) = lens.session.captureStill(request, lens.reader, handler)
        return StillCapture(
            bytes = bytes,
            format = lens.format,
            spec = spec,
            sensorTimestampNs = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: 0L,
            flashFired = result.get(CaptureResult.FLASH_STATE) == CaptureResult.FLASH_STATE_FIRED,
            reported = reported(result.physicalCameraTotalResults[spec.physicalId] ?: result, result),
        )
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
        device?.close()
        device = null
        surface = null
        preview = null
    }

    private fun closeStream() {
        stream?.close()
        stream = null
        metered = null
    }

    private suspend fun openStream(spec: RequestSpec, target: Surface): LensStream {
        closeStream()
        val camera = planner.camera(spec.preset)
        val opened = device ?: manager.openDevice(camera.logicalId, handler) { message ->
            closeAll()
            onLost(message)
        }.also { device = it }
        return LensStream.open(manager, opened, camera, target, executor).also { stream = it }
    }

    private fun request(spec: RequestSpec, template: Int, targets: CaptureRequest.Builder.() -> Unit): CaptureRequest {
        val builder = checkNotNull(device).createCaptureRequest(template, setOf(spec.physicalId))
        builder.targets()
        CaptureRequestWriter.write(builder, spec)
        return builder.build()
    }

    /** Per-lens keys come from the physical result; mode keys exist only on the logical one. */
    private fun reported(physical: CaptureResult, logical: CaptureResult) = ReportedSettings(
        exposureTimeNs = physical.get(CaptureResult.SENSOR_EXPOSURE_TIME),
        iso = physical.get(CaptureResult.SENSOR_SENSITIVITY),
        afMode = logical.get(CaptureResult.CONTROL_AF_MODE),
        awbMode = logical.get(CaptureResult.CONTROL_AWB_MODE),
    )

    /** ADR-0009: the preview auto-exposure is the light meter; keep its latest exposure time and ISO. */
    private fun meter(physicalId: String) = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult) {
            // UNVERIFIED(G1): that the physical result carries the exposure of the lens actually streaming.
            val source = result.physicalCameraTotalResults[physicalId] ?: result
            val time = source.get(CaptureResult.SENSOR_EXPOSURE_TIME)
            val iso = source.get(CaptureResult.SENSOR_SENSITIVITY)
            if (time != null && iso != null) metered = Exposure(time, iso)
        }
    }

    private companion object {
        const val SESSION_CLOSE_TIMEOUT_MS = 1_000L
    }
}
