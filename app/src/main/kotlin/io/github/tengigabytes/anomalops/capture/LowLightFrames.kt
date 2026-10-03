// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.capture

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import io.github.tengigabytes.anomalops.core.camera.session.MultiFrameCapture
import io.github.tengigabytes.anomalops.core.camera.session.RawSamples
import io.github.tengigabytes.anomalops.core.gpu.LowLightBurst
import io.github.tengigabytes.anomalops.core.imaging.develop.CfaLayout
import io.github.tengigabytes.anomalops.core.imaging.develop.NoiseProfile
import io.github.tengigabytes.anomalops.core.imaging.develop.RawFrame
import io.github.tengigabytes.anomalops.core.imaging.develop.ShadingMap

/**
 * A camera burst as the FR-17 pipeline takes it: Camera2's results and characteristics read into `:core:imaging`
 * types (`:core:camera` and `:core:imaging` do not know each other, ADR-0007). The colour comes from what each
 * result reports was applied (`COLOR_CORRECTION_GAINS` and `_TRANSFORM`), so a merged picture gets the white
 * balance and matrix of the request (ADR-0002), calibrated or automatic.
 */
internal object LowLightFrames {
    private const val CELL = 4
    private const val COLOURS = 3
    private const val MATRIX = 3
    private const val PERCENT = 100f

    /** Null when the burst cannot be merged: fewer than two frames, or a lens without a Bayer layout or colour. */
    fun burst(capture: MultiFrameCapture): LowLightBurst? {
        val layout = capture.characteristics.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT)
            ?.let { CfaLayout.fromCamera2(it) }
        val first = capture.frames.takeIf { it.size >= 2 }?.first()?.result
        val gains = first?.let { gains(it) }
        val matrix = first?.let { matrix(it) }
        return if (layout != null && gains != null && matrix != null) burst(capture, layout, gains, matrix) else null
    }

    private fun burst(
        capture: MultiFrameCapture,
        layout: CfaLayout,
        gains: FloatArray,
        matrix: FloatArray,
    ): LowLightBurst {
        val results = capture.frames.map { it.result }
        val first = results.first()
        return LowLightBurst(
            frames = capture.frames.map { frame(it, layout, capture.characteristics) },
            gains = gains,
            matrix = matrix,
            // The still's own request does not ask for the shading map; the burst frames after it do.
            shading = results.firstNotNullOfOrNull { shading(it) },
            noise = noise(first, layout),
            // With the still's manual exposure the result reports the asked ISO and a boost of 100, and the RAW
            // already holds the gain above the highest analog ISO: asked 1516 on the macro lens, the merged
            // picture at gain 1 was as bright as the camera's still (2026-10-03). Under auto-exposure the boost
            // carries that part instead.
            postRawGain = first.get(CaptureResult.CONTROL_POST_RAW_SENSITIVITY_BOOST)?.let { it / PERCENT },
        )
    }

    private fun frame(raw: RawSamples, layout: CfaLayout, characteristics: CameraCharacteristics): RawFrame {
        val pattern = characteristics.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)
        // Both are listed in the order of the CFA's cell, row by row; the per-frame values win when reported.
        val black = raw.result.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL)
            ?: FloatArray(CELL) { pattern?.getOffsetForIndex(it % 2, it / 2)?.toFloat() ?: 0f }
        val white = raw.result.get(CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL)
            ?: characteristics.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL)
        return RawFrame(raw.samples, raw.width, raw.height, raw.rowStride, layout, black, checkNotNull(white).toFloat())
    }

    /** Red, green, blue with green 1, as `Render` wants them. */
    private fun gains(result: CaptureResult): FloatArray? {
        val g = result.get(CaptureResult.COLOR_CORRECTION_GAINS) ?: return null
        val green = (g.greenEven + g.greenOdd) / 2
        return floatArrayOf(g.red / green, 1f, g.blue / green)
    }

    private fun matrix(result: CaptureResult): FloatArray? {
        val t = result.get(CaptureResult.COLOR_CORRECTION_TRANSFORM) ?: return null
        return FloatArray(MATRIX * MATRIX) { t.getElement(it % MATRIX, it / MATRIX).toFloat() }
    }

    private fun shading(result: CaptureResult): ShadingMap? {
        val map = result.get(CaptureResult.STATISTICS_LENS_SHADING_CORRECTION_MAP) ?: return null
        val gains = FloatArray(map.gainFactorCount).also { map.copyGainFactors(it, 0) }
        return ShadingMap(map.columnCount, map.rowCount, gains)
    }

    /** `SENSOR_NOISE_PROFILE` lists one pair per CFA cell position; the two greens are averaged. */
    private fun noise(result: CaptureResult, layout: CfaLayout): NoiseProfile? {
        val pairs = result.get(CaptureResult.SENSOR_NOISE_PROFILE)?.takeIf { it.size == CELL } ?: return null
        val scale = FloatArray(COLOURS)
        val offset = FloatArray(COLOURS)
        for (k in 0 until CELL) {
            val colour = layout.colourAt(k % 2, k / 2)
            val share = if (colour == CfaLayout.GREEN) 2 else 1
            scale[colour] += pairs[k].first.toFloat() / share
            offset[colour] += pairs[k].second.toFloat() / share
        }
        return NoiseProfile(scale, offset)
    }
}
