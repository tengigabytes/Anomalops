// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.core.imaging.develop

import kotlin.math.pow

/**
 * The last step of ADR-0015's pipeline (tone-map, output). All values are proposed: no look has been decided, and
 * these only aim to be neutral (midtones untouched) and to keep highlights from clipping to a hue.
 *
 * - [exposure]: linear gain after the colour matrix.
 * - [highlightKnee]: camera-RGB level (RAW white = 1) from which a pixel is blended toward neutral, fully neutral
 *   when any channel reaches RAW white; a clipped channel's true value is unknown, and white balance gains would
 *   otherwise turn clipped highlights pink or cyan.
 * - [shoulder], [white]: output levels up to [shoulder] pass unchanged; above it the curve rolls off smoothly and
 *   reaches 1 at scene level [white]. White balance gains push saturated colours above 1, so [white] > 1.
 * - [sharpen]: [Sharpen]'s amount on the finished picture; 0 leaves it as rendered.
 */
data class RenderOptions(
    val exposure: Float = 1f,
    val highlightKnee: Float = 0.9f,
    val shoulder: Float = 0.9f,
    val white: Float = 2f,
    val sharpen: Float = 0f,
) {
    init {
        require(exposure > 0f && highlightKnee in 0f..<1f) { "exposure $exposure, knee $highlightKnee" }
        require(shoulder in 0f..<1f && white > 1f) { "shoulder $shoulder, white $white" }
        require(sharpen in 0f..Sharpen.MAX_AMOUNT) { "sharpen $sharpen" }
    }
}

/**
 * Camera RGB, linear (as [Demosaic] makes it, and as an aligned merge of such frames stays), to 8-bit sRGB packed
 * as ARGB (`Bitmap.setPixels`, `BufferedImage.TYPE_INT_ARGB`). In order, per pixel: how close to clipping the RAW
 * was, lens shading gains ([ShadingMap], optional), white-balance gains, colour matrix (camera to linear sRGB,
 * ADR-0002), exposure, highlight blend toward neutral, tone curve on the brightest channel (keeps the hue), sRGB
 * encoding, and [Sharpen] over the whole picture when asked. Shading comes after the clipping check because it
 * lifts clipped corners above RAW white.
 */
object Render {
    private const val CHANNELS = 3
    private const val LUT_SIZE = 1 shl 16
    private const val OPAQUE = 0xFF shl 24
    private const val RED_SHIFT = 16
    private const val GREEN_SHIFT = 8
    private const val CODE_MAX = 255
    private const val HALF = 0.5f
    private const val SMOOTH_A = 3f
    private const val SRGB_LINEAR_LIMIT = 0.0031308f
    private const val SRGB_SLOPE = 12.92f
    private const val SRGB_SCALE = 1.055f
    private const val SRGB_OFFSET = 0.055f
    private const val SRGB_GAMMA = 1 / 2.4f

    private val lut = ByteArray(LUT_SIZE) { i -> (srgb(i / (LUT_SIZE - 1f)) * CODE_MAX + HALF).toInt().toByte() }

    /**
     * [rgb] in camera colour from a [rawWidth] x [rawHeight] frame (full size or [Demosaic.halfSize]; only the
     * shading map needs the RAW size). [gains] red, green, blue; [matrix] 3 x 3, row by row.
     */
    fun toArgb(
        rgb: Rgb,
        gains: FloatArray,
        matrix: FloatArray,
        options: RenderOptions = RenderOptions(),
        shading: ShadingMap? = null,
        rawWidth: Int = rgb.width,
        rawHeight: Int = rgb.height,
    ): IntArray {
        require(gains.size == CHANNELS && matrix.size == CHANNELS * CHANNELS) { "gains, matrix" }
        val planes = rgb.channels()
        val out = IntArray(rgb.width * rgb.height)
        val v = FloatArray(CHANNELS)
        val o = FloatArray(CHANNELS)
        val shade = FloatArray(CHANNELS) { 1f }
        val scaleX = rawWidth.toFloat() / rgb.width
        val scaleY = rawHeight.toFloat() / rgb.height
        for (y in 0 until rgb.height) {
            for (x in 0 until rgb.width) {
                val i = y * rgb.width + x
                for (c in 0 until CHANNELS) v[c] = planes[c].data[i]
                shading?.gainsAt((x + HALF) * scaleX - HALF, (y + HALF) * scaleY - HALF, rawWidth, rawHeight, shade)
                pixel(v, shade, gains, matrix, options, o)
                out[i] = OPAQUE or (encode(o[0]) shl RED_SHIFT) or (encode(o[1]) shl GREEN_SHIFT) or encode(o[2])
            }
        }
        return if (Sharpen.quantise(options.sharpen) > 0) {
            Sharpen.apply(out, rgb.width, rgb.height, options.sharpen)
        } else {
            out
        }
    }

    /** One pixel, camera RGB [v] to display-linear sRGB in [o], 0..1. */
    internal fun pixel(
        v: FloatArray,
        shade: FloatArray,
        gains: FloatArray,
        matrix: FloatArray,
        opt: RenderOptions,
        o: FloatArray,
    ) {
        val t = smoothstep((maxOf(v[0], v[1], v[2]) - opt.highlightKnee) / (1f - opt.highlightKnee))
        for (row in 0 until CHANNELS) {
            var sum = 0f
            for (c in 0 until CHANNELS) sum += matrix[row * CHANNELS + c] * v[c] * shade[c] * gains[c]
            o[row] = sum * opt.exposure
        }
        if (t > 0f) {
            val m = maxOf(o[0], o[1], o[2])
            for (c in 0 until CHANNELS) o[c] += t * (m - o[c])
        }
        for (c in 0 until CHANNELS) o[c] = o[c].coerceAtLeast(0f)
        val peak = maxOf(o[0], o[1], o[2])
        if (peak > opt.shoulder) {
            val scale = tone(peak, opt.shoulder, opt.white) / peak
            for (c in 0 until CHANNELS) o[c] *= scale
        }
    }

    /**
     * Identity up to [shoulder], then `s + (1 - s) f(a)` with `a = (x - s) / (1 - s)` and the extended Reinhard
     * `f(a) = a (1 + a / A^2) / (1 + a)`, `A` being [white]'s `a`: slope 1 at the shoulder, 1 at [white], 1 above.
     */
    fun tone(x: Float, shoulder: Float, white: Float): Float = when {
        x <= shoulder -> x

        x >= white -> 1f

        else -> {
            val a = (x - shoulder) / (1f - shoulder)
            val top = (white - shoulder) / (1f - shoulder)
            shoulder + (1f - shoulder) * a * (1f + a / (top * top)) / (1f + a)
        }
    }

    /** Display-linear 0..1 to an 8-bit sRGB code, through a 65 536-entry table. */
    fun encode(linear: Float): Int {
        val index = (linear * (LUT_SIZE - 1) + HALF).toInt().coerceIn(0, LUT_SIZE - 1)
        return lut[index].toInt() and CODE_MAX
    }

    /** The sRGB transfer function (IEC 61966-2-1), linear 0..1 to encoded 0..1. */
    fun srgb(linear: Float): Float =
        if (linear <= SRGB_LINEAR_LIMIT) SRGB_SLOPE * linear else SRGB_SCALE * linear.pow(SRGB_GAMMA) - SRGB_OFFSET

    private fun smoothstep(x: Float): Float {
        val t = x.coerceIn(0f, 1f)
        return t * t * (SMOOTH_A - 2 * t)
    }
}
