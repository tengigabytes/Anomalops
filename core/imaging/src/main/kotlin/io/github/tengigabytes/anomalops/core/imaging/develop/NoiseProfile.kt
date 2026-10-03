// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.core.imaging.develop

import kotlin.math.pow
import kotlin.math.sqrt

/**
 * The sensor's noise model for one capture (`SENSOR_NOISE_PROFILE`, a DNG's NoiseProfile): a photosite at level x
 * (black 0, white 1) has noise variance `scale · x + offset`. One pair per colour: red, green, blue.
 *
 * Checked on the Pixel 10 Pro's main lens, 2026-10-03, against the difference of two tripod frames: the luma
 * noise it predicts was within 4 % of the measured one at ISO 155 and at ISO 333.
 */
class NoiseProfile(private val scale: FloatArray, private val offset: FloatArray) {
    init {
        require(scale.size == CHANNELS && offset.size == CHANNELS) { "one pair per colour" }
    }

    /**
     * The noise standard deviation of `Rgb.luma` of `Demosaic.halfSize` of [raw], as `LowLightMerge` wants it:
     * the root of the mean variance over every [STEP]th cell each way, each cell at its own level (the level at
     * the frame's median would underestimate a scene with bright parts). Green is the mean of two photosites, so
     * it has half a photosite's variance.
     */
    fun lumaSigma(raw: RawFrame): Float {
        val v = FloatArray(CHANNELS)
        var sum = 0.0
        var n = 0
        for (y in 0 until raw.height - 1 step 2 * STEP) {
            for (x in 0 until raw.width - 1 step 2 * STEP) {
                raw.cell(x, y, v)
                for (c in 0 until CHANNELS) {
                    val variance = (scale[c] * v[c] + offset[c]).coerceAtLeast(0f)
                    sum += lumaWeights[c] * lumaWeights[c] * variance / if (c == CfaLayout.GREEN) 2 else 1
                }
                n++
            }
        }
        return sqrt(sum / n).toFloat()
    }

    /**
     * The colour noise [Render] leaves in one frame of [raw] at [exposure], before its whole-picture steps: the
     * standard deviation, in 8-bit codes, of red minus luma (54 R + 183 G + 19 B, as `ChromaDenoise` has it),
     * as the median over every [STEP]th cell each way. Each cell's photosite variances go through the shading and
     * white-balance gains, the colour matrix and the sRGB curve's slope at the cell's own level; the highlight
     * blend and the tone curve's shoulder are left out. A merge of n frames has about 1 / √n of it.
     */
    fun chromaSigma(
        raw: RawFrame,
        gains: FloatArray,
        matrix: FloatArray,
        shading: ShadingMap?,
        exposure: Float,
    ): Float {
        val columns = (raw.width / 2 + STEP - 1) / STEP
        val rows = (raw.height / 2 + STEP - 1) / STEP
        val values = FloatArray(columns * rows)
        val shade = FloatArray(CHANNELS) { 1f }
        val v = FloatArray(CHANNELS)
        val scaled = FloatArray(CHANNELS)
        for (row in 0 until rows) {
            for (column in 0 until columns) {
                val x = 2 * column * STEP
                val y = 2 * row * STEP
                raw.cell(x, y, v)
                shading?.gainsAt(x + HALF, y + HALF, raw.width, raw.height, shade)
                for (c in 0 until CHANNELS) scaled[c] = exposure * gains[c] * shade[c]
                values[row * columns + column] = cellChromaSigma(v, scaled, matrix)
            }
        }
        values.sort()
        return values[values.size / 2]
    }

    /** One cell of [chromaSigma]: camera levels [v], each channel's total gain [scaled] before the [matrix]. */
    private fun cellChromaSigma(v: FloatArray, scaled: FloatArray, matrix: FloatArray): Float {
        // How much red minus luma moves, in codes, per unit of each rendered linear channel.
        val reach = FloatArray(CHANNELS) { r ->
            var linear = 0f
            for (c in 0 until CHANNELS) linear += matrix[r * CHANNELS + c] * scaled[c] * v[c]
            ((if (r == 0) 1f else 0f) - codeWeights[r]) * CODE_MAX * srgbSlope(linear)
        }
        var variance = 0f
        for (c in 0 until CHANNELS) {
            var a = 0f
            for (r in 0 until CHANNELS) a += reach[r] * matrix[r * CHANNELS + c]
            a *= scaled[c]
            variance += a * a * (scale[c] * v[c] + offset[c]).coerceAtLeast(0f) / if (c == CfaLayout.GREEN) 2 else 1
        }
        return sqrt(variance)
    }

    /** The slope of the sRGB encoding at [linear], taken inside its range. */
    private fun srgbSlope(linear: Float): Float {
        val x = linear.coerceIn(0f, 1f)
        return if (x <= SRGB_TOE) SRGB_TOE_SLOPE else SRGB_SCALE / SRGB_GAMMA * x.pow(1 / SRGB_GAMMA - 1)
    }

    private companion object {
        const val CHANNELS = 3
        const val STEP = 8
        const val HALF = 0.5f
        const val CODE_MAX = 255f
        const val SRGB_TOE = 0.0031308f
        const val SRGB_TOE_SLOPE = 12.92f
        const val SRGB_SCALE = 1.055f
        const val SRGB_GAMMA = 2.4f
        val lumaWeights = floatArrayOf(0.2126f, 0.7152f, 0.0722f)
        val codeWeights = floatArrayOf(54 / 256f, 183 / 256f, 19 / 256f)
    }
}
