// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.core.imaging.develop

/**
 * Unsharp mask on the finished 8-bit picture: each pixel's luma minus its 5 x 5 binomial blur (1 4 6 4 1 each way,
 * σ = 1 pixel, edges clamped), times the amount, added to red, green and blue alike so the hue stays. Integers
 * only, so the GPU kernel gives the same codes bit for bit.
 *
 * Luma is 54 R + 183 G + 19 B (Rec. 709 in 256ths); the blur's weights add up to 256; the amount is held in 256ths.
 */
object Sharpen {
    const val MAX_AMOUNT = 4f
    private const val LUMA_R = 54
    private const val LUMA_G = 183
    private const val LUMA_B = 19
    private const val RADIUS = 2
    private const val SCALE_BITS = 8
    private const val ROUND = 1 shl 15
    private const val RESULT_BITS = 16
    private const val RED_SHIFT = 16
    private const val GREEN_SHIFT = 8
    private const val CODE = 0xFF
    private const val ALPHA = 0xFF shl 24
    private const val HALF = 0.5f
    private val kernel = intArrayOf(1, 4, 6, 4, 1)

    /** The amount as the integer the arithmetic uses (256ths); 0 means the picture passes unchanged. */
    fun quantise(amount: Float): Int = (amount * (1 shl SCALE_BITS) + HALF).toInt()

    /** [argb] ([width] x [height], packed as `Render.toArgb` makes it) sharpened by [amount]; alpha is kept. */
    fun apply(argb: IntArray, width: Int, height: Int, amount: Float): IntArray {
        require(argb.size == width * height) { "${argb.size} pixels for ${width}x$height" }
        require(amount in 0f..MAX_AMOUNT) { "amount $amount" }
        val q = quantise(amount)
        if (q == 0) return argb.copyOf()
        val luma = IntArray(argb.size) { luma(argb[it]) }
        val rows = IntArray(argb.size)
        for (y in 0 until height) {
            for (x in 0 until width) {
                var sum = 0
                for (d in -RADIUS..RADIUS) {
                    sum += kernel[d + RADIUS] * luma[y * width + (x + d).coerceIn(0, width - 1)]
                }
                rows[y * width + x] = sum
            }
        }
        val out = IntArray(argb.size)
        for (y in 0 until height) {
            for (x in 0 until width) {
                var blur = 0
                for (d in -RADIUS..RADIUS) {
                    blur += kernel[d + RADIUS] * rows[(y + d).coerceIn(0, height - 1) * width + x]
                }
                val i = y * width + x
                out[i] = shifted(argb[i], delta(luma[i], blur, q))
            }
        }
        return out
    }

    private fun luma(argb: Int): Int =
        LUMA_R * (argb shr RED_SHIFT and CODE) + LUMA_G * (argb shr GREEN_SHIFT and CODE) + LUMA_B * (argb and CODE)

    /** Codes to add: `q / 256` times (luma − blur), rounded; both shifts round toward −∞, as GLSL's `>>` does. */
    private fun delta(luma: Int, blur: Int, q: Int): Int =
        (q * ((luma shl SCALE_BITS) - blur shr SCALE_BITS) + ROUND) shr RESULT_BITS

    private fun shifted(argb: Int, delta: Int): Int {
        val r = ((argb shr RED_SHIFT and CODE) + delta).coerceIn(0, CODE)
        val g = ((argb shr GREEN_SHIFT and CODE) + delta).coerceIn(0, CODE)
        val b = ((argb and CODE) + delta).coerceIn(0, CODE)
        return (argb and ALPHA) or (r shl RED_SHIFT) or (g shl GREEN_SHIFT) or b
    }
}
