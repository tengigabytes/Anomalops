// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.core.imaging.merge

import io.github.tengigabytes.anomalops.core.imaging.align.Alignment
import io.github.tengigabytes.anomalops.core.imaging.align.Plane

/**
 * Where each reference pixel is in the frame: the whole-frame transform plus the tile residuals, interpolated
 * bilinearly between tile centres so neighbouring tiles do not leave a seam (edges hold the nearest tile).
 */
internal class OffsetField(private val alignment: Alignment, private val width: Int, private val height: Int) {
    private val tiles = alignment.tiles
    private val global = alignment.global.atLevel(0)

    /** [frame] resampled onto the reference grid; NaN where the aligned position falls outside it. */
    fun warp(frame: Plane): Plane {
        val out = Plane(width, height)
        forEachPosition { i, fx, fy -> out.data[i] = frame.sample(fx, fy) }
        return out
    }

    /** Calls [visit] with each reference pixel's index (row-major) and where that pixel lies in the frame. */
    fun forEachPosition(visit: (index: Int, x: Float, y: Float) -> Unit) {
        for (y in 0 until height) {
            for (x in 0 until width) {
                val (u, v) = residual(x, y)
                visit(y * width + x, global.mapX(x.toFloat(), width) + u, global.mapY(y.toFloat(), height) + v)
            }
        }
    }

    private fun residual(x: Int, y: Int): Pair<Float, Float> {
        if (tiles.cols == 0 || tiles.rows == 0) return 0f to 0f
        val size = tiles.tileSize.toFloat()
        val fx = ((x + HALF) / size - HALF).coerceIn(0f, tiles.cols - 1f)
        val fy = ((y + HALF) / size - HALF).coerceIn(0f, tiles.rows - 1f)
        val c0 = fx.toInt().coerceAtMost(tiles.cols - 1)
        val r0 = fy.toInt().coerceAtMost(tiles.rows - 1)
        val c1 = (c0 + 1).coerceAtMost(tiles.cols - 1)
        val r1 = (r0 + 1).coerceAtMost(tiles.rows - 1)
        val ax = fx - c0
        val ay = fy - r0
        fun lerp(values: FloatArray): Float {
            val top = values[tiles.index(c0, r0)] * (1 - ax) + values[tiles.index(c1, r0)] * ax
            val bottom = values[tiles.index(c0, r1)] * (1 - ax) + values[tiles.index(c1, r1)] * ax
            return top * (1 - ay) + bottom * ay
        }
        return lerp(tiles.dx) to lerp(tiles.dy)
    }

    private companion object {
        const val HALF = 0.5f
    }
}
