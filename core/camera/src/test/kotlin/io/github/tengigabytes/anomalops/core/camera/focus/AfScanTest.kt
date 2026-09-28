// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.core.camera.focus

import android.hardware.camera2.CaptureResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AfScanTest {
    private val scan = AfScan(sentNs = ms(0))

    @Test
    fun fr35_aStaleLockBeforeTheTriggerIsIgnored() {
        assertNull(scan.result(frameNumber = 9, LOCKED, ms(30)))
        assertNull(scan.triggered(frameNumber = 10, SCANNING, ms(200)))
        assertNull(scan.result(frameNumber = 9, LOCKED, ms(210)))
        assertEquals(ScanOutcome.LOCKED, scan.result(frameNumber = 12, LOCKED, ms(260)))
        assertEquals(260.0, scan.totalMs, 0.0)
        assertEquals(60.0, scan.searchMs, 0.0)
    }

    @Test
    fun fr35_theTriggerResultItselfCanSettleTheScan() {
        assertEquals(ScanOutcome.LOCKED, scan.triggered(frameNumber = 10, LOCKED, ms(212)))
    }

    @Test
    fun fr35_notFocusedLockedFailsAtOnce() {
        scan.triggered(frameNumber = 10, SCANNING, ms(200))
        assertEquals(ScanOutcome.FAILED, scan.result(frameNumber = 11, NOT_FOCUSED, ms(240)))
    }

    @Test
    fun fr35_aSearchLongerThanHalfASecondTimesOut() {
        scan.triggered(frameNumber = 10, SCANNING, ms(200))
        assertNull(scan.result(frameNumber = 20, SCANNING, ms(700)))
        assertEquals(ScanOutcome.TIMED_OUT, scan.result(frameNumber = 21, SCANNING, ms(733)))
        assertNull("reported once", scan.result(frameNumber = 22, LOCKED, ms(766)))
        assertEquals(ScanOutcome.TIMED_OUT, scan.outcome)
    }

    @Test
    fun fr35_aMissingTriggerResultTimesOutAfterOneSecond() {
        assertNull(scan.result(frameNumber = 30, SCANNING, ms(1_000)))
        assertEquals(ScanOutcome.TIMED_OUT, scan.result(frameNumber = 31, SCANNING, ms(1_033)))
    }

    @Test
    fun aFailedTriggerCaptureFails() {
        assertEquals(ScanOutcome.FAILED, scan.failed(ms(50)))
        assertNull(scan.triggered(frameNumber = 10, LOCKED, ms(200)))
    }

    private fun ms(value: Long) = value * 1_000_000L

    private companion object {
        const val LOCKED = CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED
        const val NOT_FOCUSED = CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED
        const val SCANNING = CaptureResult.CONTROL_AF_STATE_ACTIVE_SCAN
    }
}
