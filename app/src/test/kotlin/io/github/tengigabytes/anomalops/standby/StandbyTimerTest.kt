// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.standby

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** FR-57: 60 s without input enters standby, a touch leaves it, and a running capture holds it off. */
class StandbyTimerTest {
    private var now = 0L
    private val timer = StandbyTimer(nowMs = { now })

    @Test
    fun fr57_entersStandbyAfterSixtySecondsIdle() {
        now = 59_999
        assertFalse(timer.poll())
        assertEquals(1L, timer.msUntilStandby())
        now = 60_000
        assertTrue(timer.poll())
        assertNull(timer.msUntilStandby())
    }

    @Test
    fun fr57_aTouchRestartsTheIdleTime() {
        now = 50_000
        assertFalse(timer.onTouch())
        now = 100_000
        assertFalse(timer.poll())
        now = 110_000
        assertTrue(timer.poll())
    }

    @Test
    fun fr57_theWakingTouchIsSwallowedAndTheNextOneActs() {
        now = 60_000
        timer.poll()
        assertTrue(timer.onTouch())
        assertFalse(timer.isStandby)
        assertFalse(timer.onTouch())
    }

    @Test
    fun fr57_wakingTouchCanBePassedOn() {
        val passing = StandbyTimer(nowMs = { now }, swallowWakeTouch = false)
        now = 60_000
        passing.poll()
        assertFalse(passing.onTouch())
        assertFalse(passing.isStandby)
    }

    @Test
    fun fr57_noStandbyDuringACapture() {
        timer.onBusy(true)
        now = 600_000
        assertFalse(timer.poll())
        assertNull(timer.msUntilStandby())
        timer.onBusy(false)
        now = 659_999
        assertFalse(timer.poll())
        now = 660_000
        assertTrue(timer.poll())
    }

    @Test
    fun fr57_overlappingCapturesHoldUntilTheLastEnds() {
        timer.onBusy(true)
        timer.onBusy(true)
        timer.onBusy(false)
        now = 120_000
        assertFalse(timer.poll())
        timer.onBusy(false)
        timer.onBusy(false)
        now = 180_000
        assertTrue(timer.poll())
    }

    @Test
    fun fr57_standbyBrightnessNeverExceedsTheActiveOne() {
        assertEquals(0.3f, StandbyTimer.standbyBrightness(1f))
        assertEquals(0.217f, StandbyTimer.standbyBrightness(0.217f))
    }
}
