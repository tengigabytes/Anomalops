// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.standby

import kotlin.math.min

/**
 * FR-57: in-app standby. After [idleMs] without a touch, and while no capture is running, the app lowers the
 * preview to [STANDBY_FPS] and the screen to [STANDBY_BRIGHTNESS]; any touch brings it back. The OS never sleeps
 * the screen (ADR-0006), so this is the only power saving while dive lock is on.
 *
 * The touch that wakes is swallowed when [swallowWakeTouch] is set, so a stray press on a dark screen does not
 * fire the shutter. Whether the waking touch should also act is open (docs/product/requirements/09-open-items.md).
 * Not thread-safe: call from the main thread.
 */
class StandbyTimer(
    private val nowMs: () -> Long,
    private val idleMs: Long = IDLE_MS,
    private val swallowWakeTouch: Boolean = true,
) {
    private var lastInputMs = nowMs()
    private var busyCount = 0

    var isStandby: Boolean = false
        private set

    /** A touch anywhere. Returns true when it only woke the app and must not reach the control under it. */
    fun onTouch(): Boolean {
        lastInputMs = nowMs()
        val woke = isStandby
        isStandby = false
        return woke && swallowWakeTouch
    }

    /** A capture or burst started ([busy] true) or ended; standby never starts in between. */
    fun onBusy(busy: Boolean) {
        busyCount = if (busy) busyCount + 1 else maxOf(0, busyCount - 1)
        lastInputMs = nowMs()
        if (busy) isStandby = false
    }

    /** Enters standby once the idle time has passed; returns [isStandby]. Call on a timer, e.g. every second. */
    fun poll(): Boolean {
        if (!isStandby && busyCount == 0 && nowMs() - lastInputMs >= idleMs) isStandby = true
        return isStandby
    }

    /** Milliseconds until [poll] would enter standby; null while in standby or busy. */
    fun msUntilStandby(): Long? = if (isStandby || busyCount > 0) null else maxOf(0L, idleMs - (nowMs() - lastInputMs))

    companion object {
        const val IDLE_MS = 60_000L
        const val STANDBY_FPS = 10

        /** FR-57's 30 %, read as the window brightness (0–1). */
        const val STANDBY_BRIGHTNESS = 0.3f

        /**
         * The window brightness for standby: never brighter than it was, since dive lock's full brightness may
         * already be capped lower by the system's thermal limit (docs/test/m3-instrumented.md: 0.217 at MODERATE).
         */
        fun standbyBrightness(activeBrightness: Float): Float = min(activeBrightness, STANDBY_BRIGHTNESS)
    }
}
