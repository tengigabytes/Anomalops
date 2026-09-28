// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.conditions

import io.github.tengigabytes.anomalops.core.camera.request.ColorSpec
import io.github.tengigabytes.anomalops.core.camera.request.RequestPlanner
import io.github.tengigabytes.anomalops.core.profile.CalibrationEntry
import io.github.tengigabytes.anomalops.core.profile.CalibrationKey
import io.github.tengigabytes.anomalops.core.profile.CalibrationSource
import io.github.tengigabytes.anomalops.core.profile.DepthBand
import io.github.tengigabytes.anomalops.core.profile.DeviceProfiles
import io.github.tengigabytes.anomalops.core.profile.LensFilter
import io.github.tengigabytes.anomalops.core.profile.ScenePreset
import io.github.tengigabytes.anomalops.core.telemetry.depth.DepthReading
import io.github.tengigabytes.anomalops.core.telemetry.depth.DepthSource
import io.github.tengigabytes.anomalops.core.telemetry.depth.DepthZone
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShootingConditionsTest {
    /** Stands in for a measuring source such as BLE (FR-85); the camera module never sees this type. */
    private class FakeDepthSource : DepthSource {
        override val readings = MutableStateFlow(DepthReading(DepthZone.SHALLOW, depthM = 3.0, source = "fake"))

        fun dive(zone: DepthZone, depthM: Double) {
            readings.value = DepthReading(zone, depthM, "fake")
        }
    }

    private val blazer = requireNotNull(DeviceProfiles.load("blazer"))
    private val shallowEntry = entry(DepthBand.SHALLOW, LensFilter.NONE, diveLight = false, red = 1.8)
    private val deepEntry = entry(DepthBand.DEEP, LensFilter.NONE, diveLight = false, red = 3.2)
    private val redFilterEntry = entry(DepthBand.SHALLOW, LensFilter.RED, diveLight = false, red = 1.2)
    private val diveLightEntry = entry(DepthBand.SHALLOW, LensFilter.RED, diveLight = true, red = 1.1)
    private val planner = RequestPlanner(
        blazer.copy(calibration = listOf(shallowEntry, deepEntry, redFilterEntry, diveLightEntry)),
    )

    @Test
    fun fr84_fakeDepthSourceSwitchChangesTheRequestedColor() {
        val depth = FakeDepthSource()
        val conditions = ShootingConditions(depth)
        assertEquals(manual(shallowEntry), planner.preview(ScenePreset.SNAPSHOT, conditions.current).color)
        depth.dive(DepthZone.DEEP, depthM = 18.0)
        assertEquals(manual(deepEntry), planner.preview(ScenePreset.SNAPSHOT, conditions.current).color)
        depth.dive(DepthZone.MID, depthM = 9.0)
        assertEquals(
            "no MID entry falls back to approximate auto (ADR-0002)",
            ColorSpec.AutoApproximate,
            planner.preview(ScenePreset.SNAPSHOT, conditions.current).color,
        )
    }

    @Test
    fun fr24_redFilterUsesItsOwnShallowEntryWithLessRedGain() {
        val conditions = ShootingConditions(FakeDepthSource())
        conditions.mount(LensFilter.RED)
        val color = planner.preview(ScenePreset.SNAPSHOT, conditions.current).color
        assertEquals(manual(redFilterEntry), color)
        assertTrue((color as ColorSpec.Manual).gains[0] < shallowEntry.gains[0])
    }

    @Test
    fun fr25_diveLightUsesItsOwnEntry() {
        val conditions = ShootingConditions(FakeDepthSource(), filter = LensFilter.RED)
        conditions.light(on = true)
        assertEquals(CalibrationKey(DepthBand.SHALLOW, LensFilter.RED, diveLight = true), conditions.current)
        assertEquals(manual(diveLightEntry), planner.preview(ScenePreset.SNAPSHOT, conditions.current).color)
    }

    @Test
    fun keysEmitOncePerDistinctConditionNotPerReading() = runBlocking<Unit> {
        val depth = FakeDepthSource()
        val conditions = ShootingConditions(depth)
        val seen = mutableListOf<CalibrationKey>()
        val job = launch(Dispatchers.Unconfined, CoroutineStart.UNDISPATCHED) { conditions.keys.collect { seen += it } }
        depth.dive(DepthZone.SHALLOW, depthM = 4.0)
        yield()
        depth.dive(DepthZone.DEEP, depthM = 16.0)
        yield()
        depth.dive(DepthZone.DEEP, depthM = 25.0)
        yield()
        conditions.light(on = true)
        yield()
        job.cancel()
        val shallow = CalibrationKey(DepthBand.SHALLOW, LensFilter.NONE, diveLight = false)
        val deep = shallow.copy(depthBand = DepthBand.DEEP)
        assertEquals(listOf(shallow, deep, deep.copy(diveLight = true)), seen)
    }

    @Test
    fun everyZoneMapsToTheSameNamedBand() {
        DepthZone.entries.forEach { assertEquals(it.name, ShootingConditions.bandOf(it).name) }
    }

    private fun manual(entry: CalibrationEntry) = ColorSpec.Manual(entry.gains, entry.colorMatrix)

    private companion object {
        val SOURCE = CalibrationSource("2026-10-01", "grey card", "test", 0.0)
        val IDENTITY = List(9) { if (it % 4 == 0) 1.0 else 0.0 }

        fun entry(band: DepthBand, filter: LensFilter, diveLight: Boolean, red: Double) =
            CalibrationEntry("2", band, filter, diveLight, listOf(red, 1.0, 1.0, 1.5), IDENTITY, SOURCE)
    }
}
