// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.touch

/** NFR-6: a repeat counts only within this time of the accepted press it repeats. */
const val DEBOUNCE_WINDOW_MS = 200L

/** NFR-6 (mvp-acceptance.md): a repeat counts only within this radius of the accepted press it repeats. */
const val DEBOUNCE_RADIUS_DP = 48f

/** One finger-down event: position in pixels, time in milliseconds on one monotonic clock. */
data class TouchDown(val x: Float, val y: Float, val timeMs: Long)

/** The outcome for one [TouchDown]; [REPEAT] is written to `touches.csv` as filtered (ADR-0008). */
enum class TouchVerdict { ACCEPTED, REPEAT }

/**
 * NFR-6: water pressure on the housing's gel membrane can fire the same spot several times. A press within
 * [radiusPx] of an accepted press and less than [windowMs] after it is the same press again and is dropped.
 *
 * The window runs from the accepted press, not from the latest repeat, so a spot that keeps firing is accepted
 * again once per window rather than silenced for good.
 */
class TouchDebouncer(private val radiusPx: Float, private val windowMs: Long = DEBOUNCE_WINDOW_MS) {
    private val accepted = ArrayDeque<TouchDown>()

    fun check(down: TouchDown): TouchVerdict {
        while (accepted.isNotEmpty() && down.timeMs - accepted.first().timeMs >= windowMs) accepted.removeFirst()
        if (accepted.any { it.isNear(down) }) return TouchVerdict.REPEAT
        accepted.addLast(down)
        return TouchVerdict.ACCEPTED
    }

    private fun TouchDown.isNear(other: TouchDown): Boolean {
        val dx = x - other.x
        val dy = y - other.y
        return dx * dx + dy * dy <= radiusPx * radiusPx
    }
}
