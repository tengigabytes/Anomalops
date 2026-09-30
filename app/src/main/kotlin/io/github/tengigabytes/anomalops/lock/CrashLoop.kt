// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.lock

/** A crash restart in dive lock: the FR-45 session it continued and when, in `SystemClock.elapsedRealtime`. */
data class Restart(val sessionId: String, val atMs: Long)

/** Where [CrashRestarter] keeps the last [Restart]; it must outlive the crashed process. */
interface RestartLog {
    fun lastRestart(): Restart?

    fun recordRestart(restart: Restart)
}

/**
 * Maintainer decision of 2026-09-30 (docs/product/requirements/09-open-items.md, ADR-0006 note): a crash in dive
 * lock restarts the app (NFR-1), but a crash within [WINDOW_MS] of a restart in the same session does not. A crash
 * loop is almost certainly a bug; without the restart the task empties and the system ends the pin.
 */
object CrashLoop {
    const val WINDOW_MS = 60_000L

    /**
     * The window runs from the crash that was restarted; the restart itself takes under 1 s (G1: 568–699 ms).
     * `elapsedRealtime` starts again at boot, so a [last] later than [nowMs] is from before a reboot and does not
     * count.
     */
    fun shouldRestart(sessionId: String, nowMs: Long, last: Restart?): Boolean =
        last == null || last.sessionId != sessionId || nowMs - last.atMs !in 0 until WINDOW_MS
}
