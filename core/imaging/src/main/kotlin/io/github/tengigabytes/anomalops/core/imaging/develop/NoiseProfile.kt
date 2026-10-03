// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.core.imaging.develop

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

    private companion object {
        const val CHANNELS = 3
        const val STEP = 8
        val lumaWeights = floatArrayOf(0.2126f, 0.7152f, 0.0722f)
    }
}
