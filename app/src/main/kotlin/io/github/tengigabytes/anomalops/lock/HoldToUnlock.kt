// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.lock

/** FR-51: how long the unlock key must be held. */
const val UNLOCK_HOLD_MS = 3_000L

/**
 * FR-51: leaving dive lock needs the unlock key held for [holdMs]; letting go earlier cancels. The screen feeds
 * press and release, then polls on every frame to draw [progress] and to learn when the hold is complete.
 */
class HoldToUnlock(private val holdMs: Long = UNLOCK_HOLD_MS) {
    private var downAtMs: Long? = null
    private var fired = false

    /** A second press while held (for example an NFR-6 repeat that got through) keeps the original start. */
    fun press(timeMs: Long) {
        if (downAtMs != null) return
        downAtMs = timeMs
        fired = false
    }

    fun release() {
        downAtMs = null
    }

    /** 0 when not held, rising to 1 at [holdMs]. */
    fun progress(nowMs: Long): Float {
        val start = downAtMs ?: return 0f
        return ((nowMs - start).toFloat() / holdMs).coerceIn(0f, 1f)
    }

    /** True once per hold, on the first poll at or after [holdMs]. */
    fun poll(nowMs: Long): Boolean {
        val start = downAtMs
        val done = start != null && !fired && nowMs - start >= holdMs
        if (done) fired = true
        return done
    }
}
