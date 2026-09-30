// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.core.store.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** FR-67: shots (and video minutes) left from free space and the sizes actually written. */
class CapacityTest {
    @Test
    fun fr67_fallsBackBeforeTheFirstShot() {
        assertEquals(1_590_000L, ShotSizes(fallbackBytes = 1_590_000).bytesPerShot)
    }

    @Test
    fun fr67_meanOfTheLastShotsWithKeptRaws() {
        val sizes = ShotSizes(fallbackBytes = 1, window = 3)
        sizes.onShot(100)
        sizes.onShot(200)
        sizes.onExtra(600)
        assertEquals(450L, sizes.bytesPerShot)
        sizes.onShot(300)
        assertEquals(400L, sizes.bytesPerShot)
        // The fourth shot pushes out the first; the extra after the second stays with it.
        sizes.onShot(400)
        assertEquals(500L, sizes.bytesPerShot)
        // The fifth pushes out the second and its extra.
        sizes.onShot(500)
        assertEquals(400L, sizes.bytesPerShot)
    }

    @Test
    fun fr67_extraBeforeAnyShotIsIgnored() {
        val sizes = ShotSizes(fallbackBytes = 7)
        sizes.onExtra(1_000)
        assertEquals(7L, sizes.bytesPerShot)
        sizes.onShot(10)
        assertEquals(10L, sizes.bytesPerShot)
    }

    @Test
    fun fr67_capacityKeepsTheSystemReserve() {
        val capacity = Capacity.of(freeBytes = 10_500, reserveBytes = 500, bytesPerShot = 100)
        assertEquals(100L, capacity.shots)
        assertNull(capacity.videoMinutes)
        assertEquals(0L, Capacity.of(freeBytes = 400, reserveBytes = 500, bytesPerShot = 100).shots)
    }

    @Test
    fun fr67_videoMinutesFromTheBitrate() {
        val capacity = Capacity.of(freeBytes = 60_000, reserveBytes = 0, bytesPerShot = 100, videoBytesPerSecond = 100)
        assertEquals(10L, capacity.videoMinutes)
    }
}
