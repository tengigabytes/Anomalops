// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.core.imaging.stack

import io.github.tengigabytes.anomalops.core.imaging.align.Plane
import io.github.tengigabytes.anomalops.core.imaging.align.SyntheticScene
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * A slanted subject: depth grows from left to right, and frame k of the bracket is in focus at the k-th fifth of
 * the width, blurred elsewhere in proportion to the depth difference. The sharp scene is the ground truth.
 */
class FocusStackTest {
    private val width = 240
    private val height = 160
    private val frames = 5
    private val scene = SyntheticScene(width, height, blobs = 500, seed = 7)
    private val truth = Plane(
        width,
        height,
    ).also { p -> for (y in 0 until height) for (x in 0 until width) p[x, y] = scene.at(x.toFloat(), y.toFloat()) }
    private val bracket = List(frames) { k ->
        Plane(width, height).also { p ->
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val depth = (x + 0.5f) / width * frames - 0.5f
                    p[x, y] = scene.at(x.toFloat(), y.toFloat(), blur = BLUR_PER_STEP * abs(depth - k))
                }
            }
        }
    }
    private val candidates = listOf(ContrastSelectStack(), LaplacianPyramidStack(), GuidedWeightStack())

    private fun rmse(a: Plane, b: Plane): Double {
        var sum = 0.0
        var n = 0
        for (y in 8 until height - 8) {
            for (x in 8 until width - 8) {
                val d = a[x, y] - b[x, y]
                sum += d * d
                n++
            }
        }
        return sqrt(sum / n)
    }

    @Test
    fun fr33_everyCandidateBeatsTheBestSingleFrame() {
        val bestSingle = bracket.minOf { rmse(it, truth) }
        val errors = candidates.associate { it.name to rmse(it.merge(bracket).single(), truth) }
        errors.forEach { (name, error) ->
            println(
                "$name: rmse %.2f against best single %.2f".format(error, bestSingle),
            )
        }
        errors.forEach { (name, error) ->
            assertTrue("$name: rmse $error, best single $bestSingle", error < MAX_ERROR_RATIO * bestSingle)
        }
    }

    @Test
    fun fr33_noisyBracketStillBeatsTheBestSingleFrame() {
        // Sensor noise (σ = 3 on a floor of 100) is detail to a contrast measure; every candidate must still win.
        val random = Random(11)
        val noisy = bracket.map { f -> Filters.map(f) { it + NOISE * random.nextGaussian().toFloat() } }
        val bestSingle = noisy.minOf { rmse(it, truth) }
        val errors = candidates.associate { it.name to rmse(it.merge(noisy).single(), truth) }
        errors.forEach { (name, error) ->
            println(
                "noisy $name: rmse %.2f against best single %.2f".format(error, bestSingle),
            )
        }
        errors.forEach { (name, error) ->
            assertTrue("noisy $name: rmse $error, best single $bestSingle", error < NOISY_MAX_ERROR_RATIO * bestSingle)
        }
    }

    @Test
    fun identicalFramesComeBackUnchanged() {
        val same = List(3) { truth }
        candidates.forEach { stack ->
            val merged = stack.merge(same).single()
            val worst = merged.data.indices.maxOf { abs(merged.data[it] - truth.data[it]) }
            assertTrue("${stack.name} changed an identical stack by $worst", worst < 1e-2f)
        }
    }

    @Test
    fun colourChannelsFollowTheLumaDecision() {
        // A channel that is an affine function of luma must come out as the same function of the merged luma.
        val channels = bracket.map { luma -> listOf(luma, Filters.map(luma) { 2 * it + 10 }) }
        candidates.forEach { stack ->
            val (l, c) = stack.merge(bracket, channels)
            val worst = l.data.indices.maxOf { abs(c.data[it] - (2 * l.data[it] + 10)) }
            assertTrue("${stack.name} channel drifted by $worst", worst < 1e-2f)
        }
    }

    @Test
    fun guardKeepsAGoodMerge() {
        val result = StackGuard(GuidedWeightStack()).merge(bracket, bracket.map { listOf(it) })
        assertTrue("merge ${result.sharpness} against best single ${result.bestSingle}", result.merged)
    }

    @Test
    fun guardFallsBackToTheSharpestFrame() {
        // A merge that only averages loses detail; the guard must return the sharpest frame instead.
        val averaging = object : FocusStack {
            override val name = "mean"

            override fun merge(luma: List<Plane>, channels: List<List<Plane>>) = channels[0].indices.map { c ->
                Plane(
                    width,
                    height,
                    FloatArray(width * height) { i -> channels.map { it[c].data[i] }.average().toFloat() },
                )
            }
        }
        val sharpFirst = listOf(truth) + bracket.map { Filters.box(it, 3) }
        val result = StackGuard(averaging).merge(sharpFirst, sharpFirst.map { listOf(it) })
        assertFalse(result.merged)
        assertEquals(0, result.bestFrame)
        assertTrue(result.channels.single() === sharpFirst[0] || rmse(result.channels.single(), truth) < 1e-3)
    }

    @Test
    fun missingPixelsAreFilledFromTheReference() {
        val holey = Plane(2, 1, floatArrayOf(Float.NaN, 5f))
        val reference = Plane(2, 1, floatArrayOf(1f, 2f))
        val filled = StackGuard.fillMissing(listOf(reference, holey), reference = 0)[1]
        assertEquals(1f, filled[0, 0], 0f)
        assertEquals(5f, filled[1, 0], 0f)
    }

    private companion object {
        /** Blur in pixels per fifth of the depth range away from focus. */
        const val BLUR_PER_STEP = 2.5f

        /** Proposed: a merge must at least halve the error of the best single frame on this scene. */
        const val MAX_ERROR_RATIO = 0.5
        const val NOISE = 3f

        /** Proposed: with noise the gain is smaller; a merge must still cut the error by a quarter. */
        const val NOISY_MAX_ERROR_RATIO = 0.75
    }
}
