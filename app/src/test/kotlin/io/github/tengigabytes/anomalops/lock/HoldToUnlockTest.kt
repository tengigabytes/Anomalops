// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.lock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HoldToUnlockTest {
    @Test
    fun fr51_releasedAt2900MsNeverUnlocks() {
        val hold = HoldToUnlock()
        hold.press(1_000)
        assertFalse(hold.poll(3_900))
        hold.release()
        assertFalse(hold.poll(10_000))
        assertEquals(0f, hold.progress(10_000))
    }

    @Test
    fun fr51_heldFor3SecondsUnlocksExactlyOnce() {
        val hold = HoldToUnlock()
        hold.press(1_000)
        assertFalse(hold.poll(3_999))
        assertTrue(hold.poll(4_000))
        assertFalse(hold.poll(4_016))
        assertFalse(hold.poll(9_000))
    }

    @Test
    fun fr51_progressRisesToOne() {
        val hold = HoldToUnlock()
        hold.press(0)
        val progress = listOf(0L, 750L, 1_500L, 3_000L, 4_000L).map(hold::progress)
        assertEquals(listOf(0f, 0.25f, 0.5f, 1f, 1f), progress)
    }

    @Test
    fun fr51_aRepeatedPressDoesNotRestartTheHold() {
        val hold = HoldToUnlock()
        hold.press(0)
        hold.press(1_500)
        assertTrue(hold.poll(3_000))
    }

    @Test
    fun fr51_aNewHoldAfterReleaseStartsOver() {
        val hold = HoldToUnlock()
        hold.press(0)
        assertTrue(hold.poll(3_000))
        hold.release()
        hold.press(5_000)
        assertFalse(hold.poll(7_000))
        assertTrue(hold.poll(8_000))
    }
}
