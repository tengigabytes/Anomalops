// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.core.camera.experiment

import android.media.Image
import kotlin.math.sqrt

/**
 * Measurements on a RAW_SENSOR frame of a ruler slanting away from the camera (T7, T10, T9 recheck of
 * docs/test/macro-stacking-test-plan.md): the ruler runs along the image's vertical axis, near at one end and far
 * at the other, so a horizontal band of the image is one distance. All on one Bayer phase of the central columns.
 */
internal object RulerProfile {
    private const val BAYER_STEP = 2
    private const val U16 = 0xFFFF

    /** Sharpness (variance of a Laplacian) of each of [count] horizontal bands, top to bottom. */
    fun bands(image: Image, count: Int, columnFraction: Float = 1f / 3): DoubleArray {
        val view = View(image, columnFraction)
        val rows = (image.height - 2 * BAYER_STEP) / BAYER_STEP
        return DoubleArray(count) { band ->
            val y0 = BAYER_STEP + band * rows / count * BAYER_STEP
            val y1 = BAYER_STEP + (band + 1) * rows / count * BAYER_STEP
            var sum = 0.0
            var squares = 0.0
            var n = 0
            for (y in y0 until y1 step BAYER_STEP) {
                for (x in view.x0 until view.x1 step BAYER_STEP) {
                    val lap = view.laplacian(x, y)
                    sum += lap
                    squares += lap * lap
                    n++
                }
            }
            if (n == 0) 0.0 else squares / n - (sum / n) * (sum / n)
        }
    }

    /** Mean of each row (one Bayer phase) over the central columns, for the magnification between two frames. */
    fun rows(image: Image, columnFraction: Float = 1f / 3): FloatArray {
        val view = View(image, columnFraction)
        return FloatArray(image.height / BAYER_STEP) { r ->
            var sum = 0.0
            var n = 0
            for (x in view.x0 until view.x1 step BAYER_STEP) {
                sum += view.at(x, r * BAYER_STEP)
                n++
            }
            (sum / n).toFloat()
        }
    }

    /** The band with the highest sharpness, and the first and last band at or above half of it. */
    fun peak(bands: DoubleArray): Triple<Int, Int, Int> {
        val best = bands.indices.maxBy { bands[it] }
        val floor = bands.min()
        val half = floor + (bands[best] - floor) / 2
        var lo = best
        while (lo > 0 && bands[lo - 1] >= half) lo--
        var hi = best
        while (hi < bands.lastIndex && bands[hi + 1] >= half) hi++
        return Triple(best, lo, hi)
    }

    /**
     * The scale (about the centre) and shift that best map row profile [a] onto [b]: focus breathing between two
     * focus distances (T10). Profiles are normalised; the search covers ±[maxScale] and ±[maxShift] rows.
     */
    fun scaleBetween(a: FloatArray, b: FloatArray, maxScale: Float = 0.03f, maxShift: Float = 40f): Pair<Float, Float> {
        val na = normalised(a)
        val nb = normalised(b)
        val centre = (na.size - 1) / 2f
        var best = 1f to 0f
        var bestCost = Double.MAX_VALUE
        var s = 1 - maxScale
        while (s <= 1 + maxScale) {
            var t = -maxShift
            while (t <= maxShift) {
                val cost = cost(na, nb, centre, s, t)
                if (cost < bestCost) {
                    bestCost = cost
                    best = s to t
                }
                t += SHIFT_STEP
            }
            s += SCALE_STEP
        }
        return best
    }

    private fun cost(a: FloatArray, b: FloatArray, centre: Float, scale: Float, shift: Float): Double {
        var sum = 0.0
        var n = 0
        val from = (a.size * MARGIN).toInt()
        for (i in from until a.size - from) {
            val p = centre + scale * (i - centre) + shift
            val j = p.toInt()
            if (j < 0 || j >= b.size - 1) continue
            val v = b[j] + (b[j + 1] - b[j]) * (p - j)
            val d = a[i] - v
            sum += d * d
            n++
        }
        return if (n == 0) Double.MAX_VALUE else sum / n
    }

    private fun normalised(p: FloatArray): FloatArray {
        val mean = p.average()
        val sd = sqrt(p.sumOf { (it - mean) * (it - mean) } / p.size).takeIf { it > 0 } ?: 1.0
        return FloatArray(p.size) { ((p[it] - mean) / sd).toFloat() }
    }

    private const val SCALE_STEP = 0.0005f
    private const val SHIFT_STEP = 0.5f
    private const val MARGIN = 0.1f

    /** The central columns of the frame, on the Bayer phase of an even offset. */
    private class View(image: Image, columnFraction: Float) {
        private val buffer = image.planes[0].buffer.asShortBuffer()
        private val stride = image.planes[0].rowStride / 2
        private val span = (image.width * columnFraction).toInt()
        val x0 = ((image.width - span) / 2 and 1.inv()).coerceAtLeast(BAYER_STEP)
        val x1 = (x0 + span).coerceAtMost(image.width - BAYER_STEP)
        private val height = image.height

        fun at(x: Int, y: Int): Int = buffer.get(y.coerceIn(0, height - 1) * stride + x).toInt() and U16

        fun laplacian(x: Int, y: Int): Double = (
            4 * at(x, y) - at(x - BAYER_STEP, y) - at(x + BAYER_STEP, y) - at(x, y - BAYER_STEP) -
                at(x, y + BAYER_STEP)
            ).toDouble()
    }
}
