// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.core.imaging.align

import kotlin.math.ceil

/**
 * Whole-frame alignment: the scale and shift that best map the reference onto the frame (ADR-0015, shared by
 * FR-17 and FR-33). Coarse to fine on both pyramids: an exhaustive search over shift and scale on the top level,
 * then ±1 pixel and a halved scale step on each level below (cost: the mean squared difference, [meanSquaredDiff]),
 * then sub-pixel refinement on level 0 by [GaussNewton], which a cost-based fit would bias (see there). A
 * least-squares fit: a subject moving on its own pulls it a little, and [TileAligner] takes up the difference tile
 * by tile.
 *
 * The search itself is shared by every backend (ADR-0017): the second [align] takes the costs and the normal
 * equations as functions, which `:core:gpu` computes on the GPU.
 */
class GlobalAligner(private val options: AlignOptions = AlignOptions()) {
    fun align(reference: Pyramid, frame: Pyramid): Similarity {
        require(reference.levels.size == frame.levels.size) { "pyramids of different depth" }
        val base = reference[0]
        val full = base.width to base.height
        val sums = GradientSums(base, Region.inner(base.width, base.height, options.margin), options.sampleStep)
        return align(
            reference.top,
            costs = { level, candidates ->
                val ref = reference[level]
                val region = Region.inner(ref.width, ref.height, options.margin)
                FloatArray(candidates.size) {
                    meanSquaredDiff(ref, frame[level], candidates[it].atLevel(level), region, options.sampleStep, full)
                }
            },
            equations = { sums.at(frame[0], it) },
        )
    }

    /**
     * The search with pyramids of [top] + 1 levels: [costs] gives the [meanSquaredDiff] of each candidate on a
     * level, over [Region.inner] of that level with [AlignOptions.margin] and grid spacing [AlignOptions.sampleStep];
     * [equations] gives [GaussNewton]'s normal equations on level 0 over the same region. Ties keep the earlier
     * candidate, as the costs are compared in order.
     */
    fun align(
        top: Int,
        costs: (Int, List<Similarity>) -> FloatArray,
        equations: (Similarity) -> NormalEquations,
    ): Similarity {
        val first = topCandidates(top)
        var best = pick(Similarity(), Float.POSITIVE_INFINITY, first, costs(top, first))
        var scaleStep = options.scaleStep
        for (level in top - 1 downTo 0) {
            scaleStep /= 2
            val around = around(best, (1 shl level).toFloat(), scaleStep)
            val all = costs(level, listOf(best) + around)
            best = pick(best, all[0], around, all.copyOfRange(1, all.size))
        }
        return GaussNewton().refine(best, equations)
    }

    /** The exhaustive grid of the top level, scale outermost and x innermost. */
    private fun topCandidates(top: Int): List<Similarity> {
        val factor = 1 shl top
        val radius = ceil(options.maxShiftPx.toFloat() / factor).toInt()
        val scaleCount = ceil(options.maxScaleDelta / options.scaleStep).toInt()
        return (-scaleCount..scaleCount).flatMap { k ->
            val scale = 1f + k * options.scaleStep
            (-radius..radius).flatMap { dy ->
                (-radius..radius).map { dx -> Similarity(scale, (dx * factor).toFloat(), (dy * factor).toFloat()) }
            }
        }
    }

    /** ±1 [factor]-pixel step in x and y and ±[scaleStep] around [start], scale outermost and x innermost. */
    private fun around(start: Similarity, factor: Float, scaleStep: Float): List<Similarity> = (-1..1).flatMap { ds ->
        (-1..1).flatMap { dy ->
            (-1..1).map { dx ->
                Similarity(start.scale + ds * scaleStep, start.dx + dx * factor, start.dy + dy * factor)
            }
        }
    }

    /** The first of [candidates] whose cost is below every earlier one and [bestCost]; [best] if none is. */
    private fun pick(best: Similarity, bestCost: Float, candidates: List<Similarity>, costs: FloatArray): Similarity {
        var chosen = best
        var lowest = bestCost
        candidates.forEachIndexed { i, candidate ->
            if (costs[i] < lowest) {
                lowest = costs[i]
                chosen = candidate
            }
        }
        return chosen
    }
}

/**
 * The offset of a parabola's minimum through costs at -[step], 0 and +[step]; kept within ±[step] / 2, and 0 when
 * the costs do not curve upwards (flat or a maximum) or one of them is not finite.
 */
internal fun parabola(minus: Float, centre: Float, plus: Float, step: Float): Float {
    val curvature = minus - 2 * centre + plus
    val finite = minus.isFinite() && centre.isFinite() && plus.isFinite()
    if (!finite || curvature <= 0f) return 0f
    return (step * (minus - plus) / (2 * curvature)).coerceIn(-step / 2, step / 2)
}

/**
 * [maxShiftPx] and [maxScaleDelta] bound the whole-frame search (full-resolution pixels of the plane, and a
 * fraction of 1); [scaleStep] is the scale grid on the top level. [margin] leaves out a border (a fraction per side)
 * where the frames do not overlap; [sampleStep] thins the cost grid. [tileSize] and [tileSearchPx] are for
 * [TileAligner], and [minTextureRatio] is the texture below which a tile keeps the whole-frame transform. The
 * defaults are proposals until T9 and T10 of docs/test/macro-stacking-test-plan.md measure hand
 * shake and focus breathing.
 */
data class AlignOptions(
    val maxShiftPx: Int = 64,
    val maxScaleDelta: Float = 0.03f,
    val scaleStep: Float = 0.005f,
    val margin: Float = 0.1f,
    val sampleStep: Int = 2,
    val tileSize: Int = 32,
    val tileSearchPx: Int = 8,
    val minTextureRatio: Float = 0.1f,
)
