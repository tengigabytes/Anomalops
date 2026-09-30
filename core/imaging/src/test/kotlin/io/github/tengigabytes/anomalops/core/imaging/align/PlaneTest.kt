// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.core.imaging.align

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaneTest {
    @Test
    fun bayerCellsAreSummedAboveBlack() {
        // 4 x 2 RAW, row stride 6 (padding), black 10: two cells.
        val raw = shortArrayOf(
            20, 30, 40, 50, 0, 0,
            60, 70, 80, 90, 0, 0,
        )
        val plane = Plane.fromBayer(raw, width = 4, height = 2, rowStride = 6, blackLevel = 10)
        assertEquals(2, plane.width)
        assertEquals(1, plane.height)
        assertEquals(20f + 30 + 60 + 70 - 40, plane[0, 0], 0f)
        assertEquals(40f + 50 + 80 + 90 - 40, plane[1, 0], 0f)
    }

    @Test
    fun bayerReadsSamplesAsUnsigned() {
        val raw = shortArrayOf(-1, -1, -1, -1)
        assertEquals(4f * 0xFFFF, Plane.fromBayer(raw, 2, 2, 2, 0)[0, 0], 0f)
    }

    @Test
    fun bilinearSampleAndOutside() {
        val plane = Plane(2, 2, floatArrayOf(0f, 10f, 20f, 30f))
        assertEquals(15f, plane.sample(0.5f, 0.5f), 1e-6f)
        assertEquals(30f, plane.sample(1f, 1f), 1e-6f)
        assertTrue(plane.sample(-0.1f, 0f).isNaN())
        assertTrue(plane.sample(0f, 1.01f).isNaN())
    }

    @Test
    fun halfAveragesBlocksAndDropsTheOddEdge() {
        val plane = Plane(3, 2, floatArrayOf(1f, 3f, 99f, 5f, 7f, 99f))
        val half = plane.half()
        assertEquals(1, half.width)
        assertEquals(1, half.height)
        assertEquals(4f, half[0, 0], 0f)
    }

    @Test
    fun levelTransformMatchesFullResolution() {
        // A point on level 2 maps like its full-resolution position does.
        val s = Similarity(scale = 1.02f, dx = 8f, dy = -4f)
        val full = 400 to 300
        val level = s.atLevel(2)
        val x2 = 37f
        val x0 = (x2 + 0.5f) * 4 - 0.5f
        val expected = (s.mapX(x0, full.first) + 0.5f) / 4 - 0.5f
        assertEquals(expected, level.mapX(x2, full.first), 1e-4f)
    }

    @Test
    fun parabolaFindsTheMinimumBetweenSamples() {
        // Costs of (x - 0.3)^2 at -1, 0, 1.
        assertEquals(0.3f, parabola(1.69f, 0.09f, 0.49f, 1f), 1e-5f)
        assertEquals(0f, parabola(1f, 2f, 1f, 1f), 0f)
        assertEquals(0f, parabola(Float.POSITIVE_INFINITY, 0f, 1f, 1f), 0f)
    }
}
