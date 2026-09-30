// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.core.imaging.align

import kotlin.math.ceil

/**
 * Whole-frame alignment: the scale and shift that best map the reference onto the frame (ADR-0015, shared by
 * FR-17 and FR-33). Coarse to fine on both pyramids: an exhaustive search over shift and scale on the top level,
 * then ±1 pixel and a halved scale step on each level below, then sub-pixel refinement on level 0 by fitting a
 * parabola through the costs on each side. A least-squares fit: a subject moving on its own pulls it a little,
 * and [TileAligner] takes up the difference tile by tile. Cost is the mean squared difference (see [meanSquaredDiff]).
 */
class GlobalAligner(private val options: AlignOptions = AlignOptions()) {
    fun align(reference: Pyramid, frame: Pyramid): Similarity {
        require(reference.levels.size == frame.levels.size) { "pyramids of different depth" }
        val full = reference[0].width to reference[0].height
        val top = reference.top
        var best = searchTop(reference, frame, full)
        var scaleStep = options.scaleStep
        for (level in top - 1 downTo 0) {
            scaleStep /= 2
            best = searchAround(reference, frame, level, full, best, scaleStep)
        }
        return refine(reference[0], frame[0], full, best, scaleStep)
    }

    private fun searchTop(reference: Pyramid, frame: Pyramid, full: Pair<Int, Int>): Similarity {
        val level = reference.top
        val factor = 1 shl level
        val radius = ceil(options.maxShiftPx.toFloat() / factor).toInt()
        val scaleCount = ceil(options.maxScaleDelta / options.scaleStep).toInt()
        var best = Similarity()
        var bestCost = Float.POSITIVE_INFINITY
        for (k in -scaleCount..scaleCount) {
            val scale = 1f + k * options.scaleStep
            for (dy in -radius..radius) {
                for (dx in -radius..radius) {
                    val candidate = Similarity(scale, (dx * factor).toFloat(), (dy * factor).toFloat())
                    val cost = cost(reference, frame, level, full, candidate)
                    if (cost < bestCost) {
                        bestCost = cost
                        best = candidate
                    }
                }
            }
        }
        return best
    }

    private fun searchAround(
        reference: Pyramid,
        frame: Pyramid,
        level: Int,
        full: Pair<Int, Int>,
        start: Similarity,
        scaleStep: Float,
    ): Similarity {
        val factor = (1 shl level).toFloat()
        var best = start
        var bestCost = cost(reference, frame, level, full, start)
        for (ds in -1..1) {
            for (dy in -1..1) {
                for (dx in -1..1) {
                    val candidate = Similarity(
                        start.scale + ds * scaleStep,
                        start.dx + dx * factor,
                        start.dy + dy * factor,
                    )
                    val cost = cost(reference, frame, level, full, candidate)
                    if (cost < bestCost) {
                        bestCost = cost
                        best = candidate
                    }
                }
            }
        }
        return best
    }

    /**
     * Sub-pixel shift and finer scale on level 0: for each parameter a parabola through its neighbours, starting one
     * pixel (and the last scale step) apart and halving each round.
     */
    private fun refine(
        reference: Plane,
        frame: Plane,
        full: Pair<Int, Int>,
        start: Similarity,
        scaleStep: Float,
    ): Similarity {
        val region = Region.inner(reference.width, reference.height, options.margin)
        val cost = { s: Similarity ->
            meanSquaredDiff(
                reference,
                frame,
                s.atLevel(0),
                region,
                options.sampleStep,
                full,
            )
        }
        var best = start
        var shiftStep = 1f
        var sStep = scaleStep
        repeat(REFINE_ROUNDS) {
            best = best.nudge(cost, shiftStep) { s, d -> s.copy(dx = s.dx + d) }
            best = best.nudge(cost, shiftStep) { s, d -> s.copy(dy = s.dy + d) }
            best = best.nudge(cost, sStep) { s, d -> s.copy(scale = s.scale + d) }
            shiftStep /= 2
            sStep /= 2
        }
        return best
    }

    private fun Similarity.nudge(
        cost: (Similarity) -> Float,
        step: Float,
        move: (Similarity, Float) -> Similarity,
    ): Similarity = move(this, parabola(cost(move(this, -step)), cost(this), cost(move(this, step)), step))

    private fun cost(reference: Pyramid, frame: Pyramid, level: Int, full: Pair<Int, Int>, s: Similarity): Float {
        val ref = reference[level]
        val region = Region.inner(ref.width, ref.height, options.margin)
        return meanSquaredDiff(ref, frame[level], s.atLevel(level), region, options.sampleStep, full)
    }

    private companion object {
        const val REFINE_ROUNDS = 3
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
