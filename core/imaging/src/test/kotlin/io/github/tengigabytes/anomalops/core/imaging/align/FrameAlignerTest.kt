// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.core.imaging.align

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

class FrameAlignerTest {
    private val width = 400
    private val height = 300
    private val scene = SyntheticScene(width, height)
    private val reference = scene.render(width, height)
    private val aligner = FrameAligner(reference)

    private fun assertGlobal(truth: Similarity, got: Similarity, shiftTol: Float, scaleTol: Float) {
        assertEquals("scale of $got", truth.scale, got.scale, scaleTol)
        assertEquals("dx of $got", truth.dx, got.dx, shiftTol)
        assertEquals("dy of $got", truth.dy, got.dy, shiftTol)
    }

    @Test
    fun identicalFramesAlignToIdentity() {
        val result = aligner.align(scene.render(width, height))
        assertGlobal(Similarity(), result.global, 0.05f, 5e-4f)
        assertTrue("tiles moved", result.tiles.dx.all { abs(it) < 0.1f } && result.tiles.dy.all { abs(it) < 0.1f })
    }

    @Test
    fun handShake_subPixelShift() {
        val truth = Similarity(dx = 7.3f, dy = -4.6f)
        assertGlobal(truth, aligner.align(scene.render(width, height, truth)).global, 0.1f, 5e-4f)
    }

    @Test
    fun largeShiftNearTheSearchLimit() {
        val truth = Similarity(dx = -41.7f, dy = 23.2f)
        assertGlobal(truth, aligner.align(scene.render(width, height, truth)).global, 0.15f, 1e-3f)
    }

    @Test
    fun fr33_focusBreathingScaleAndShift() {
        val truth = Similarity(scale = 1.018f, dx = 3.4f, dy = 2.1f)
        assertGlobal(truth, aligner.align(scene.render(width, height, truth)).global, 0.15f, 5e-4f)
    }

    @Test
    fun fr33_blurredFrameOfAFocusStack() {
        val truth = Similarity(scale = 0.991f, dx = -2.2f, dy = 5.6f)
        val frame = scene.render(width, height, truth, blur = 2.5f)
        assertGlobal(truth, aligner.align(frame).global, 0.25f, 1e-3f)
    }

    /** [p] plus near-Gaussian noise (a sum of 12 uniforms) of standard deviation [sigma]. */
    private fun noisy(p: Plane, sigma: Float, seed: Int): Plane {
        val r = Random(seed)
        return Plane(
            p.width,
            p.height,
            FloatArray(p.data.size) { p.data[it] + sigma * (List(12) { r.nextFloat() }.sum() - 6f) },
        )
    }

    @Test
    fun fr17_noisyStillFramesAlignToIdentity() {
        // Two exposures of a still scene differ only by noise (σ 4 on a scene spread of 17.6). Bilinear sampling
        // averages the noise down between pixels, so a cost-based sub-pixel fit came out 0.39 % of scale off here.
        val result = FrameAligner(noisy(reference, 4f, 1)).align(noisy(reference, 4f, 2))
        assertGlobal(Similarity(), result.global, 0.03f, 3e-4f)
    }

    @Test
    fun fr33_sharpFrameAgainstABlurredReference() {
        // The other way round from fr33_blurredFrameOfAFocusStack: in a bracket the reference is the blurred one
        // wherever another frame is in focus. A cost-based fit came out 0.11-0.13 % of scale off for these two.
        val blurredReference = FrameAligner(scene.render(width, height, blur = 2f))
        assertGlobal(Similarity(), blurredReference.align(reference).global, 0.03f, 3e-4f)
        val truth = Similarity(dx = 3f, dy = -2f)
        assertGlobal(truth, blurredReference.align(scene.render(width, height, truth)).global, 0.03f, 3e-4f)
    }

    @Test
    fun fr17_movingSubjectShowsOnlyInItsTiles() {
        val truth = Similarity(dx = 5f, dy = -3f)
        // Reference tiles (5..7, 3..5) of 32 pixels, carried to the frame by the global shift, with 4 pixels to spare.
        val moving = Region(5 * 32 + 5 - 4, 3 * 32 - 3 - 4, 8 * 32 + 5 + 4, 6 * 32 - 3 + 4)
        val frame = scene.render(width, height, truth, local = moving to (3f to -2f))
        val result = aligner.align(frame)
        // The least-squares global fit is pulled a little toward the moving patch; the tiles take up the rest,
        // so each tile's whole shift (global plus residual) is what must match.
        assertGlobal(truth, result.global, 0.5f, 1e-3f)
        val tiles = result.tiles
        for (row in 0 until tiles.rows) {
            for (col in 0 until tiles.cols) {
                val i = tiles.index(col, row)
                val totalDx = result.global.dx + tiles.dx[i]
                val totalDy = result.global.dy + tiles.dy[i]
                if (col in 5..7 && row in 3..5) {
                    assertTrue("tile $col,$row untrusted", tiles.trusted[i])
                    assertEquals("tile $col,$row dx", 5f + 3f, totalDx, 0.2f)
                    assertEquals("tile $col,$row dy", -3f - 2f, totalDy, 0.2f)
                } else if (isStill(col, row, tiles) && tiles.trusted[i]) {
                    assertEquals("tile $col,$row dx", 5f, totalDx, 0.2f)
                    assertEquals("tile $col,$row dy", -3f, totalDy, 0.2f)
                }
            }
        }
    }

    /** Away from the frame edge and from the moving patch, with a tile of margin around it. */
    private fun isStill(col: Int, row: Int, tiles: TileField) =
        col in 1 until tiles.cols - 1 && row in 1 until tiles.rows - 1 && !(col in 4..8 && row in 2..6)

    @Test
    fun warpUndoesTheWholeFrameTransform() {
        val truth = Similarity(scale = 1.01f, dx = 6.5f, dy = -2.5f)
        val frame = scene.render(width, height, truth)
        val warped = aligner.warp(frame, truth)
        // Bilinear resampling cannot follow the sharpest blobs exactly, so judge the mean and bound the worst.
        var worst = 0f
        var sum = 0.0
        var n = 0
        for (y in 20 until height - 20) {
            for (x in 20 until width - 20) {
                val d = abs(warped[x, y] - reference[x, y])
                worst = maxOf(worst, d)
                sum += d
                n++
            }
        }
        assertTrue("warp differs by ${sum / n} on average", sum / n < 0.3)
        assertTrue("warp differs by $worst at worst", worst < 8f)
    }
}
