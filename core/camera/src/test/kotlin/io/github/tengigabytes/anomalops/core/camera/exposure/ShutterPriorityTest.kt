// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.core.camera.exposure

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShutterPriorityTest {
    @Test
    fun adr0009_meteredExposureWithinLimitIsKept() {
        val still = convert(Exposure(timeNs = MS * 4, iso = 200))
        assertEquals(Exposure(MS * 4, 200), still.exposure)
        assertFalse(still.isoClamped)
    }

    @Test
    fun adr0009_longExposureIsCappedAndIsoRaisedToKeepExposureValue() {
        // 1/30 s at ISO 100 with a 1/125 s limit: ISO x 4.1667.
        val still = convert(Exposure(timeNs = SECOND / 30, iso = 100))
        assertEquals(SECOND / 125, still.exposure.timeNs)
        assertEquals(417, still.exposure.iso)
        assertFalse(still.isoClamped)
    }

    @Test
    fun adr0009_isoIsClampedToSensorRangeAndFlagged() {
        val still = convert(Exposure(timeNs = SECOND / 15, iso = 2000))
        assertEquals(SECOND / 125, still.exposure.timeNs)
        assertEquals(ISO_RANGE.last, still.exposure.iso)
        assertTrue(still.isoClamped)
    }

    @Test
    fun adr0009_frameDurationCoversExposureAndStreamMinimum() {
        assertEquals(MIN_FRAME_NS, convert(Exposure(MS, 100)).frameDurationNs)
        val longLimit = ShutterPriority.convert(Exposure(SECOND / 10, 100), SECOND / 20, ISO_RANGE, MIN_FRAME_NS)
        assertEquals(SECOND / 20, longLimit.frameDurationNs)
    }

    @Test(expected = IllegalArgumentException::class)
    fun zeroMeteredExposureIsRejected() {
        convert(Exposure(timeNs = 0, iso = 100))
    }

    private fun convert(metered: Exposure) = ShutterPriority.convert(metered, SECOND / 125, ISO_RANGE, MIN_FRAME_NS)

    private companion object {
        const val SECOND = 1_000_000_000L
        const val MS = 1_000_000L
        const val MIN_FRAME_NS = 33_333_333L
        val ISO_RANGE = 21..5333
    }
}
