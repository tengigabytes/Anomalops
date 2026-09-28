// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.core.camera.request

import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.RggbChannelVector
import android.util.Rational

/**
 * Maps a [RequestSpec] onto Camera2 keys. The builder must come from
 * `createCaptureRequest(template, setOf(spec.physicalId))` so the per-lens keys can be set.
 *
 * Keys listed in the logical camera's `availablePhysicalCameraRequestKeys` (exposure time, sensitivity, focus
 * distance, colour correction gains and transform; docs/test/g0-blazer.md) are written both on the logical
 * request and for the physical camera; mode keys exist only on the logical request.
 */
internal object CaptureRequestWriter {

    // Order of ColorSpec.Manual.gains.
    private const val R = 0
    private const val G_EVEN = 1
    private const val G_ODD = 2
    private const val B = 3

    fun write(builder: CaptureRequest.Builder, spec: RequestSpec) {
        // FR-81: every request, preview or still, keeps the flash off.
        builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
        builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
        writeExposure(builder, spec)
        writeFocus(builder, spec)
        writeColor(builder, spec)
    }

    private fun writeExposure(builder: CaptureRequest.Builder, spec: RequestSpec) {
        when (spec.ae) {
            AeMode.ON -> builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)

            AeMode.OFF -> {
                val still = checkNotNull(spec.exposure)
                builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                builder.set(CaptureRequest.SENSOR_FRAME_DURATION, still.frameDurationNs)
                builder.both(spec, CaptureRequest.SENSOR_EXPOSURE_TIME, still.exposure.timeNs)
                builder.both(spec, CaptureRequest.SENSOR_SENSITIVITY, still.exposure.iso)
            }
        }
    }

    private fun writeFocus(builder: CaptureRequest.Builder, spec: RequestSpec) {
        when (val focus = spec.focus) {
            FocusSpec.ContinuousPicture ->
                builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)

            FocusSpec.Auto -> builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)

            is FocusSpec.Fixed -> {
                builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                builder.both(spec, CaptureRequest.LENS_FOCUS_DISTANCE, focus.diopters.toFloat())
            }
        }
    }

    private fun writeColor(builder: CaptureRequest.Builder, spec: RequestSpec) {
        when (val color = spec.color) {
            ColorSpec.AutoApproximate -> builder.set(
                CaptureRequest.CONTROL_AWB_MODE,
                CaptureRequest.CONTROL_AWB_MODE_AUTO,
            )

            is ColorSpec.Manual -> {
                builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF)
                builder.set(
                    CaptureRequest.COLOR_CORRECTION_MODE,
                    CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX,
                )
                val gains = color.gains.map { it.toFloat() }
                val vector = RggbChannelVector(gains[R], gains[G_EVEN], gains[G_ODD], gains[B])
                builder.both(spec, CaptureRequest.COLOR_CORRECTION_GAINS, vector)
                builder.both(spec, CaptureRequest.COLOR_CORRECTION_TRANSFORM, transform(color.transform))
            }
        }
    }

    private fun transform(values: List<Double>): ColorSpaceTransform {
        val elements = values.map { Rational(FixedPoint.numerator(it), FixedPoint.DENOMINATOR) }
        return ColorSpaceTransform(elements.toTypedArray())
    }

    // Exposure time and ISO: the physical result matched the request on blazer (docs/test/m1-camera-session.md).
    // UNVERIFIED(G1): the same for the colour keys, untested until a calibration entry exists (FR-91).
    private fun <T : Any> CaptureRequest.Builder.both(spec: RequestSpec, key: CaptureRequest.Key<T>, value: T) {
        set(key, value)
        setPhysicalCameraKey(key, value, spec.physicalId)
    }
}
