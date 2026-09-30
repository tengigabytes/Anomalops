// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.core.imaging.align

import kotlin.math.floor

/** One channel of linear values, row-major, [width] x [height]. The unit of everything alignment reads. */
class Plane(val width: Int, val height: Int, val data: FloatArray = FloatArray(width * height)) {
    init {
        require(width > 0 && height > 0) { "empty plane ${width}x$height" }
        require(data.size == width * height) { "data size ${data.size} for ${width}x$height" }
    }

    operator fun get(x: Int, y: Int): Float = data[y * width + x]

    operator fun set(x: Int, y: Int, value: Float) {
        data[y * width + x] = value
    }

    /** Bilinear sample at a fractional position; NaN outside the pixel centres 0..width-1, 0..height-1. */
    fun sample(x: Float, y: Float): Float {
        if (x !in 0f..(width - 1f) || y !in 0f..(height - 1f)) return Float.NaN
        val x0 = floor(x).toInt().coerceAtMost(width - 2).coerceAtLeast(0)
        val y0 = floor(y).toInt().coerceAtMost(height - 2).coerceAtLeast(0)
        val fx = x - x0
        val fy = y - y0
        val x1 = (x0 + 1).coerceAtMost(width - 1)
        val y1 = (y0 + 1).coerceAtMost(height - 1)
        val top = this[x0, y0] + (this[x1, y0] - this[x0, y0]) * fx
        val bottom = this[x0, y1] + (this[x1, y1] - this[x0, y1]) * fx
        return top + (bottom - top) * fy
    }

    /** Half size, each pixel the mean of a 2 x 2 block; an odd last row or column is dropped. */
    fun half(): Plane {
        val w = width / 2
        val h = height / 2
        require(w > 0 && h > 0) { "cannot halve ${width}x$height" }
        val out = Plane(w, h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                out[x, y] = (
                    this[2 * x, 2 * y] + this[2 * x + 1, 2 * y] + this[2 * x, 2 * y + 1] +
                        this[2 * x + 1, 2 * y + 1]
                    ) * QUARTER
            }
        }
        return out
    }

    companion object {
        private const val QUARTER = 0.25f
        private const val U16 = 0xFFFF
        private const val BAYER_CELL = 4

        /**
         * A luminance plane from a Bayer RAW_SENSOR buffer: each 2 x 2 cell (one of each colour) summed, black level
         * removed. Half the RAW size in each direction; the colour filter layout does not matter for alignment.
         * [rowStride] is in samples.
         */
        fun fromBayer(raw: ShortArray, width: Int, height: Int, rowStride: Int, blackLevel: Int): Plane {
            val out = Plane(width / 2, height / 2)
            for (y in 0 until out.height) {
                val row0 = 2 * y * rowStride
                val row1 = row0 + rowStride
                for (x in 0 until out.width) {
                    val i = 2 * x
                    val sum = (raw[row0 + i].toInt() and U16) + (raw[row0 + i + 1].toInt() and U16) +
                        (raw[row1 + i].toInt() and U16) + (raw[row1 + i + 1].toInt() and U16)
                    out[x, y] = (sum - BAYER_CELL * blackLevel).coerceAtLeast(0).toFloat()
                }
            }
            return out
        }
    }
}
