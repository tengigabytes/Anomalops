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

    override fun merge(luma: List<Plane>, channels: List<List<Plane>>): List<Plane> =
        accumulator().mergeAll(luma, channels)

    /**
     * Per band level, the best score so far and each channel's coefficient from that frame (ties keep the earlier
     * frame); the top level sums every frame. Memory is one pyramid per channel plus the scores, whatever the count.
     */
    override fun accumulator(): FocusAccumulator = object : FocusAccumulator {
        private var frames = 0
        private lateinit var best: List<FloatArray>
        private lateinit var picked: List<List<Plane>>
        private lateinit var top: List<DoubleArray>

        override fun add(luma: Plane, channels: List<Plane>) {
            val lumaBands = bands(luma)
            val bandLevels = lumaBands.size - 1
            val scores = (0 until bandLevels).map {
                Filters.box(
                    Filters.map(lumaBands[it]) { v -> abs(v) },
                    selectRadius,
                )
            }
            if (frames == 0) {
                best = scores.map { it.data.copyOf() }
                picked = channels.map { bands(it) }
                top = picked.map { pyramid ->
                    DoubleArray(
                        pyramid.last().data.size,
                    ) { pyramid.last().data[it].toDouble() }
                }
            } else {
                val wins = scores.mapIndexed { level, score -> winsOver(best[level], score.data) }
                channels.forEachIndexed { c, channel ->
                    val channelBands = bands(channel)
                    for (level in 0 until bandLevels) {
                        val into = picked[c][level].data
                        val from = channelBands[level].data
                        for (i in into.indices) if (wins[level][i]) into[i] = from[i]
                    }
                    val sum = top[c]
                    channelBands.last().data.forEachIndexed { i, v -> sum[i] += v.toDouble() }
                }
            }
            frames++
        }

        override fun finish(): List<Plane> = picked.mapIndexed { c, pyramid ->
            val low = pyramid.last()
            val mean = Plane(low.width, low.height, FloatArray(low.data.size) { (top[c][it] / frames).toFloat() })
            collapse(pyramid.dropLast(1) + mean)
        }
    }

    /** Where [score] beats [best] (strictly, so ties keep the earlier frame), updating [best] there. */
    private fun winsOver(best: FloatArray, score: FloatArray): BooleanArray = BooleanArray(best.size) { i ->
        (best[i] < score[i]).also { if (it) best[i] = score[i] }
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
