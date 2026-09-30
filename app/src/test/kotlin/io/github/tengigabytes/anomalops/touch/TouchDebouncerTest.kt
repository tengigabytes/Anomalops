// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.touch

import org.junit.Assert.assertEquals
import org.junit.Test

class TouchDebouncerTest {
    private val radiusPx = 100f
    private val accepted = TouchVerdict.ACCEPTED
    private val repeat = TouchVerdict.REPEAT

    private fun verdicts(vararg downs: TouchDown): List<TouchVerdict> {
        val debouncer = TouchDebouncer(radiusPx)
        return downs.map(debouncer::check)
    }

    @Test
    fun nfr6_samePointWithin200MsCountsOnce() {
        assertEquals(
            listOf(accepted, repeat, repeat),
            verdicts(TouchDown(500f, 500f, 0), TouchDown(500f, 500f, 50), TouchDown(500f, 500f, 199)),
        )
    }

    @Test
    fun nfr6_exactly200MsLaterIsANewPress() {
        assertEquals(listOf(accepted, accepted), verdicts(TouchDown(500f, 500f, 0), TouchDown(500f, 500f, 200)))
    }

    @Test
    fun nfr6_radiusBoundaryIsInclusive() {
        assertEquals(
            listOf(accepted, repeat, accepted),
            verdicts(
                TouchDown(500f, 500f, 0),
                TouchDown(560f, 580f, 10), // 100 px away: on the circle
                TouchDown(500f, 600.5f, 20), // 100.5 px away: outside
            ),
        )
    }

    @Test
    fun nfr6_differentKeysAtTheSameTimeBothCount() {
        assertEquals(listOf(accepted, accepted), verdicts(TouchDown(100f, 100f, 0), TouchDown(900f, 100f, 0)))
    }

    @Test
    fun nfr6_windowRunsFromTheAcceptedPressNotTheLatestRepeat() {
        // A spot firing every 150 ms: accepted at 0 and 300, repeats in between.
        assertEquals(
            listOf(accepted, repeat, accepted, repeat),
            verdicts(
                TouchDown(500f, 500f, 0),
                TouchDown(500f, 500f, 150),
                TouchDown(500f, 500f, 300),
                TouchDown(500f, 500f, 450),
            ),
        )
    }

    @Test
    fun nfr6_anOlderPressElsewhereDoesNotBlockANewOne() {
        assertEquals(
            listOf(accepted, accepted, repeat),
            verdicts(TouchDown(100f, 100f, 0), TouchDown(500f, 500f, 100), TouchDown(510f, 500f, 250)),
        )
    }
}
