// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.core.camera.experiment

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
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.util.Log
import io.github.tengigabytes.anomalops.core.camera.session.configure
import io.github.tengigabytes.anomalops.core.camera.session.openDevice
import java.util.concurrent.Executor

/**
 * One physical lens behind logical camera 0, as ADR-0013 proposes for tele macro: a preview sink and a full-size
 * RAW reader, both bound to the lens with `setPhysicalCameraId`, AF off and the focus distance set by hand.
 * Shared by the macro experiments (docs/test/macro-stacking-test-plan.md, T1 and T9). Writes no files.
 */
internal class RawRig(private val manager: CameraManager, private val handler: Handler) : AutoCloseable {
    private val executor = Executor { handler.post(it) }
    private val preview = ImageReader.newInstance(
        ComboStreams.PREVIEW.width,
        ComboStreams.PREVIEW.height,
        ImageFormat.PRIVATE,
        SINK_IMAGES,
        HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE,
    ).apply { setOnImageAvailableListener({ it.acquireLatestImage()?.close() }, handler) }

    /** What the lens reported for one frame; the physical result when there is one, else the logical one. */
    class Frame(
        val sensorNs: Long?,
        val diopters: Float?,
        val lensState: Int?,
        val afState: Int?,
        val activeId: String?,
    )

    class Lens(val id: String, val minDiopters: Float, val hyperfocalDiopters: Float, val calibration: String)

    fun lens(id: String): Lens {
        val ch = manager.getCameraCharacteristics(id)
        return Lens(
            id,
            ch[CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE] ?: 0f,
            ch[CameraCharacteristics.LENS_INFO_HYPERFOCAL_DISTANCE] ?: 0f,
            when (ch[CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION]) {
                CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_CALIBRATED -> "CALIBRATED"
                CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_APPROXIMATE -> "APPROXIMATE"
                else -> "UNCALIBRATED"
            },
        )
    }

    /** Opens logical camera 0 with [id]'s preview and RAW streams and runs [block]; the RAW reader holds [rawImages]. */
    suspend fun <T> withLens(id: String, rawImages: Int, block: suspend (Session) -> T): T {
        val size = manager.getCameraCharacteristics(id)[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP]!!
            .getOutputSizes(ImageFormat.RAW_SENSOR).maxBy { it.width * it.height }
        val raw = ImageReader.newInstance(size.width, size.height, ImageFormat.RAW_SENSOR, rawImages)
        val device = manager.openDevice(LOGICAL_BACK, handler) { Log.e(TAG, "camera lost: $it") }
        try {
            val outputs = listOf(preview.surface, raw.surface).map {
                OutputConfiguration(it).apply { setPhysicalCameraId(id) }
            }
            val session = device.configure(outputs, executor) {}
            return block(Session(id, device, session, raw)).also { session.close() }
        } finally {
            device.close()
            raw.close()
        }
    }

    inner class Session(
        val id: String,
        private val device: CameraDevice,
        val capture: CameraCaptureSession,
        val raw: ImageReader,
    ) {
        /**
         * AF off, focus at [diopters]; the RAW reader is a target only when [withRaw]. [aeLock] holds the exposure,
         * so a focus sweep compares frames of the same gain.
         */
        fun request(diopters: Float, withRaw: Boolean, aeLock: Boolean = false): CaptureRequest =
            device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE, setOf(id)).apply {
                addTarget(preview.surface)
                if (withRaw) addTarget(raw.surface)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                set(CaptureRequest.CONTROL_AE_LOCK, aeLock)
                // As CaptureRequestWriter does: a physical stream follows the physical key, not the logical one.
                set(CaptureRequest.LENS_FOCUS_DISTANCE, diopters)
                setPhysicalCameraKey(CaptureRequest.LENS_FOCUS_DISTANCE, diopters, id)
            }.build()

        /** AF AUTO on the preview; [trigger] starts one scan (T0). */
        fun autoFocus(trigger: Boolean): CaptureRequest =
            device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW, setOf(id)).apply {
                addTarget(preview.surface)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
                if (trigger) set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
            }.build()

        /** A callback that hands every completed frame of this lens to [onFrame]. */
        fun frames(onFrame: (Frame) -> Unit) = object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult) {
                val physical = result.physicalCameraTotalResults[id]
                fun <T> key(k: CaptureResult.Key<T>): T? = physical?.get(k) ?: result.get(k)
                onFrame(
                    Frame(
                        key(CaptureResult.SENSOR_TIMESTAMP),
                        key(CaptureResult.LENS_FOCUS_DISTANCE),
                        key(CaptureResult.LENS_STATE),
                        key(CaptureResult.CONTROL_AF_STATE),
                        result.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID),
                    ),
                )
            }
        }
    }

    override fun close() {
        preview.close()
    }

    companion object {
        const val TAG = "MacroExperiment"
        private const val LOGICAL_BACK = "0"
        private const val SINK_IMAGES = 4
        private const val PATCH = 1024
        private const val BAYER_STEP = 2

        /**
         * Relative sharpness of a RAW_SENSOR frame: variance of a Laplacian over one Bayer phase of the central
         * [PATCH] square. Only for comparing frames of the same lens and scene.
         */
        fun sharpness(image: Image): Double {
            val plane = image.planes[0]
            val buffer = plane.buffer.asShortBuffer()
            val stride = plane.rowStride / 2
            // Even offsets keep the same Bayer phase whatever the frame size.
            val x0 = (image.width - PATCH) / 2 and 1.inv()
            val y0 = (image.height - PATCH) / 2 and 1.inv()
            fun at(x: Int, y: Int) = buffer.get((y0 + y) * stride + x0 + x).toInt() and 0xFFFF
            var sum = 0.0
            var squares = 0.0
            var n = 0
            for (y in BAYER_STEP until PATCH - BAYER_STEP step BAYER_STEP) {
                for (x in BAYER_STEP until PATCH - BAYER_STEP step BAYER_STEP) {
                    val lap = 4 * at(x, y) - at(x - BAYER_STEP, y) - at(x + BAYER_STEP, y) -
                        at(x, y - BAYER_STEP) - at(x, y + BAYER_STEP)
                    sum += lap
                    squares += lap.toDouble() * lap
                    n++
                }
            }
            val mean = sum / n
            return squares / n - mean * mean
        }
    }
}
