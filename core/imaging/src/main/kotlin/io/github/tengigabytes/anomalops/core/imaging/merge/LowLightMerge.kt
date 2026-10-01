// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.core.imaging.merge

import io.github.tengigabytes.anomalops.core.imaging.align.AlignOptions
import io.github.tengigabytes.anomalops.core.imaging.align.Alignment
import io.github.tengigabytes.anomalops.core.imaging.align.FrameAligner
import io.github.tengigabytes.anomalops.core.imaging.align.Plane
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * FR-17, first stage: equal-exposure frames of a low-light burst averaged into one with less noise. Each frame
 * is aligned to the reference (whole frame, then per tile); its pixels are read at the aligned position (tile
 * shifts interpolated between tile centres) and weighted by how well they agree with the reference: a pixel whose
 * neighbourhood differs by much more than the noise (a fish that moved, a tile that did not align) gets little
 * weight, so it does not leave a ghost. A CPU reference; FR-17 asks for the GPU.
 *
 * [robustness] scales the noise allowance: weight = exp(-d² / (robustness · σ²)), d the 3 x 3 mean difference
 * against the reference. Proposed 4, to be set from real bursts.
 */
class LowLightMerge(private val options: AlignOptions = AlignOptions(), private val robustness: Float = 4f) {
    /** The merged frames' [luma] decides alignment and weights; every plane of [channels] is merged the same way. */
    class Result(val channels: List<Plane>, val alignments: List<Alignment?>)

    /**
     * All frames at once: [luma] and [channels] per frame, frame [reference] (default the first) kept as is;
     * [noiseSigma] the reference luma's noise standard deviation (from the sensor's noise profile, or
     * [estimateNoise]). The same as [start] with the reference followed by [Accumulator.add] of the others in order.
     */
    fun merge(
        luma: List<Plane>,
        channels: List<List<Plane>> = luma.map { listOf(it) },
        noiseSigma: Float = estimateNoise(luma[0]),
        reference: Int = 0,
    ): Result {
        val accumulator = start(luma[reference], channels[reference], noiseSigma)
        val alignments = luma.indices.map { k -> if (k == reference) null else accumulator.add(luma[k], channels[k]) }
        return Result(accumulator.finish(), alignments)
    }

    /**
     * One frame at a time (ADR-0017): only the reference, the running sums and the frame being added are held, so
     * memory does not grow with the burst. [noiseSigma] as for [merge].
     */
    fun start(
        referenceLuma: Plane,
        referenceChannels: List<Plane> = listOf(referenceLuma),
        noiseSigma: Float = estimateNoise(referenceLuma),
    ): Accumulator = Accumulator(referenceLuma, referenceChannels, noiseSigma)

    /** Running weighted sums of one burst; the reference counts with weight 1 everywhere. */
    inner class Accumulator internal constructor(
        private val refLuma: Plane,
        referenceChannels: List<Plane>,
        private val sigma: Float,
    ) {
        private val aligner = FrameAligner(refLuma, options)
        private val sums = referenceChannels.map { it.data.copyOf() }
        private val weightSum = FloatArray(refLuma.data.size) { 1f }

        /** Aligns one frame to the reference and adds it; returns the alignment. */
        fun add(luma: Plane, channels: List<Plane>): Alignment {
            require(channels.size == sums.size) { "${channels.size} channels, the reference has ${sums.size}" }
            return aligner.align(luma).also { accumulate(it, luma, channels) }
        }

        /** The merged channels; the accumulator should not be used afterwards. */
        fun finish(): List<Plane> =
            sums.map { sum -> Plane(refLuma.width, refLuma.height, FloatArray(sum.size) { sum[it] / weightSum[it] }) }

        private fun accumulate(alignment: Alignment, frameLuma: Plane, frameChannels: List<Plane>) {
            val field = OffsetField(alignment, refLuma.width, refLuma.height)
            val diff = field.warp(frameLuma)
            for (i in diff.data.indices) diff.data[i] = diff.data[i] - refLuma.data[i]
            val localDiff = mean3(diff)
            val allowance = robustness * sigma * sigma
            val values = FloatArray(frameChannels.size)
            field.forEachPosition { i, x, y ->
                // NaN (outside the aligned frame) fails every comparison, so such pixels add nothing.
                val d = localDiff.data[i]
                val w = exp(-d * d / allowance)
                if (w >= MIN_WEIGHT) {
                    for (c in values.indices) values[c] = frameChannels[c].sample(x, y)
                    if (values.none { it.isNaN() }) {
                        weightSum[i] += w
                        for (c in sums.indices) sums[c][i] += w * values[c]
                    }
                }
            }
        }
    }

    /** 3 x 3 mean ignoring NaN; NaN where all nine are missing. */
    private fun mean3(p: Plane): Plane {
        val out = Plane(p.width, p.height)
        for (y in 0 until p.height) {
            for (x in 0 until p.width) out[x, y] = neighbourhoodMean(p, x, y)
        }
        return out
    }

    private fun neighbourhoodMean(p: Plane, x: Int, y: Int): Float {
        var sum = 0f
        var n = 0
        for (dy in -1..1) {
            for (dx in -1..1) {
                val v = p[(x + dx).coerceIn(0, p.width - 1), (y + dy).coerceIn(0, p.height - 1)]
                if (!v.isNaN()) {
                    sum += v
                    n++
                }
            }
        }
        return if (n == 0) Float.NaN else sum / n
    }

    companion object {
        private const val MIN_WEIGHT = 1e-3f
        private const val MAD_TO_SIGMA = 1.4826f

        /** Laplacian of independent noise has variance 20 σ² (4² + 4 · 1²). */
        private const val LAPLACIAN_NOISE_GAIN = 20f

        /**
         * The noise σ of [p] from the median absolute value of its 4-neighbour Laplacian, which a smooth scene
         * hardly moves; a fallback when the sensor's noise profile is not at hand.
         */
        fun estimateNoise(p: Plane): Float {
            val values = FloatArray((p.width - 2) * (p.height - 2))
            var n = 0
            for (y in 1 until p.height - 1) {
                for (x in 1 until p.width - 1) {
                    val lap = 4 * p[x, y] - p[x - 1, y] - p[x + 1, y] - p[x, y - 1] - p[x, y + 1]
                    values[n++] = abs(lap)
                }
            }
            values.sort()
            val mad = values[n / 2]
            return MAD_TO_SIGMA * mad / sqrt(LAPLACIAN_NOISE_GAIN)
        }
    }
}
