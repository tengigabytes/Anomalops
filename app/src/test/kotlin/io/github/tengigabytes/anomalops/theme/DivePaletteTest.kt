// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DivePaletteTest {
    @Test
    fun nfr5_everyPairReaches7To1() {
        val weak = DivePalette.pairs.filter { (fg, bg) -> contrastRatio(fg, bg) < MIN_CONTRAST }
        assertEquals(emptyList<Pair<Int, Int>>(), weak)
    }

    @Test
    fun nfr5_noStateColourIsBlue() {
        assertTrue(DivePalette.stateColors.none(::isBlue))
    }

    @Test
    fun contrastMatchesTheWcagReferenceValues() {
        assertEquals(21.0, contrastRatio(0xFFFFFF, 0x000000), 0.01)
        assertEquals(1.0, contrastRatio(0x777777, 0x777777), 0.01)
        assertEquals(13.65, contrastRatio(0x000000, 0xFFC933), 0.01)
        assertEquals(9.19, contrastRatio(0xFF8A7F, 0x000000), 0.01)
    }

    @Test
    fun blueDetection() {
        assertTrue(isBlue(0x0000FF))
        assertTrue(isBlue(0x2196F3)) // Material blue 500, hue about 207°
        assertFalse(isBlue(0x00FFFF)) // cyan, 180°
        assertFalse(isBlue(0xB8B8B8)) // grey
        assertFalse(isBlue(0xFFC933))
        assertFalse(isBlue(0xFF8A7F))
    }
}
