// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.core.imaging.align

import kotlin.math.abs

/**
 * Sub-pixel refinement of a whole-frame [Similarity] on level 0 by Gauss-Newton on scale and shift, the Jacobian
 * taken from the mean of the reference's and the warped frame's gradients (efficient second-order minimisation).
 *
 * Why not a parabola through sampled costs: bilinear sampling at a fractional position averages neighbouring
 * pixels, so it lowers the frame's noise and blurs it. When the frame is noisier or sharper than the reference
 * (two noisy frames of a still scene; any frame of a focus bracket where the reference is out of focus), the mean
 * squared difference dips either side of the true position and peaks on it, and a cost-based fit lands in one of
 * the dips: 0.4-0.5 % of scale and up to 0.19 pixel off on synthetic scenes (2026-10-01). Here the residual's part
 * from that smoothing is even about the true position while the gradients are odd, so they cancel in the normal
 * equations and the fixed point stays on the truth.
 *
 * Each round solves the 3 x 3 normal equations and limits the step to [maxShiftStep] pixels and [maxScaleStep]; it
 * stops after [rounds] or once a step is below [SHIFT_DONE] pixels and [SCALE_DONE]. The result is not judged by the
 * mean squared difference, the very thing that is biased here (with heavy noise the dip is a third of the cost);
 * the coarse search has already found the basin, so a result more than [maxDrift] pixels or [maxScaleDrift] from
 * the start counts as a run away and the start is kept.
 *
 * The rounds are shared by every backend (ADR-0017): the normal equations come from [GradientSums] on the CPU and
 * from a kernel of `:core:gpu` on the GPU.
 */
class GaussNewton(
    private val rounds: Int = DEFAULT_ROUNDS,
    private val maxShiftStep: Float = 1f,
    private val maxScaleStep: Float = DEFAULT_MAX_SCALE_STEP,
    private val maxDrift: Float = DEFAULT_MAX_DRIFT,
    private val maxScaleDrift: Float = DEFAULT_MAX_SCALE_DRIFT,
) {
    /** [start] refined; [equations] gives the normal equations at a transform. */
    fun refine(start: Similarity, equations: (Similarity) -> NormalEquations): Similarity {
        var s = start
        var round = 0
        var moving = true
        while (moving && round++ < rounds) {
            val step = step(equations(s))
            moving = step != null && !settled(step)
            if (step != null) s = Similarity(s.scale + step[0], s.dx + step[1], s.dy + step[2])
        }
        return if (drifted(start, s)) start else s
    }

    /** Whether a (scale, dx, dy) [step] is too small to go on. */
    private fun settled(step: FloatArray): Boolean =
        abs(step[0]) < SCALE_DONE && abs(step[1]) < SHIFT_DONE && abs(step[2]) < SHIFT_DONE

    /** Whether [s] ran away from [start] (or is not a number). */
    private fun drifted(start: Similarity, s: Similarity): Boolean {
        val scaleOk = abs(s.scale - start.scale) <= maxScaleDrift
        return !(scaleOk && abs(s.dx - start.dx) <= maxDrift && abs(s.dy - start.dy) <= maxDrift)
    }

    /** The clamped Gauss-Newton step (scale, dx, dy) solving [e]; null when the equations are degenerate. */
    private fun step(e: NormalEquations): FloatArray? {
        val d = solve3(e.a, e.b) ?: return null
        return floatArrayOf(
            d[0].toFloat().coerceIn(-maxScaleStep, maxScaleStep),
            d[1].toFloat().coerceIn(-maxShiftStep, maxShiftStep),
            d[2].toFloat().coerceIn(-maxShiftStep, maxShiftStep),
        )
    }

    companion object {
        const val DEFAULT_ROUNDS = 8
        const val DEFAULT_MAX_SCALE_STEP = 0.005f
        private const val SHIFT_DONE = 1e-3f
        private const val SCALE_DONE = 1e-6f
        private const val SINGULAR = 1e-12

        /** Proposed: two pixels, beyond the ±1 pixel the last coarse level leaves open. */
        const val DEFAULT_MAX_DRIFT = 2f

        /**
         * Proposed: two of [AlignOptions.scaleStep]'s default 0.005. The bias also reaches the coarse levels, which on
         * small planes (256 pixels wide) were seen to land 0.006 off.
         */
        const val DEFAULT_MAX_SCALE_DRIFT = 0.01f

        /** [a] (3 x 3, row by row) x = [b] by Cramer's rule; null when [a] is (nearly) singular. */
        fun solve3(a: DoubleArray, b: DoubleArray): DoubleArray? {
            fun e(m: DoubleArray, r: Int, c: Int) = m[r * PARAMS + c]
            fun minor(m: DoubleArray, c0: Int, c1: Int) = e(m, 1, c0) * e(m, 2, c1) - e(m, 1, c1) * e(m, 2, c0)
            fun det(m: DoubleArray) = e(
                m,
                0,
                0,
            ) * minor(m, 1, 2) - e(m, 0, 1) * minor(m, 0, 2) + e(m, 0, 2) * minor(m, 0, 1)
            val d = det(a)
            val scale = e(a, 0, 0) * e(a, 1, 1) * e(a, 2, 2)
            if (d == 0.0 || abs(d) <= SINGULAR * abs(scale)) return null
            return DoubleArray(PARAMS) { col ->
                det(DoubleArray(PARAMS * PARAMS) { i -> if (i % PARAMS == col) b[i / PARAMS] else a[i] }) / d
            }
        }
    }
}

