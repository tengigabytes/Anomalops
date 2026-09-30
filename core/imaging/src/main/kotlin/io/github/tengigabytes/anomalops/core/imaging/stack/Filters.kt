// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.core.imaging.stack

import io.github.tengigabytes.anomalops.core.imaging.align.Plane

/** Plane filters the focus-stacking candidates share (ADR-0015). Edges are clamped: the border pixel repeats. */
internal object Filters {
    private const val HALF = 0.5f
    private const val LAPLACIAN_CENTRE = 4f

    /** The 4-neighbour Laplacian: how much a pixel differs from its surroundings, the fine detail focus brings. */
    fun laplacian(p: Plane): Plane {
        val out = Plane(p.width, p.height)
        for (y in 0 until p.height) {
            for (x in 0 until p.width) {
                out[x, y] = LAPLACIAN_CENTRE * p[x, y] - p.clamped(x - 1, y) - p.clamped(x + 1, y) -
                    p.clamped(x, y - 1) - p.clamped(x, y + 1)
            }
        }
        return out
    }

    /** Mean over a (2 [radius] + 1)² window, by a summed-area table so the cost does not grow with the radius. */
    fun box(p: Plane, radius: Int): Plane {
        val w = p.width
        val h = p.height
        val sums = DoubleArray((w + 1) * (h + 1))
        for (y in 0 until h) {
            var row = 0.0
            for (x in 0 until w) {
                row += p[x, y]
                sums[(y + 1) * (w + 1) + x + 1] = sums[y * (w + 1) + x + 1] + row
            }
        }
        val out = Plane(w, h)
        for (y in 0 until h) {
            val y0 = (y - radius).coerceAtLeast(0)
            val y1 = (y + radius + 1).coerceAtMost(h)
            for (x in 0 until w) {
                val x0 = (x - radius).coerceAtLeast(0)
                val x1 = (x + radius + 1).coerceAtMost(w)
                val s =
                    sums[y1 * (w + 1) + x1] - sums[y0 * (w + 1) + x1] - sums[y1 * (w + 1) + x0] +
                        sums[y0 * (w + 1) + x0]
                out[x, y] = (s / ((x1 - x0) * (y1 - y0))).toFloat()
            }
        }
        return out
    }

    /**
     * He, Sun and Tang's guided filter: [input] smoothed so its edges follow those of [guide], with a window of
     * [radius] and regularisation [eps] (in squared guide units; larger smooths across weaker edges).
     */
    fun guided(guide: Plane, input: Plane, radius: Int, eps: Float): Plane {
        val meanI = box(guide, radius)
        val meanP = box(input, radius)
        val corrI = box(map(guide) { it * it }, radius)
        val corrIp = box(zip(guide, input) { i, p -> i * p }, radius)
        val a = Plane(guide.width, guide.height)
        val b = Plane(guide.width, guide.height)
        for (i in a.data.indices) {
            val varI = corrI.data[i] - meanI.data[i] * meanI.data[i]
            val covIp = corrIp.data[i] - meanI.data[i] * meanP.data[i]
            a.data[i] = covIp / (varI + eps)
            b.data[i] = meanP.data[i] - a.data[i] * meanI.data[i]
        }
        val meanA = box(a, radius)
        val meanB = box(b, radius)
        return zip(guide, meanA) { g, ma -> ma * g }.also { out ->
            for (i in out.data.indices) out.data[i] += meanB.data[i]
        }
    }

    /**
     * [p] resized to [width] x [height] (twice its size, give or take an odd pixel), bilinear, in the coordinates of
     * [Plane.half]: a pixel x of the larger plane sits at (x + 0.5) / 2 - 0.5 of the smaller.
     */
    fun double(p: Plane, width: Int, height: Int): Plane {
        val out = Plane(width, height)
        for (y in 0 until height) {
            val sy = ((y + HALF) / 2 - HALF).coerceIn(0f, p.height - 1f)
            for (x in 0 until width) {
                val sx = ((x + HALF) / 2 - HALF).coerceIn(0f, p.width - 1f)
                out[x, y] = p.sample(sx, sy)
            }
        }
        return out
    }

    fun map(p: Plane, f: (Float) -> Float): Plane = Plane(p.width, p.height, FloatArray(p.data.size) { f(p.data[it]) })

    fun zip(a: Plane, b: Plane, f: (Float, Float) -> Float): Plane =
        Plane(a.width, a.height, FloatArray(a.data.size) { f(a.data[it], b.data[it]) })

    private fun Plane.clamped(x: Int, y: Int): Float = this[x.coerceIn(0, width - 1), y.coerceIn(0, height - 1)]
}
