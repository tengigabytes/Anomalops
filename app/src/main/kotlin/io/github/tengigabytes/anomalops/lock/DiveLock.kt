// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.lock

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Screen states of docs/product/dive-lock-layout.md, section 2. Holding the unlock key is [HoldToUnlock]. */
sealed interface LockState {
    /** Normal camera: settings reachable, not shown over the keyguard, no FR-45 log. */
    data object Normal : LockState

    /** `startLockTask()` called; the system's pin dialog is up. */
    data object AwaitingPin : LockState

    /** Dive lock (ADR-0006). [sessionId] names the FR-45 session directory (ADR-0008). */
    data class Locked(val sessionId: String) : LockState
}

/**
 * Survives the process: a crash restart (NFR-1) reads the session back so FR-45 continues it (ADR-0008 note of
 * 2026-09-28). Writes must be on disk before they return, because the next thing may be a crash.
 */
interface LockStore {
    fun load(): String?

    fun save(sessionId: String?)
}

/**
 * Dive-lock state machine. [pinnedNow] is the system's lock-task state when the process starts: a stored session
 * with the task still pinned is a crash restart through the trampoline (ADR-0006) and resumes locked; a stored
 * session without the pin means the process ended some other way (force stop, reboot), so that session is over.
 * Pinned with nothing stored (a crash between the pin and the write) still resumes locked, in a new session:
 * under water the diver cannot pin again (G1 platform test, section 3).
 */
class DiveLock(private val store: LockStore, pinnedNow: Boolean, private val newSessionId: () -> String) {
    private val mutableState = MutableStateFlow(restore(pinnedNow))
    val state: StateFlow<LockState> = mutableState.asStateFlow()

    /** The dive-lock key in normal mode. Returns false when not in normal mode. */
    fun requestLock(): Boolean = move(LockState.Normal, LockState.AwaitingPin)

    /** The pin dialog was accepted. Starts a new FR-45 session. */
    fun pinned() {
        if (mutableState.value != LockState.AwaitingPin) return
        val id = newSessionId()
        store.save(id)
        mutableState.value = LockState.Locked(id)
    }

    /** The pin dialog was dismissed, or pinning failed (the phone was locked, G1 platform test item 5). */
    fun pinCancelled() {
        move(LockState.AwaitingPin, LockState.Normal)
    }

    /** The unlock hold completed and `stopLockTask()` was called. Ends the FR-45 session. */
    fun unlocked() {
        if (mutableState.value !is LockState.Locked) return
        store.save(null)
        mutableState.value = LockState.Normal
    }

    private fun move(from: LockState, to: LockState): Boolean {
        if (mutableState.value != from) return false
        mutableState.value = to
        return true
    }

    private fun restore(pinnedNow: Boolean): LockState {
        val stored = store.load()
        if (pinnedNow) return LockState.Locked(stored ?: newSessionId().also(store::save))
        if (stored != null) store.save(null)
        return LockState.Normal
    }
}
