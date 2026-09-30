// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.lock

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashLoopTest {
    private val session = "DIVE_20260930_101500"

    @Test
    fun firstCrashOfASessionRestarts() {
        assertTrue(CrashLoop.shouldRestart(session, nowMs = 5_000_000, last = null))
    }

    @Test
    fun crashWithinTheWindowOfARestartDoesNotRestart() {
        val last = Restart(session, atMs = 5_000_000)
        assertFalse(CrashLoop.shouldRestart(session, nowMs = 5_000_000, last = last))
        assertFalse(CrashLoop.shouldRestart(session, nowMs = 5_003_000, last = last))
        assertFalse(CrashLoop.shouldRestart(session, nowMs = 5_000_000 + CrashLoop.WINDOW_MS - 1, last = last))
    }

    @Test
    fun crashAfterTheWindowRestartsAgain() {
        val last = Restart(session, atMs = 5_000_000)
        assertTrue(CrashLoop.shouldRestart(session, nowMs = 5_000_000 + CrashLoop.WINDOW_MS, last = last))
    }

    @Test
    fun restartOfAnEarlierSessionDoesNotCount() {
        val last = Restart("DIVE_20260930_094500", atMs = 5_000_000)
        assertTrue(CrashLoop.shouldRestart(session, nowMs = 5_003_000, last = last))
    }

    @Test
    fun restartFromBeforeARebootDoesNotCount() {
        val last = Restart(session, atMs = 5_000_000)
        assertTrue(CrashLoop.shouldRestart(session, nowMs = 20_000, last = last))
    }
}
