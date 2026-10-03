// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.core.imaging.develop

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs
import kotlin.math.sqrt

class ChromaDenoiseTest {
    private fun argb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    private fun red(p: Int) = p shr 16 and 0xFF

    private fun green(p: Int) = p shr 8 and 0xFF

    private fun blue(p: Int) = p and 0xFF

    private fun luma(p: Int) = (54 * red(p) + 183 * green(p) + 19 * blue(p)) / 256.0

    private fun deviation(values: List<Double>): Double {
        val mean = values.average()
        return sqrt(values.sumOf { (it - mean) * (it - mean) } / values.size)
    }

    @Test
    fun flatAndGreyPicturesPassUnchanged() {
        val flat = IntArray(40 * 30) { argb(200, 120, 40) }
        assertArrayEquals(flat, ChromaDenoise.apply(flat, 40, 30, 3))
        val random = Random(1)
        val grey = IntArray(40 * 30) { random.nextInt(256).let { v -> argb(v, v, v) } }
        assertArrayEquals(grey, ChromaDenoise.apply(grey, 40, 30, 3))
        assertArrayEquals(flat, ChromaDenoise.apply(flat, 40, 30, 0))
    }

    @Test
    fun colourNoiseGoesAndLumaStays() {
        // Mid grey with independent noise of ±12 codes on red and blue: colour noise, little luma noise.
        val random = Random(2)
        val noisy = IntArray(96 * 96) { argb(120 + random.nextInt(25) - 12, 120, 120 + random.nextInt(25) - 12) }
        val clean = ChromaDenoise.apply(noisy, 96, 96, 3)
        fun colour(p: Int) = red(p) - luma(p)
        val before = deviation(noisy.map { colour(it) })
        val after = deviation(clean.map { colour(it) })
        assertTrue("colour noise $before -> $after", after < before / 5)
        // Each pass rounds every channel to a whole code, so luma can drift by a code or two, no more.
        val moved = noisy.indices.maxOf { abs(luma(noisy[it]) - luma(clean[it])) }
        assertTrue("luma moved by $moved", moved <= 2.0)
        assertEquals(0xFF, clean[0] ushr 24)
    }

    @Test
    fun colourDoesNotBleedAcrossALumaEdge() {
        // Dark blue beside light orange: about 128 codes of luma apart, so each side ignores the other.
        val dark = argb(20, 30, 90)
        val light = argb(230, 150, 60)
        val picture = IntArray(64 * 16) { if (it % 64 < 32) dark else light }
        val out = ChromaDenoise.apply(picture, 64, 16, 3)
        for (x in intArrayOf(0, 30, 31, 32, 33, 63)) {
            val expected = if (x < 32) dark else light
            val p = out[8 * 64 + x]
            val differences = listOf(red(p) - red(expected), green(p) - green(expected), blue(p) - blue(expected))
            assertTrue("x=$x differs by $differences", differences.all { abs(it) <= 1 })
        }
    }

    @Test
    fun weightsAndSpacingAreAsDocumented() {
        assertEquals(255, ChromaDenoise.rangeWeights[0])
        // 255 exp(-10² / (2 · 10.2²)) = 157.6.
        assertEquals(158, ChromaDenoise.rangeWeights[10])
        assertEquals(0, ChromaDenoise.rangeWeights[60])
        assertEquals(16, ChromaDenoise.spatialWeights[12])
        assertEquals(listOf(2, 4, 8), (0 until 3).map { ChromaDenoise.spacing(it) })
    }

    @Test
    fun renderDenoisesBeforeItSharpens() {
        val random = Random(3)
        val noisy = IntArray(32 * 32) { argb(100 + random.nextInt(31), 110, 100 + random.nextInt(31)) }
        val options = RenderOptions(chromaPasses = 2, sharpen = 0.5f)
        val expected = Sharpen.apply(ChromaDenoise.apply(noisy, 32, 32, 2), 32, 32, 0.5f)
        assertArrayEquals(expected, Render.finish(noisy, 32, 32, options))
        assertArrayEquals(noisy, Render.finish(noisy, 32, 32, RenderOptions()))
    }
}
