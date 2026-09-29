// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.conditions

import io.github.tengigabytes.anomalops.core.telemetry.depth.DepthZone
import io.github.tengigabytes.anomalops.core.telemetry.depth.ManualDepthSource
import org.junit.Assert.assertEquals
import org.junit.Test

class DepthSwitchTest {
    @Test
    fun fr21_depthKeyCyclesShallowMidDeep() {
        val depth = ManualDepthSource(DepthZone.SHALLOW)
        val seen = List(3) {
            depth.cycle()
            depth.readings.value.zone
        }
        assertEquals(listOf(DepthZone.MID, DepthZone.DEEP, DepthZone.SHALLOW), seen)
    }

    @Test
    fun fr25_lightKeyToggles() {
        val conditions = ShootingConditions(ManualDepthSource())
        val seen = List(2) {
            conditions.toggleLight()
            conditions.current.diveLight
        }
        assertEquals(listOf(true, false), seen)
    }
}
