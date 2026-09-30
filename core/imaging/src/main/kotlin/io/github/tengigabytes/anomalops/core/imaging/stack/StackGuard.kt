// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.core.imaging.stack

import io.github.tengigabytes.anomalops.core.imaging.align.Plane

/**
 * FR-33's failure guard (ADR-0015): the merge is kept only when it is at least as sharp as the sharpest single
 * frame; otherwise that frame is the output and the stack is marked failed (every DNG is kept either way, FR-63).
 */
class StackGuard(private val stack: FocusStack) {
    /** [merged] when the merge was kept; [bestFrame] the sharpest single frame, used when it was not. */
    class Result(
        val merged: Boolean,
        val bestFrame: Int,
        val sharpness: Double,
        val bestSingle: Double,
        val channels: List<Plane>,
    )

    /** [luma] and [channels] as for [FocusStack.merge]; missing pixels (NaN from alignment) filled from [reference]. */
    fun merge(luma: List<Plane>, channels: List<List<Plane>>, reference: Int = 0): Result {
        val filledLuma = fillMissing(luma, reference)
        val filledChannels = channels[0].indices.map { c -> fillMissing(channels.map { it[c] }, reference) }
            .let { perChannel -> luma.indices.map { k -> perChannel.map { it[k] } } }
        val singles = filledLuma.map { sharpness(it) }
        val best = singles.indices.maxBy { singles[it] }
        // Luma rides along as one more channel, so the merge that is scored is the one that is returned.
        val merged = stack.merge(filledLuma, filledChannels.mapIndexed { k, c -> c + filledLuma[k] })
        val score = sharpness(merged.last())
        return if (score >= singles[best]) {
            Result(true, best, score, singles[best], merged.dropLast(1))
        } else {
            Result(false, best, score, singles[best], filledChannels[best])
        }
    }

    companion object {
        private const val MARGIN = 0.05f

        /**
         * Variance of the Laplacian over the plane without a [MARGIN] border: the same kind of score as FR-69's
         * sharpness, compared only between frames of one bracket.
         */
        fun sharpness(p: Plane): Double {
            val lap = Filters.laplacian(p)
            val mx = (p.width * MARGIN).toInt()
            val my = (p.height * MARGIN).toInt()
            var sum = 0.0
            var squares = 0.0
            var n = 0
            for (y in my until p.height - my) {
                for (x in mx until p.width - mx) {
                    val v = lap[x, y].toDouble()
                    sum += v
                    squares += v * v
                    n++
                }
            }
            val mean = sum / n
            return squares / n - mean * mean
        }

        /** Each frame with its NaN pixels (outside the aligned area) replaced by [reference]'s. */
        fun fillMissing(frames: List<Plane>, reference: Int): List<Plane> = frames.map { f ->
            Filters.zip(f, frames[reference]) { v, r -> if (v.isNaN()) r else v }
        }
    }
}
