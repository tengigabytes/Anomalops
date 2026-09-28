// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.probe

import android.media.Image
import kotlin.math.sqrt

/**
 * A small luma grid used only to compare fields of view; the camera image itself is discarded immediately.
 * Grid size is fixed so frames of different resolutions can be compared directly.
 */
internal class Luma(val width: Int, val height: Int, val values: FloatArray) {

    /** 2x2 average pooling. */
    fun half(): Luma {
        val w = width / 2
        val h = height / 2
        val out = FloatArray(w * h) { i ->
            val x = (i % w) * 2
            val y = (i / w) * 2
            (at(x, y) + at(x + 1, y) + at(x, y + 1) + at(x + 1, y + 1)) / 4f
        }
        return Luma(w, h, out)
    }

    /** The central half-width, half-height region at native grid scale. */
    fun centre(): Luma {
        val w = width / 2
        val h = height / 2
        val x0 = width / 4
        val y0 = height / 4
        return Luma(w, h, FloatArray(w * h) { i -> at(x0 + i % w, y0 + i / w) })
    }

    private fun at(x: Int, y: Int) = values[y * width + x]

    companion object {
        const val GRID_W = 320
        const val GRID_H = 240

        /** Box-averages the Y plane of a YUV_420_888 image onto a GRID_W x GRID_H grid. */
        fun from(image: Image): Luma {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val row = plane.rowStride
            val step = plane.pixelStride
            val sums = FloatArray(GRID_W * GRID_H)
            val counts = IntArray(GRID_W * GRID_H)
            for (y in 0 until image.height step 2) {
                val gy = y * GRID_H / image.height
                for (x in 0 until image.width step 2) {
                    val g = gy * GRID_W + x * GRID_W / image.width
                    sums[g] += (buffer.get(y * row + x * step).toInt() and BYTE_MASK).toFloat()
                    counts[g]++
                }
            }
            return Luma(GRID_W, GRID_H, FloatArray(sums.size) { sums[it] / counts[it].coerceAtLeast(1) })
        }

        /** Normalised cross-correlation of two equally sized grids, in [-1, 1]. */
        fun ncc(a: Luma, b: Luma): Double {
            require(a.width == b.width && a.height == b.height)
            val meanA = a.values.average()
            val meanB = b.values.average()
            var num = 0.0
            var denA = 0.0
            var denB = 0.0
            for (i in a.values.indices) {
                val da = a.values[i] - meanA
                val db = b.values[i] - meanB
                num += da * db
                denA += da * da
                denB += db * db
            }
            return if (denA == 0.0 || denB == 0.0) 0.0 else num / sqrt(denA * denB)
        }

        private const val BYTE_MASK = 0xFF
    }
}
