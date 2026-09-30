// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.core.camera.whitebalance

import io.github.tengigabytes.anomalops.core.profile.ScenePreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/** FR-22: gains from a RAW gray patch, and four named slots chosen per preset. */
class CustomWhiteBalanceTest {
    private val identity = listOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)

    /** A 4 × 4 RGGB frame: R 164, greens 264, B 114, black 64 everywhere → 100 / 200 / 50 above black. */
    private fun rggb(red: Int = 164, green: Int = 264, blue: Int = 114): ShortArray = ShortArray(16) { i ->
        val x = i % 4
        val y = i / 4
        when {
            y % 2 == 0 && x % 2 == 0 -> red
            y % 2 == 1 && x % 2 == 1 -> blue
            else -> green
        }.toShort()
    }

    private val black = doubleArrayOf(64.0, 64.0, 64.0, 64.0)

    @Test
    fun fr22_patchMeansPerBayerChannel() {
        val means = GrayPatch.means(rggb(), rowStride = 4, Cfa.RGGB, black, whiteLevel = 1023, PatchRect(0, 0, 4, 4))
        assertEquals(ChannelMeans(100.0, 200.0, 200.0, 50.0, 0.0), means)
    }

    @Test
    fun fr22_channelLayoutFollowsTheCfa() {
        val cell = listOf(0 to 0, 1 to 0, 0 to 1, 1 to 1)
        // GBRG: row 0 is G B, row 1 is R G.
        assertEquals(listOf(1, 3, 0, 2), cell.map { (x, y) -> GrayPatch.channelAt(Cfa.GBRG, x, y) })
        assertEquals(listOf(3, 1, 2, 0), cell.map { (x, y) -> GrayPatch.channelAt(Cfa.BGGR, x, y) })
    }

    @Test
    fun fr22_gainsMakeThePatchNeutral() {
        val measurement = CustomWhiteBalance.measure(ChannelMeans(100.0, 200.0, 200.0, 50.0, 0.0))
        assertEquals(Measurement.Gains(listOf(2.0, 1.0, 1.0, 4.0)), measurement)
    }

    @Test
    fun fr22_refusesClippedAndDarkPatches() {
        val clipped = GrayPatch.means(rggb(green = 1023), 4, Cfa.RGGB, black, 1023, PatchRect(0, 0, 4, 4))
        assertEquals(0.5, clipped.clippedShare, 0.0)
        assertEquals(Measurement.Refused(PatchProblem.CLIPPED), CustomWhiteBalance.measure(clipped))
        val dark = ChannelMeans(10.0, 20.0, 20.0, 5.0, 0.0)
        assertEquals(Measurement.Refused(PatchProblem.TOO_DARK), CustomWhiteBalance.measure(dark))
        val noRed = ChannelMeans(0.0, 200.0, 200.0, 50.0, 0.0)
        assertEquals(Measurement.Refused(PatchProblem.TOO_DARK), CustomWhiteBalance.measure(noRed))
    }

    @Test
    fun fr22_slotsFollowThePreset() {
        val slots = WbSlots()
        slots.save(0, WbSlot("Wreck", listOf(3.0, 1.0, 1.0, 1.5), identity))
        slots.save(3, WbSlot("Reef", listOf(2.0, 1.0, 1.0, 1.8), identity))
        slots.select(ScenePreset.WIDE, 0)
        slots.select(ScenePreset.MACRO, 3)
        assertEquals("Wreck", slots.activeFor(ScenePreset.WIDE)?.name)
        assertEquals("Reef", slots.activeFor(ScenePreset.MACRO)?.name)
        assertNull(slots.activeFor(ScenePreset.SNAPSHOT))
        slots.rename(3, "Reef 12 m")
        assertEquals("Reef 12 m", slots.activeFor(ScenePreset.MACRO)?.name)
        slots.clear(0)
        assertNull(slots.activeFor(ScenePreset.WIDE))
        slots.select(ScenePreset.MACRO, null)
        assertNull(slots.activeFor(ScenePreset.MACRO))
    }

    @Test
    fun fr22_slotRules() {
        val slots = WbSlots()
        assertThrows(IllegalArgumentException::class.java) { slots.slot(4) }
        assertThrows(IllegalStateException::class.java) { slots.select(ScenePreset.WIDE, 1) }
        assertThrows(IllegalArgumentException::class.java) { WbSlot("x", listOf(1.0, 1.0, 1.0, 0.0), identity) }
    }
}
