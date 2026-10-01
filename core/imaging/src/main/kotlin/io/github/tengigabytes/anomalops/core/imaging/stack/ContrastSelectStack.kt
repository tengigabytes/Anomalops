// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.core.imaging.stack

import io.github.tengigabytes.anomalops.core.imaging.align.Plane

/**
 * Candidate A, local-contrast selection: per pixel, the frame whose Laplacian energy (squared, averaged over
 * [focusRadius]) is highest; the one-hot choice maps are box-smoothed over [blendRadius] and used as weights.
 *
 * A pixel's output uses the values of every frame chosen near it, and a later frame can overturn an earlier
 * choice, so the per-frame form takes two passes (ADR-0017, maintainer's decision 2026-10-02): the first keeps
 * only the best score and its frame per pixel, the second adds each frame with its smoothed weight.
 */
class ContrastSelectStack(private val focusRadius: Int = 4, private val blendRadius: Int = 6) : FocusStack {
    override val name = "A contrast select"

    override fun merge(luma: List<Plane>, channels: List<List<Plane>>): List<Plane> =
        accumulator().mergeAll(luma, channels)

    override fun accumulator(): FocusAccumulator = object : FocusAccumulator {
        override val passes = 2
        private var frames = 0
        private var added = 0
        private var best: FloatArray? = null
        private lateinit var choice: IntArray
        private var width = 0
        private var height = 0
        private lateinit var total: FloatArray
        private var sums: List<FloatArray>? = null

        override fun add(luma: Plane, channels: List<Plane>) {
            if (best != null || frames == 0) score(luma) else blend(channels)
        }

        /** First pass: the highest measure so far and its frame (ties keep the earlier frame). */
        private fun score(luma: Plane) {
            val measure = focusMeasures(listOf(luma), focusRadius).single().data
            val current = best
            if (current == null) {
                best = measure
                choice = IntArray(measure.size)
                width = luma.width
                height = luma.height
            } else {
                for (i in measure.indices) {
                    if (current[i] < measure[i]) {
                        current[i] = measure[i]
                        choice[i] = frames
                    }
                }
            }
            frames++
        }

        /** The weights' per-pixel total, summed in frame order as [weighted] does. */
        override fun endPass() {
            best = null
            val sum = DoubleArray(choice.size)
            for (k in 0 until frames) weight(k).data.forEachIndexed { i, w -> sum[i] += w.toDouble() }
            total = FloatArray(sum.size) { sum[it].toFloat() }
        }

        /** Second pass: each frame's channels times its normalised weight, in frame order. */
        private fun blend(channels: List<Plane>) {
            check(added < frames) { "more frames in the second pass than the first ($frames)" }
            val w = weight(added).data
            val into = sums ?: channels.map { FloatArray(it.data.size) }.also { sums = it }
            for (c in channels.indices) {
                val from = channels[c].data
                val out = into[c]
                for (i in out.indices) out[i] += (if (total[i] > 0f) w[i] / total[i] else 1f / frames) * from[i]
            }
            added++
        }

        override fun finish(): List<Plane> {
            check(added == frames) { "second pass had $added of $frames frames" }
            return checkNotNull(sums).map { Plane(width, height, it) }
        }

        private fun weight(k: Int): Plane =
            Filters.box(Plane(width, height, FloatArray(choice.size) { if (choice[it] == k) 1f else 0f }), blendRadius)
    }
}
