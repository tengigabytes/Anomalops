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

    /**
     * All frames at once: [luma] and [channels] as for [FocusStack.merge]; missing pixels (NaN from alignment) filled
     * from [reference]. The same as [start] with the reference, then [Session.add] of every frame in order.
     */
    fun merge(luma: List<Plane>, channels: List<List<Plane>>, reference: Int = 0): Result =
        start(luma[reference], channels[reference]).apply { luma.indices.forEach { add(luma[it], channels[it]) } }
            .finish()

    /**
     * One frame at a time (ADR-0017). The reference is given first, to fill other frames' missing pixels, and is
     * also added at its place in the bracket like any other frame. Besides the stack's own state, the session holds
     * the reference and the sharpest frame so far (the fallback).
     */
    fun start(referenceLuma: Plane, referenceChannels: List<Plane>): Session = Session(referenceLuma, referenceChannels)

    inner class Session internal constructor(
        private val referenceLuma: Plane,
        private val referenceChannels: List<Plane>,
    ) {
        private val accumulator = stack.accumulator()
        private var frames = 0
        private var bestFrame = -1
        private var bestSingle = Double.NaN
        private var bestChannels: List<Plane> = emptyList()

        fun add(luma: Plane, channels: List<Plane>) {
            val filledLuma = fill(luma, referenceLuma)
            val filledChannels = channels.mapIndexed { c, p -> fill(p, referenceChannels[c]) }
            val single = sharpness(filledLuma)
            // Strictly better, so ties keep the earlier frame.
            if (bestFrame < 0 || bestSingle < single) {
                bestFrame = frames
                bestSingle = single
                bestChannels = filledChannels
            }
            // Luma rides along as one more channel, so the merge that is scored is the one that is returned.
            accumulator.add(filledLuma, filledChannels + filledLuma)
            frames++
        }

        fun finish(): Result {
            val merged = accumulator.finish()
            val score = sharpness(merged.last())
            return if (score >= bestSingle) {
                Result(true, bestFrame, score, bestSingle, merged.dropLast(1))
            } else {
                Result(false, bestFrame, score, bestSingle, bestChannels)
            }
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
        fun fillMissing(frames: List<Plane>, reference: Int): List<Plane> = frames.map { fill(it, frames[reference]) }

        private fun fill(frame: Plane, reference: Plane): Plane =
            Filters.zip(frame, reference) { v, r -> if (v.isNaN()) r else v }
    }
}
