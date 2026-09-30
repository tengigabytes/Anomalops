// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.lock

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log

private const val POLL_MS = 250L

/** With no dialog and no pin after this long, pinning did not start (for example the phone was locked). */
private const val NO_DIALOG_TIMEOUT_MS = 5_000L

/** How often the pin is checked while locked. */
private const val PIN_CHECK_MS = 1_000L

private const val TAG = "AnomalopsLock"

/**
 * Turns the system's pin state into [DiveLock] events. G1 platform test, item 1: `startLockTask()` shows "App is
 * pinned" and the task is pinned only after "Got it"; item 5: on a locked phone nothing happens at all.
 *
 * The dialog takes window focus. When focus comes back, the task is either pinned (accepted) or not (dismissed).
 * A poll catches the pin in case focus never moves. While locked, a second poll notices when the system ends the
 * pin and reports [DiveLock.pinLost].
 */
class PinWatcher(private val activity: Activity, private val lock: DiveLock) {
    private val ui = Handler(Looper.getMainLooper())
    private var sawDialog = false
    private var startedAtMs = 0L

    // UNVERIFIED(G1): that swipe up and hold ends the pin with the process alive, so this poll sees it within 1 s.
    private val pinCheck = object : Runnable {
        override fun run() {
            if (lock.state.value !is LockState.Locked) return
            if (activity.isPinned()) {
                ui.postDelayed(this, PIN_CHECK_MS)
            } else {
                Log.w(TAG, "pin ended by the system while locked; ending the dive lock")
                lock.pinLost()
            }
        }
    }

    private val poll = object : Runnable {
        override fun run() {
            if (lock.state.value != LockState.AwaitingPin) return
            val waited = SystemClock.elapsedRealtime() - startedAtMs
            when {
                activity.isPinned() -> lock.pinned()
                !sawDialog && waited > NO_DIALOG_TIMEOUT_MS -> lock.pinCancelled()
                else -> ui.postDelayed(this, POLL_MS)
            }
        }
    }

    /** The dive-lock key in normal mode. */
    fun start() {
        if (!lock.requestLock()) return
        sawDialog = false
        startedAtMs = SystemClock.elapsedRealtime()
        activity.startLockTask()
        ui.postDelayed(poll, POLL_MS)
    }

    // UNVERIFIED(G1): that the pin dialog takes window focus and gives it back when it closes.
    fun onWindowFocusChanged(hasFocus: Boolean) {
        if (lock.state.value != LockState.AwaitingPin) return
        if (!hasFocus) {
            sawDialog = true
        } else if (sawDialog) {
            ui.removeCallbacks(poll)
            if (activity.isPinned()) lock.pinned() else lock.pinCancelled()
        }
    }

    /** Every [LockState] as it changes: checks the pin once a second while locked. */
    fun follow(state: LockState) {
        ui.removeCallbacks(pinCheck)
        if (state is LockState.Locked) ui.postDelayed(pinCheck, PIN_CHECK_MS)
    }

    fun stop() {
        ui.removeCallbacks(poll)
        ui.removeCallbacks(pinCheck)
    }
}
