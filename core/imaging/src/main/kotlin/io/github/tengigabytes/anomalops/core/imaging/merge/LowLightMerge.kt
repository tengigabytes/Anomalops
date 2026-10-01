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
     * [luma] and [channels] per frame, frame [reference] (default the first) kept as is; [noiseSigma] the
     * reference luma's noise standard deviation (from the sensor's noise profile, or [estimateNoise]).
     */
    fun merge(
        luma: List<Plane>,
        channels: List<List<Plane>> = luma.map { listOf(it) },
        noiseSigma: Float = estimateNoise(luma[0]),
        reference: Int = 0,
    ): Result {
        val aligner = FrameAligner(luma[reference], options)
        val sums = channels[reference].map { it.data.copyOf() }
        val weightSum = FloatArray(luma[reference].data.size) { 1f }
        val alignments = luma.indices.map { k ->
            if (k == reference) {
                null
            } else {
                aligner.align(
                    luma[k],
                ).also { add(it, luma[reference], luma[k], channels[k], noiseSigma, sums, weightSum) }
            }
        }
        val ref = luma[reference]
        val merged = sums.map { sum -> Plane(ref.width, ref.height, FloatArray(sum.size) { sum[it] / weightSum[it] }) }
        return Result(merged, alignments)
    }

    @Suppress("LongParameterList") // One frame's contribution into the running sums.
    private fun add(
        alignment: Alignment,
        refLuma: Plane,
        frameLuma: Plane,
        frameChannels: List<Plane>,
        sigma: Float,
        sums: List<FloatArray>,
        weightSum: FloatArray,
    ) {
        val field = OffsetField(alignment, refLuma.width, refLuma.height)
        val warpedLuma = field.warp(frameLuma)
        val diff = Plane(refLuma.width, refLuma.height)
        for (i in diff.data.indices) diff.data[i] = warpedLuma.data[i] - refLuma.data[i]
        val localDiff = mean3(diff)
        val allowance = robustness * sigma * sigma
        val warped = frameChannels.map { field.warp(it) }
        for (i in localDiff.data.indices) {
            // NaN (outside the aligned frame) fails every comparison, so such pixels add nothing.
            val d = localDiff.data[i]
            val w = exp(-d * d / allowance)
            if (w >= MIN_WEIGHT && warped.none { it.data[i].isNaN() }) {
                weightSum[i] += w
                for (c in sums.indices) sums[c][i] += w * warped[c].data[i]
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
