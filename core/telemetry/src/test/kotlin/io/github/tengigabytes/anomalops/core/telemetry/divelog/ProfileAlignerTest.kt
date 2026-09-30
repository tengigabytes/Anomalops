// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.core.telemetry.divelog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Duration
import java.time.LocalDateTime

/** FR-44: alignment within 5 s, with the clock offset corrected by hand. */
class ProfileAlignerTest {
    private val start = LocalDateTime.of(2026, 9, 28, 10, 0)
    private val dive = DiveProfile(
        start,
        listOf(
            ProfilePoint(0.0, 0.0, 28.0),
            ProfilePoint(20.0, 4.0, null),
            ProfilePoint(40.0, 8.0, 26.0),
            ProfilePoint(200.0, 12.0, null),
        ),
    )
    private val aligner = ProfileAligner(listOf(dive))

    @Test
    fun fr44_interpolatesDepthAndHoldsTheLastTemperature() {
        val reading = aligner.at(start.plusSeconds(30))!!
        assertEquals(6.0, reading.depthM!!, 1e-9)
        assertEquals(28.0, reading.tempC!!, 1e-9)
        assertEquals(8.0, aligner.at(start.plusSeconds(40))!!.depthM!!, 1e-9)
        assertEquals(26.0, aligner.at(start.plusSeconds(45))!!.tempC!!, 1e-9)
    }

    @Test
    fun fr44_clockOffsetShiftsThePhotoOntoTheComputersClock() {
        // The computer runs 10 s ahead of the phone: a photo at phone 10:00:20 is computer 10:00:30.
        val reading = aligner.at(start.plusSeconds(20), Duration.ofSeconds(10))!!
        assertEquals(6.0, reading.depthM!!, 1e-9)
    }

    @Test
    fun fr44_photosOutsideTheDiveGetNothing() {
        assertNull(aligner.at(start.minusSeconds(1)))
        assertNull(aligner.at(start.plusSeconds(201)))
    }

    @Test
    fun fr44_noDepthAcrossALongGap() {
        val reading = aligner.at(start.plusSeconds(100))!!
        assertNull(reading.depthM)
        assertEquals(26.0, reading.tempC!!, 1e-9)
    }

    @Test
    fun fr44_picksTheDiveThatHoldsThePhoto() {
        val second = DiveProfile(
            start.plusHours(2),
            listOf(ProfilePoint(0.0, 1.0, null), ProfilePoint(10.0, 3.0, 25.0)),
        )
        val both = ProfileAligner(listOf(second, dive))
        assertEquals(2.0, both.at(start.plusHours(2).plusSeconds(5))!!.depthM!!, 1e-9)
        assertEquals(25.0, both.at(start.plusHours(2).plusSeconds(5))!!.tempC!!, 1e-9)
        assertNull(both.at(start.plusHours(1)))
    }
}
