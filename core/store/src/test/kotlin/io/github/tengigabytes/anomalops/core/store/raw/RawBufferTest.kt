// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.core.store.raw

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** FR-62 rules (docs/product/mvp-acceptance.md): 5 frames, 10 s, the 6th pushes out the 1st. */
class RawBufferTest {
    private var now = 0L
    private val released = mutableListOf<String>()
    private val buffer = RawBuffer<String>(capacity = 5, ttlMs = 10_000, nowMs = { now }, release = { released += it })

    @Test
    fun fr62_sixthFramePushesOutTheFirst() {
        (1..6).forEach { buffer.offer("s$it", "raw$it") }
        assertEquals(listOf("raw1"), released)
        assertEquals((2..6).map { "s$it" }, buffer.keys)
        assertNull(buffer.take("s1"))
    }

    @Test
    fun fr62_takeWithinTenSecondsHandsTheFrameOver() {
        buffer.offer("s1", "raw1")
        now = 9_999
        assertEquals("raw1", buffer.take("s1"))
        assertEquals(emptyList<String>(), released)
        assertNull(buffer.take("s1"))
    }

    @Test
    fun fr62_framesExpireAfterTenSeconds() {
        buffer.offer("s1", "raw1")
        now = 5_000
        buffer.offer("s2", "raw2")
        now = 10_000
        assertNull(buffer.take("s1"))
        assertEquals(listOf("raw1"), released)
        now = 15_000
        buffer.sweep()
        assertEquals(listOf("raw1", "raw2"), released)
        assertEquals(emptyList<String>(), buffer.keys)
    }

    @Test
    fun clearReleasesEverything() {
        (1..3).forEach { buffer.offer("s$it", "raw$it") }
        buffer.clear()
        assertEquals(listOf("raw1", "raw2", "raw3"), released)
    }
}
