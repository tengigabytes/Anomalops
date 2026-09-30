// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.core.store.cull

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** FR-69: sharpness and clipping metrics, and the per-stack verdict. */
class StackCullTest {
    private fun image(width: Int, height: Int, value: (Int, Int) -> Int) =
        Luma(width, height, IntArray(width * height) { value(it % width, it / width) })

    private val checker = image(16, 16) { x, y -> if ((x + y) % 2 == 0) 200 else 50 }
    private val flat = image(16, 16) { _, _ -> 128 }

    /** The checkerboard averaged over 2 × 2 blocks: the same scene, less detail. */
    private val soft = image(16, 16) { x, y -> if ((x / 2 + y / 2) % 2 == 0) 160 else 90 }

    @Test
    fun fr69_laplacianVarianceRanksDetail() {
        assertEquals(0.0, FrameMetrics.laplacianVariance(flat), 0.0)
        // Every interior Laplacian of the checkerboard is ±600, so the variance is 600².
        assertEquals(360_000.0, FrameMetrics.laplacianVariance(checker), 1e-6)
        assertTrue(FrameMetrics.laplacianVariance(soft) < FrameMetrics.laplacianVariance(checker))
        assertEquals(0.0, FrameMetrics.laplacianVariance(image(2, 5) { x, _ -> x * 255 }), 0.0)
    }

    @Test
    fun fr69_clippingShares() {
        val half = image(4, 1) { x, _ -> if (x < 2) 0 else 255 }
        val metrics = FrameMetrics.of(half)
        assertEquals(0.5, metrics.darkClip, 0.0)
        assertEquals(0.5, metrics.brightClip, 0.0)
        val edges = FrameMetrics.of(image(4, 1) { x, _ -> intArrayOf(2, 3, 252, 253)[x] })
        assertEquals(0.25, edges.darkClip, 0.0)
        assertEquals(0.25, edges.brightClip, 0.0)
    }

    @Test
    fun fr69_marksBlurredFramesRelativeToTheStack() {
        val verdicts = StackCull.judge(listOf(FrameMetrics(100.0, 0.0, 0.0), FrameMetrics(49.0, 0.0, 0.0)))
        assertEquals(emptySet<FailReason>(), verdicts[0].reasons)
        assertEquals(setOf(FailReason.BLURRED), verdicts[1].reasons)
        assertTrue(verdicts[0].score > verdicts[1].score)
    }

    @Test
    fun fr69_marksClippedFramesAndLowersTheirScore() {
        val verdicts = StackCull.judge(
            listOf(FrameMetrics(100.0, 0.0, 0.0), FrameMetrics(100.0, 0.6, 0.0), FrameMetrics(100.0, 0.0, 0.06)),
        )
        assertEquals(setOf(FailReason.CLIPPED_DARK), verdicts[1].reasons)
        assertEquals(setOf(FailReason.CLIPPED_BRIGHT), verdicts[2].reasons)
        assertEquals(1.0, verdicts[0].score, 1e-9)
        assertEquals(0.4, verdicts[1].score, 1e-9)
        assertEquals(0.94, verdicts[2].score, 1e-9)
    }

    @Test
    fun fr69_aStackWithoutDetailIsNotCalledBlurred() {
        val verdicts = StackCull.judge(listOf(FrameMetrics(0.0, 0.0, 0.0), FrameMetrics(0.0, 0.0, 0.0)))
        assertTrue(verdicts.none { it.failed })
        assertTrue(StackCull.judge(emptyList()).isEmpty())
    }
}
