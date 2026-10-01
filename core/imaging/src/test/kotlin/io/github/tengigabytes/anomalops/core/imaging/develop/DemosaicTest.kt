// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.core.imaging.develop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sin

class DemosaicTest {
    private val black = 64f
    private val white = 1023f

    /** A RAW frame sampling the colour function [rgb] (linear, 0..1) through [layout]. */
    private fun mosaic(width: Int, height: Int, layout: CfaLayout, rgb: (Int, Int) -> FloatArray): RawFrame {
        val samples = ShortArray(width * height) { i ->
            val x = i % width
            val y = i / width
            (black + rgb(x, y)[layout.colourAt(x, y)] * (white - black)).toInt().toShort()
        }
        return RawFrame(samples, width, height, width, layout, FloatArray(4) { black }, white)
    }

    @Test
    fun layoutsPlaceTheirColours() {
        assertEquals(CfaLayout.RED, CfaLayout.RGGB.colourAt(0, 0))
        assertEquals(CfaLayout.BLUE, CfaLayout.RGGB.colourAt(1, 1))
        assertEquals(CfaLayout.GREEN, CfaLayout.GBRG.colourAt(0, 0))
        assertEquals(CfaLayout.BLUE, CfaLayout.GBRG.colourAt(1, 0))
        assertEquals(CfaLayout.RED, CfaLayout.GBRG.colourAt(0, 1))
        assertEquals(CfaLayout.BLUE, CfaLayout.BGGR.colourAt(2, 2))
        assertEquals(CfaLayout.GBRG, CfaLayout.fromCamera2(2))
        assertNull(CfaLayout.fromCamera2(4))
    }

    @Test
    fun flatColourComesBackInEveryLayout() {
        CfaLayout.entries.forEach { layout ->
            val raw = mosaic(8, 6, layout) { _, _ -> floatArrayOf(0.5f, 0.25f, 0.75f) }
            listOf(Demosaic.halfSize(raw), Demosaic.bilinear(raw)).forEach { rgb ->
                assertEquals("$layout red", 0.5f, rgb.red[1, 1], 2e-3f)
                assertEquals("$layout green", 0.25f, rgb.green[1, 1], 2e-3f)
                assertEquals("$layout blue", 0.75f, rgb.blue[1, 1], 2e-3f)
            }
        }
    }

    @Test
    fun blackLevelPerCellPositionIsRemoved() {
        val samples = shortArrayOf(100, 200, 300, 400)
        val raw = RawFrame(samples, 2, 2, 2, CfaLayout.RGGB, floatArrayOf(0f, 100f, 200f, 300f), 1100f)
        // Every photosite is 100 above its own black level, so each reads 100 / (1100 - black).
        assertEquals(100f / 1100f, raw.linear(0, 0), 1e-6f)
        assertEquals(100f / 800f, raw.linear(1, 1), 1e-6f)
    }

    @Test
    fun bilinearFollowsASmoothColourField() {
        val field = { x: Int, y: Int -> floatArrayOf(0.5f + 0.3f * sin(x / 7f), 0.4f + 0.2f * sin(y / 5f), 0.3f) }
        val raw = mosaic(64, 48, CfaLayout.GBRG, field)
        val rgb = Demosaic.bilinear(raw)
        var worst = 0f
        for (y in 2 until 46) {
            for (x in 2 until 62) {
                val want = field(x, y)
                worst = maxOf(worst, abs(rgb.red[x, y] - want[0]), abs(rgb.green[x, y] - want[1]))
            }
        }
        assertEquals("worst error $worst", 0f, worst, 0.02f)
    }

    @Test
    fun whiteBalanceAndMatrixAreAppliedInOrder() {
        val rgb = Demosaic.halfSize(mosaic(2, 2, CfaLayout.RGGB) { _, _ -> floatArrayOf(0.2f, 0.4f, 0.1f) })
        // Gains double red; the matrix swaps red and blue and keeps green.
        val out = ColourPipeline.apply(rgb, floatArrayOf(2f, 1f, 1f), floatArrayOf(0f, 0f, 1f, 0f, 1f, 0f, 1f, 0f, 0f))
        assertEquals(0.1f, out.red[0, 0], 2e-3f)
        assertEquals(0.4f, out.green[0, 0], 2e-3f)
        assertEquals(0.4f, out.blue[0, 0], 2e-3f)
    }

    @Test
    fun lumaUsesRec709Weights() {
        val rgb = Demosaic.halfSize(mosaic(2, 2, CfaLayout.RGGB) { _, _ -> floatArrayOf(1f, 0f, 0f) })
        assertEquals(0.2126f, rgb.luma()[0, 0], 2e-3f)
    }
}
