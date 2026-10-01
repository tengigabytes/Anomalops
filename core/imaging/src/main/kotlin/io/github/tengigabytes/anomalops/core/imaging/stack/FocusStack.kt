// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.core.imaging.stack

import io.github.tengigabytes.anomalops.core.imaging.align.Plane

/**
 * One of ADR-0015's candidate algorithms for FR-33: frames of a focus bracket, already aligned to one grid and with
 * no missing pixels, merged into one image that is sharp wherever some frame was. [luma] (one plane per frame)
 * decides; [channels] (per frame, for example linear R, G, B) are merged by the same decision, so colour follows
 * the detail. Which candidate FR-33 uses is chosen by T12 (docs/test/macro-stacking-test-plan.md).
 */
interface FocusStack {
    val name: String

    fun merge(luma: List<Plane>, channels: List<List<Plane>> = luma.map { listOf(it) }): List<Plane>

    /** The per-frame form; by default it keeps every frame until the end (see [CollectingAccumulator]). */
    fun accumulator(): FocusAccumulator = CollectingAccumulator(this)
}

/**
 * Candidate C, weight map refined by a guided filter: the same one-hot choice as [ContrastSelectStack], but each
 * frame's map is smoothed with a guided filter led by that frame, so the weights follow the subject's edges
 * instead of spilling over them (a simplified single-scale form of Li, Kang and Hu's guided-filter fusion).
 */
class GuidedWeightStack(
    private val focusRadius: Int = 4,
    private val guideRadius: Int = 8,
    private val eps: Float = 0.3f,
) : FocusStack {
    override val name = "C guided weights"

    override fun merge(luma: List<Plane>, channels: List<List<Plane>>): List<Plane> {
        val choice = winners(focusMeasures(luma, focusRadius))
        // The guide is normalised to 0..1 per frame so eps means the same for any exposure.
        val weights = oneHot(choice, luma.size, luma[0]).mapIndexed { k, map ->
            Filters.map(Filters.guided(normalised(luma[k]), map, guideRadius, eps)) { it.coerceAtLeast(0f) }
        }
        return weighted(weights, channels)
    }

    private fun normalised(p: Plane): Plane {
        val lo = p.data.min()
        val span = (p.data.max() - lo).takeIf { it > 0f } ?: 1f
        return Filters.map(p) { (it - lo) / span }
    }
}

/**
 * Laplacian energy of each frame, averaged over a window: where that frame has fine detail. The frame is first
 * smoothed over 3 x 3 (a Laplacian of a smoothed image), because a bare Laplacian mostly measures sensor noise,
 * which every frame has and which would then decide the choice (FocusStackTest, noisy bracket).
 */
internal fun focusMeasures(luma: List<Plane>, radius: Int): List<Plane> =
    luma.map { Filters.box(Filters.map(Filters.laplacian(Filters.box(it, 1))) { v -> v * v }, radius) }

/** Per pixel, the index of the frame with the highest measure. */
internal fun winners(measures: List<Plane>): IntArray {
    val size = measures[0].data.size
    return IntArray(size) { i -> measures.indices.maxBy { measures[it].data[i] } }
}

internal fun oneHot(choice: IntArray, frames: Int, like: Plane): List<Plane> = List(frames) { k ->
    Plane(like.width, like.height, FloatArray(choice.size) { if (choice[it] == k) 1f else 0f })
}

/** Σ weight × channel per pixel, weights normalised to sum to 1 (equal weights where they all vanish). */
internal fun weighted(weights: List<Plane>, channels: List<List<Plane>>): List<Plane> {
    val size = weights[0].data.size
    val total = FloatArray(size) { i -> weights.sumOf { it.data[i].toDouble() }.toFloat() }
    return channels[0].indices.map { c ->
        val out = Plane(weights[0].width, weights[0].height)
        for (i in 0 until size) {
            var v = 0f
            for (k in weights.indices) {
                val w = if (total[i] > 0f) weights[k].data[i] / total[i] else 1f / weights.size
                v += w * channels[k][c].data[i]
            }
            out.data[i] = v
        }
        out
    }
}
