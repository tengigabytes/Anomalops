// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.core.imaging.develop

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs

class SaturationTest {
    private fun argb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    private fun luma(p: Int) = (54 * (p shr 16 and 0xFF) + 183 * (p shr 8 and 0xFF) + 19 * (p and 0xFF)) / 256.0

    @Test
    fun aFactorOfOneAndGreyPixelsPassUnchanged() {
        val random = Random(4)
        val picture = IntArray(500) { argb(random.nextInt(256), random.nextInt(256), random.nextInt(256)) }
        assertArrayEquals(picture, Saturation.apply(picture, 1f))
        assertFalse(Saturation.changes(1f))
        assertTrue(Saturation.changes(1.25f))
        val grey = IntArray(256) { argb(it, it, it) }
        assertArrayEquals(grey, Saturation.apply(grey, 1.25f))
        assertArrayEquals(grey, Saturation.apply(grey, 0.5f))
    }

    @Test
    fun aPixelMovesAwayFromItsLumaByTheFactor() {
        // (200, 100, 50) has luma 117.38; times 1.25 from there: 220.66, 95.66, 33.15.
        assertEquals(argb(221, 96, 33), Saturation.apply(intArrayOf(argb(200, 100, 50)), 1.25f)[0])
        // A factor of 0 leaves the luma alone in every channel.
        assertEquals(argb(117, 117, 117), Saturation.apply(intArrayOf(argb(200, 100, 50)), 0f)[0])
    }

    @Test
    fun lumaStaysWhereNothingClips() {
        val random = Random(5)
        val picture = IntArray(2000) {
            argb(60 + random.nextInt(120), 60 + random.nextInt(120), 60 + random.nextInt(120))
        }
        val out = Saturation.apply(picture, 1.25f)
        val moved = picture.indices.maxOf { abs(luma(picture[it]) - luma(out[it])) }
        assertTrue("luma moved by $moved", moved <= 0.5)
    }

    @Test
    fun strongColoursClipWithoutWrapping() {
        val out = Saturation.apply(intArrayOf(argb(255, 0, 0), argb(0, 255, 255)), 2f)
        assertEquals(argb(255, 0, 0), out[0])
        assertEquals(argb(0, 255, 255), out[1])
    }
}