/** One round's normal equations a x = b in (scale, dx, dy): [a] is 3 x 3 row by row; both summed in double. */
class NormalEquations(val a: DoubleArray = DoubleArray(PARAMS * PARAMS), val b: DoubleArray = DoubleArray(PARAMS))

/**
 * [GaussNewton]'s normal equations on the CPU: one term per [reference] pixel on the grid of [region] with spacing
 * [sampleStep], where the frame has the pixel and its four neighbours. Public as the reference the GPU kernel is
 * checked against.
 */
class GradientSums(private val reference: Plane, private val region: Region, private val sampleStep: Int) {
    init {
        require(region.left >= 1 && region.top >= 1) { "region $region touches the plane's edge" }
        require(region.right < reference.width && region.bottom < reference.height) { "region $region at the edge" }
    }

    fun at(frame: Plane, s: Similarity): NormalEquations {
        val e = NormalEquations()
        for (y in region.top until region.bottom step sampleStep) {
            for (x in region.left until region.right step sampleStep) accumulate(frame, s, x, y, e.a, e.b)
        }
        return e
    }

    /** Adds reference pixel ([x], [y]) to the normal equations [a] x = [b], if the frame has it and its neighbours. */
    @Suppress("LongParameterList") // One pixel's term of the running sums.
    private fun accumulate(frame: Plane, s: Similarity, x: Int, y: Int, a: DoubleArray, b: DoubleArray) {
        val fx = s.mapX(x.toFloat(), reference.width)
        val fy = s.mapY(y.toFloat(), reference.height)
        val f = frame.sample(fx, fy)
        val fxGrad = (frame.sample(fx + 1, fy) - frame.sample(fx - 1, fy)) / 2
        val fyGrad = (frame.sample(fx, fy + 1) - frame.sample(fx, fy - 1)) / 2
        if ((f + fxGrad + fyGrad).isNaN()) return
        // The reference's gradient is per reference pixel; per frame pixel it is divided by the scale.
        val gx = (fxGrad + (reference[x + 1, y] - reference[x - 1, y]) / (2 * s.scale)) / 2.0
        val gy = (fyGrad + (reference[x, y + 1] - reference[x, y - 1]) / (2 * s.scale)) / 2.0
        val j0 = gx * (x - Similarity.centre(reference.width)) + gy * (y - Similarity.centre(reference.height))
        val j = doubleArrayOf(j0, gx, gy)
        val r = (f - reference[x, y]).toDouble()
        for (p in 0 until PARAMS) {
            b[p] -= j[p] * r
            for (q in 0 until PARAMS) a[p * PARAMS + q] += j[p] * j[q]
        }
    }
}

private const val PARAMS = 3
