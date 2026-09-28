// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.core.camera.request

import org.junit.Assert.assertEquals
import org.junit.Test

class FixedPointTest {
    @Test
    fun adr0002_hal1over256GridValuesRoundTripExactly() {
        // Elements the HAL reported for lens 2; with a 1/10000 denominator 2.02734375 became 2.0273 and the HAL
        // truncated it one grid step down to 2.0234375.
        listOf(2.02734375, -0.45703125, -0.56640625, -1.04296875, 2.08203125).forEach { value ->
            assertEquals(value, FixedPoint.numerator(value).toDouble() / FixedPoint.DENOMINATOR, 0.0)
        }
    }

    @Test
    fun arbitraryValuesStayWithinHalfAStep() {
        val value = 1.2345678
        val decoded = FixedPoint.numerator(value).toDouble() / FixedPoint.DENOMINATOR
        assertEquals(value, decoded, 0.5 / FixedPoint.DENOMINATOR)
    }
}
