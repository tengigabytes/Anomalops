// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.orientation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** FR-92: flip by gravity after a hold, keep the side when flat or sideways, and obey the lock. */
class InversionDetectorTest {
    private val g = 9.81f
    private val detector = InversionDetector()

    @Test
    fun fr92_flipsAfterHoldingUpsideDown() {
        assertFalse(detector.onGravity(0f, -g, 0f, 0))
        assertFalse(detector.onGravity(0f, -g, 0f, 499))
        assertTrue(detector.onGravity(0f, -g, 0f, 500))
        assertTrue(detector.onGravity(0f, g, 0f, 600))
        assertFalse(detector.onGravity(0f, g, 0f, 1_100))
    }

    @Test
    fun fr92_aBriefSwingDoesNotFlip() {
        detector.onGravity(0f, -g, 0f, 0)
        detector.onGravity(0f, g, 0f, 300)
        assertFalse(detector.onGravity(0f, -g, 0f, 600))
        assertFalse(detector.onGravity(0f, -g, 0f, 1_000))
        assertTrue(detector.onGravity(0f, -g, 0f, 1_100))
    }

    @Test
    fun fr92_flatOrSidewaysKeepsTheCurrentSide() {
        detector.onGravity(0f, -g, 0f, 0)
        detector.onGravity(0f, -g, 0f, 500)
        // Screen up with the camera end slightly high: gravity on z.
        assertTrue(detector.onGravity(0f, 1f, g, 2_000))
        // Landscape.
        assertTrue(detector.onGravity(g, 0f, 0f, 4_000))
        // 53° from upright already counts as upright (cos 53° ≈ 0.6 ≥ 0.5); the flip back waits for the hold.
        assertTrue(detector.onGravity(0.8f * g, 0.6f * g, 0f, 5_000))
        assertFalse(detector.onGravity(0.6f * g, 0.8f * g, 0f, 5_500))
    }

    @Test
    fun fr92_lockFreezesTheSide() {
        detector.locked = true
        detector.onGravity(0f, -g, 0f, 0)
        assertFalse(detector.onGravity(0f, -g, 0f, 10_000))
        detector.locked = false
        detector.onGravity(0f, -g, 0f, 10_100)
        assertTrue(detector.onGravity(0f, -g, 0f, 10_600))
    }

    @Test
    fun fr92_jpegOrientationTurnsWithTheUi() {
        assertEquals(90, InversionDetector.jpegOrientation(90, inverted = false))
        assertEquals(270, InversionDetector.jpegOrientation(90, inverted = true))
        assertEquals(90, InversionDetector.jpegOrientation(270, inverted = true))
    }
}
