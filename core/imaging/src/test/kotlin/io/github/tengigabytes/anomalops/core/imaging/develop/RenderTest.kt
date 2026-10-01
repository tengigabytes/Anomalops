// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.core.imaging.develop

import io.github.tengigabytes.anomalops.core.imaging.align.Plane
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class RenderTest {
    private val identity = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
    private val unity = floatArrayOf(1f, 1f, 1f)

    private fun flat(width: Int, height: Int, r: Float, g: Float, b: Float) =
        Rgb(Plane(width, height).fill(r), Plane(width, height).fill(g), Plane(width, height).fill(b))

    private fun Plane.fill(value: Float) = apply { data.fill(value) }

    private fun channels(argb: Int) = Triple(argb shr 16 and 0xFF, argb shr 8 and 0xFF, argb and 0xFF)

    @Test
    fun srgbEncodingMatchesTheStandard() {
        assertEquals(0, Render.encode(0f))
        assertEquals(255, Render.encode(1f))
        // 0.18 linear is 0.4614 encoded, code 117.7.
        assertEquals(118, Render.encode(0.18f))
        // The linear segment: 0.002 * 12.92 * 255 = 6.6.
        assertEquals(7, Render.encode(0.002f))
        assertEquals(255, Render.encode(3f))
        assertEquals(0, Render.encode(-1f))
    }

    @Test
    fun toneCurveIsIdentityThenASmoothShoulderToWhite() {
        val s = 0.9f
        val w = 2f
        assertEquals(0.5f, Render.tone(0.5f, s, w), 0f)
        assertEquals(1f, Render.tone(w, s, w), 1e-6f)
        assertEquals(1f, Render.tone(5f, s, w), 0f)
        val slope = (Render.tone(s + 1e-3f, s, w) - Render.tone(s, s, w)) / 1e-3f
        assertEquals("slope at the shoulder", 1f, slope, 0.01f)
        var last = 0f
        var x = 0f
        while (x <= w) {
            val y = Render.tone(x, s, w)
            assertTrue("monotonic at $x", y >= last)
            assertTrue("at most 1 at $x", y <= 1f)
            last = y
            x += 0.01f
        }
    }

    @Test
    fun midtonesPassThroughWithGainsAndMatrix() {
        val swap = floatArrayOf(0f, 0f, 1f, 0f, 1f, 0f, 1f, 0f, 0f)
        val argb = Render.toArgb(flat(2, 2, 0.1f, 0.2f, 0.3f), floatArrayOf(2f, 1f, 1f), swap)
        val (r, g, b) = channels(argb[0])
        // Gains make (0.2, 0.2, 0.3); the matrix swaps red and blue.
        assertEquals(Render.encode(0.3f), r)
        assertEquals(Render.encode(0.2f), g)
        assertEquals(Render.encode(0.2f), b)
        assertEquals(0xFF, argb[0] ushr 24)
    }

    @Test
    fun clippedHighlightsTurnNeutralNotPink() {
        // Every channel at RAW white; red and blue gains alone would make it pink.
        val argb = Render.toArgb(flat(1, 1, 1f, 1f, 1f), floatArrayOf(2f, 1f, 1.5f), identity)
        val (r, g, b) = channels(argb[0])
        assertEquals(255, r)
        assertEquals(r, g)
        assertEquals(r, b)
        // Well below the knee the same gains keep their colour.
        val (r2, g2, b2) = channels(
            Render.toArgb(flat(1, 1, 0.1f, 0.1f, 0.1f), floatArrayOf(2f, 1f, 1.5f), identity)[0],
        )
        assertTrue("$r2 $g2 $b2", r2 > b2 && b2 > g2)
    }

    @Test
    fun theShoulderKeepsTheHue() {
        val o = FloatArray(3)
        Render.pixel(floatArrayOf(0.8f, 0.4f, 0.2f), unity, floatArrayOf(2f, 1f, 1f), identity, RenderOptions(), o)
        // 1.6 : 0.4 : 0.2 scaled down together.
        assertEquals(Render.tone(1.6f, 0.9f, 2f), o[0], 1e-6f)
        assertEquals(4f, o[0] / o[1], 1e-4f)
        assertEquals(2f, o[1] / o[2], 1e-4f)
    }

    @Test
    fun negativeMatrixResultsClampToBlack() {
        val o = FloatArray(3)
        val mix = floatArrayOf(1f, -1f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        Render.pixel(floatArrayOf(0.1f, 0.3f, 0.2f), unity, unity, mix, RenderOptions(), o)
        assertEquals(0f, o[0], 0f)
        assertEquals(0.3f, o[1], 1e-6f)
    }

    @Test
    fun exposureScalesBeforeTheCurve() {
        val o = FloatArray(3)
        Render.pixel(floatArrayOf(0.1f, 0.1f, 0.1f), unity, unity, identity, RenderOptions(exposure = 4f), o)
        assertEquals(0.4f, o[1], 1e-6f)
    }

    @Test
    fun shadingMapIsBilinearWithEdgesOnTheImageEdges() {
        // 3 x 2 grid; each point's gains: red = 1 + column, greens = 1 + row (even) and 3 + row (odd), blue = 2.
        val gains = FloatArray(4 * 3 * 2)
        for (row in 0 until 2) {
            for (col in 0 until 3) {
                val k = (row * 3 + col) * 4
                gains[k] = 1f + col
                gains[k + 1] = 1f + row
                gains[k + 2] = 3f + row
                gains[k + 3] = 2f
            }
        }
        val map = ShadingMap(3, 2, gains)
        val g = FloatArray(3)
        map.gainsAt(0f, 0f, 101, 51, g)
        assertEquals(1f, g[0], 1e-6f)
        assertEquals(2f, g[1], 1e-6f)
        assertEquals(2f, g[2], 1e-6f)
        map.gainsAt(100f, 50f, 101, 51, g)
        assertEquals(3f, g[0], 1e-6f)
        assertEquals(3f, g[1], 1e-6f)
        // A quarter of the way across and halfway down.
        map.gainsAt(25f, 25f, 101, 51, g)
        assertEquals(1.5f, g[0], 1e-5f)
        assertEquals(2.5f, g[1], 1e-5f)
        map.gainsAt(-10f, 80f, 101, 51, g)
        assertEquals(1f, g[0], 1e-6f)
        assertEquals(3f, g[1], 1e-6f)
    }

    @Test
    fun shadingFlattensAVignetteBeforeTheClipCheck() {
        // A RAW 4 x 2 frame made half size (2 x 1); the right half is vignetted to 1/2 and its map gain is 2.
        val map = ShadingMap(2, 2, floatArrayOf(1f, 1f, 1f, 1f, 2f, 2f, 2f, 2f, 1f, 1f, 1f, 1f, 2f, 2f, 2f, 2f))
        val rgb = Rgb(
            Plane(2, 1, floatArrayOf(0.2f, 0.1f)),
            Plane(2, 1, floatArrayOf(0.2f, 0.1f)),
            Plane(2, 1, floatArrayOf(0.2f, 0.1f)),
        )
        val argb = Render.toArgb(rgb, unity, identity, shading = map, rawWidth = 4, rawHeight = 2)
        // Pixel centres sit at RAW x = 0.5 and 2.5 of 0..3: gains 1 + 0.5/3 and 1 + 2.5/3.
        val left = Render.encode(0.2f * (1 + 0.5f / 3))
        val right = Render.encode(0.1f * (1 + 2.5f / 3))
        assertEquals(left, channels(argb[0]).second)
        assertEquals(right, channels(argb[1]).second)
        // A corner clipped on the RAW stays neutral even though shading lifts it to 2 x white.
        val clipped = Render.toArgb(
            flat(2, 1, 1f, 1f, 1f),
            floatArrayOf(1.8f, 1f, 1.4f),
            identity,
            shading = map,
            rawWidth = 4,
            rawHeight = 2,
        )
        val (r, g, b) = channels(clipped[1])
        assertTrue("$r $g $b", abs(r - g) <= 1 && abs(b - g) <= 1)
    }

    @Test
    fun endToEndGreyRawRendersAsTheExpectedCode() {
        val black = 64f
        val white = 1023f
        val level = 0.18f
        val samples = ShortArray(16) { (black + level * (white - black) + 0.5f).toInt().toShort() }
        val raw = RawFrame(samples, 4, 4, 4, CfaLayout.GBRG, FloatArray(4) { black }, white)
        val argb = Render.toArgb(Demosaic.halfSize(raw), unity, identity)
        assertEquals(Render.encode(level), channels(argb[3]).first)
        assertEquals(118, channels(argb[3]).second)
    }
}
