// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.lock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiveLockTest {
    private class MemoryStore(var sessionId: String? = null) : LockStore {
        override fun load(): String? = sessionId

        override fun save(sessionId: String?) {
            this.sessionId = sessionId
        }
    }

    private var ids = 0
    private fun newId() = "DIVE_${++ids}"

    @Test
    fun normalToLockedAndBack() {
        val store = MemoryStore()
        val lock = DiveLock(store, pinnedNow = false, ::newId)
        assertEquals(LockState.Normal, lock.state.value)
        assertTrue(lock.requestLock())
        assertEquals(LockState.AwaitingPin, lock.state.value)
        lock.pinned()
        assertEquals(LockState.Locked("DIVE_1"), lock.state.value)
        assertEquals("DIVE_1", store.sessionId)
        lock.unlocked()
        assertEquals(LockState.Normal, lock.state.value)
        assertNull(store.sessionId)
    }

    @Test
    fun pinEndedByTheSystemEndsTheSession() {
        val store = MemoryStore()
        val lock = DiveLock(store, pinnedNow = false, ::newId)
        lock.requestLock()
        lock.pinned()
        lock.pinLost()
        assertEquals(LockState.Normal, lock.state.value)
        assertNull(store.sessionId)
    }

    @Test
    fun pinLostOutsideDiveLockIsIgnored() {
        val lock = DiveLock(MemoryStore(), pinnedNow = false, ::newId)
        lock.requestLock()
        lock.pinLost()
        assertEquals(LockState.AwaitingPin, lock.state.value)
    }

    @Test
    fun cancelledPinStaysNormalWithoutASession() {
        val store = MemoryStore()
        val lock = DiveLock(store, pinnedNow = false, ::newId)
        lock.requestLock()
        lock.pinCancelled()
        assertEquals(LockState.Normal, lock.state.value)
        assertNull(store.sessionId)
        assertEquals(0, ids)
    }

    @Test
    fun eventsOutOfOrderAreIgnored() {
        val lock = DiveLock(MemoryStore(), pinnedNow = false, ::newId)
        lock.pinned()
        lock.unlocked()
        assertEquals(LockState.Normal, lock.state.value)
        lock.requestLock()
        assertFalse(lock.requestLock())
        lock.pinned()
        lock.pinCancelled()
        assertEquals(LockState.Locked("DIVE_1"), lock.state.value)
    }

    @Test
    fun nfr1_crashRestartWhilePinnedResumesTheSameSession() {
        val store = MemoryStore("DIVE_20260928_101500")
        val lock = DiveLock(store, pinnedNow = true, ::newId)
        assertEquals(LockState.Locked("DIVE_20260928_101500"), lock.state.value)
        assertEquals(0, ids)
    }

    @Test
    fun storedSessionWithoutPinIsOver() {
        val store = MemoryStore("DIVE_20260928_101500")
        val lock = DiveLock(store, pinnedNow = false, ::newId)
        assertEquals(LockState.Normal, lock.state.value)
        assertNull(store.sessionId)
    }

    @Test
    fun pinnedWithNothingStoredResumesLockedInANewSession() {
        val store = MemoryStore()
        val lock = DiveLock(store, pinnedNow = true, ::newId)
        assertEquals(LockState.Locked("DIVE_1"), lock.state.value)
        assertEquals("DIVE_1", store.sessionId)
    }
}
