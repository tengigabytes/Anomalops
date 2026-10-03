// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.core.imaging.develop

import kotlin.math.abs
import kotlin.math.exp

/**
 * Colour noise removed from the finished 8-bit picture: each pixel's colour (its channels minus its luma) becomes
 * the weighted mean of its neighbours' colours, counting most the neighbours that are near and of similar luma, so
 * colour does not bleed across an edge. Luma is left as it is (up to rounding), so detail and luma noise stay.
 *
 * Each pass reads a 5 x 5 grid of neighbours, [spacing] pixels apart: 2, 4 and 8 for the three passes, reaching 16
 * pixels. Integers only, so the GPU kernel gives the same codes bit for bit: luma is 54 R + 183 G + 19 B (256ths),
 * colour is held in 16ths of a code with an offset that keeps the sums positive, the spatial weights are
 * 16 exp(-d² / 4) rounded and the luma weights come from [rangeWeights].
 *
 * Why: on real bursts of 2026-10-03 the colour noise of a 5-frame merge was about 20 times the stock camera's
 * (4.3 against 0.2 codes in a flat patch) while its luma noise was no worse; three passes brought it to 0.4.
 */
object ChromaDenoise {
    const val MAX_PASSES = 3
    private const val LUMA_R = 54
    private const val LUMA_G = 183
    private const val LUMA_B = 19
    private const val RADIUS = 2
    private const val CHANNELS = 3
    private const val CODE = 0xFF
    private const val CODE_BITS = 8
    private const val ALPHA = 0xFF shl 24
    private const val RED_SHIFT = 16
    private const val GREEN_SHIFT = 8
    private const val CHROMA_BITS = 4
    private const val OFFSET = 4096
    private const val ROUND = 128
    private const val RANGE_PEAK = 255
    private const val RANGE_SIGMA = 10.2
    private const val HALF = 0.5

    /** 16 exp(-(dx² + dy²) / 4), row by row. */
    val spatialWeights = intArrayOf(
        2, 5, 6, 5, 2,
        5, 10, 12, 10, 5,
        6, 12, 16, 12, 6,
        5, 10, 12, 10, 5,
        2, 5, 6, 5, 2,
    )

    /** The weight of a neighbour whose luma differs by the index, in codes: 255 exp(-d² / (2 · 10.2²)). */
    val rangeWeights = IntArray(CODE + 1) {
        (RANGE_PEAK * exp(-(it * it) / (2 * RANGE_SIGMA * RANGE_SIGMA)) + HALF).toInt()
    }

    /** How far apart pass [pass] (from 0) takes its neighbours. */
    fun spacing(pass: Int): Int = 2 shl pass

    /** [argb] ([width] x [height], packed as `Render.toArgb` makes it) after [passes] passes; alpha is kept. */
    fun apply(argb: IntArray, width: Int, height: Int, passes: Int): IntArray {
        require(argb.size == width * height) { "${argb.size} pixels for ${width}x$height" }
        require(passes in 0..MAX_PASSES) { "passes $passes" }
        var current = argb.copyOf()
        for (pass in 0 until passes) current = pass(current, width, height, spacing(pass))
        return current
    }

    private fun pass(src: IntArray, width: Int, height: Int, spacing: Int): IntArray {
        val luma = IntArray(src.size) { luma(src[it]) }
        val out = IntArray(src.size)
        val sums = IntArray(CHANNELS)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val i = y * width + x
                sums.fill(0)
                var total = 0
                for (dy in -RADIUS..RADIUS) {
                    val row = (y + dy * spacing).coerceIn(0, height - 1) * width
                    for (dx in -RADIUS..RADIUS) {
                        val j = row + (x + dx * spacing).coerceIn(0, width - 1)
                        val spatial = spatialWeights[(dy + RADIUS) * (2 * RADIUS + 1) + dx + RADIUS]
                        val w = spatial * rangeWeights[abs(luma[j] - luma[i]) shr CODE_BITS]
                        sums[0] += w * chroma(src[j] shr RED_SHIFT and CODE, luma[j])
                        sums[1] += w * chroma(src[j] shr GREEN_SHIFT and CODE, luma[j])
                        sums[2] += w * chroma(src[j] and CODE, luma[j])
                        total += w
                    }
                }
                out[i] = (src[i] and ALPHA) or
                    (code(luma[i], sums[0], total) shl RED_SHIFT) or
                    (code(luma[i], sums[1], total) shl GREEN_SHIFT) or
                    code(luma[i], sums[2], total)
            }
        }
        return out
    }

    private fun luma(argb: Int): Int =
        LUMA_R * (argb shr RED_SHIFT and CODE) + LUMA_G * (argb shr GREEN_SHIFT and CODE) + LUMA_B * (argb and CODE)

    /** A channel's colour in 16ths of a code, plus [OFFSET] so it is never negative. */
    private fun chroma(code: Int, luma: Int): Int = ((code shl CODE_BITS) - luma shr CHROMA_BITS) + OFFSET

    /** The channel's code again from the pixel's own luma and the weighted mean colour. */
    private fun code(luma: Int, sum: Int, total: Int): Int {
        val chroma = (sum + total / 2) / total - OFFSET
        return (luma + (chroma shl CHROMA_BITS) + ROUND shr CODE_BITS).coerceIn(0, CODE)
    }
}
