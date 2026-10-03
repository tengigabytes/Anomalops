// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.core.imaging.develop

/**
 * Colour strength on the finished 8-bit picture: every channel moves away from the pixel's luma by the factor, so
 * luma stays (up to rounding and clipping) and grey stays grey. Integers only, so the GPU kernel gives the same
 * codes bit for bit: luma is 54 R + 183 G + 19 B (256ths) and the factor is held in 256ths.
 *
 * Why: measured on 8 x 8 block means (so noise does not count), three merged pictures of 2026-10-03 were 31 to
 * 38 % less saturated than the camera's own stills of the same scenes, with about the same luma contrast.
 */
object Saturation {
    const val MAX_FACTOR = 2f
    private const val LUMA_R = 54
    private const val LUMA_G = 183
    private const val LUMA_B = 19
    private const val SCALE_BITS = 8
    private const val UNITY = 1 shl SCALE_BITS
    private const val ROUND = 1 shl 15
    private const val RESULT_BITS = 16
    private const val RED_SHIFT = 16
    private const val GREEN_SHIFT = 8
    private const val CODE = 0xFF
    private const val ALPHA = 0xFF shl 24
    private const val HALF = 0.5f

    /** The factor as the integer the arithmetic uses (256ths); 256 means the picture passes unchanged. */
    fun quantise(factor: Float): Int = (factor * UNITY + HALF).toInt()

    /** Whether [factor] changes anything. */
    fun changes(factor: Float): Boolean = quantise(factor) != UNITY

    /** [argb] (packed as `Render.toArgb` makes it) with its colour [factor] times as far from grey; alpha is kept. */
    fun apply(argb: IntArray, factor: Float): IntArray {
        require(factor in 0f..MAX_FACTOR) { "factor $factor" }
        val q = quantise(factor)
        if (q == UNITY) return argb.copyOf()
        return IntArray(argb.size) {
            val p = argb[it]
            val r = p shr RED_SHIFT and CODE
            val g = p shr GREEN_SHIFT and CODE
            val b = p and CODE
            val luma = LUMA_R * r + LUMA_G * g + LUMA_B * b
            (p and ALPHA) or (code(r, luma, q) shl RED_SHIFT) or (code(g, luma, q) shl GREEN_SHIFT) or code(b, luma, q)
        }
    }

    /** luma + q / 256 times (code − luma), rounded; the shift rounds toward −∞, as GLSL's `>>` does. */
    private fun code(code: Int, luma: Int, q: Int): Int =
        ((luma shl SCALE_BITS) + q * ((code shl SCALE_BITS) - luma) + ROUND shr RESULT_BITS).coerceIn(0, CODE)
}
