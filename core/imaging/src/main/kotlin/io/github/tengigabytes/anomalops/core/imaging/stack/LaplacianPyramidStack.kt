// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.core.imaging.stack

import io.github.tengigabytes.anomalops.core.imaging.align.Plane
import kotlin.math.abs

/**
 * Candidate B, Laplacian-pyramid fusion (Burt and Kolczynski): each frame split into band-pass levels; on every level
 * the coefficient comes from the frame whose luma band has the most energy there (absolute value averaged over
 * [selectRadius]), the coarsest level is the mean of all frames; then the pyramid is collapsed. Down to a
 * short side of [minSize] or [maxLevels] levels.
 */
class LaplacianPyramidStack(
    private val maxLevels: Int = 7,
    private val minSize: Int = 8,
    private val selectRadius: Int = 1,
) : FocusStack {
    override val name = "B Laplacian pyramid"

    override fun merge(luma: List<Plane>, channels: List<List<Plane>>): List<Plane> {
        val lumaPyramids = luma.map { bands(it) }
        val depth = lumaPyramids[0].size
        // Per band level, which frame wins each coefficient (the top level is averaged instead).
        val choices = (0 until depth - 1).map { level ->
            winners(lumaPyramids.map { Filters.box(Filters.map(it[level]) { v -> abs(v) }, selectRadius) })
        }
        return channels[0].indices.map { c ->
            val pyramids = channels.map { bands(it[c]) }
            val merged = (0 until depth).map { level ->
                val like = pyramids[0][level]
                if (level == depth - 1) {
                    Plane(
                        like.width,
                        like.height,
                        FloatArray(like.data.size) { i -> pyramids.map { it[level].data[i] }.average().toFloat() },
                    )
                } else {
                    Plane(
                        like.width,
                        like.height,
                        FloatArray(like.data.size) { i -> pyramids[choices[level][i]][level].data[i] },
                    )
                }
            }
            collapse(merged)
        }
    }

    /** Band-pass levels, finest first, then the low-pass top. */
    private fun bands(p: Plane): List<Plane> {
        val gaussian = buildList {
            add(p)
            while (size < maxLevels && minOf(last().width, last().height) / 2 >= minSize) add(last().half())
        }
        return gaussian.indices.map { l ->
            if (l == gaussian.lastIndex) {
                gaussian[l]
            } else {
                val up = Filters.double(gaussian[l + 1], gaussian[l].width, gaussian[l].height)
                Filters.zip(gaussian[l], up) { a, b -> a - b }
            }
        }
    }

    private fun collapse(levels: List<Plane>): Plane {
        var image = levels.last()
        for (l in levels.lastIndex - 1 downTo 0) {
            val up = Filters.double(image, levels[l].width, levels[l].height)
            image = Filters.zip(levels[l], up) { band, low -> band + low }
        }
        return image
    }
}
