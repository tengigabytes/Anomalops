// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.core.telemetry.depth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ManualDepthSourceTest {
    @Test
    fun fr84_startsShallowAndFollowsTheSwitch() {
        val source = ManualDepthSource()
        assertEquals(DepthZone.SHALLOW, source.readings.value.zone)
        source.select(DepthZone.DEEP)
        assertEquals(DepthZone.DEEP, source.readings.value.zone)
        assertNull("a manual source measures no depth", source.readings.value.depthM)
        assertEquals(ManualDepthSource.SOURCE, source.readings.value.source)
    }
}
