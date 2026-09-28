// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.core.camera.request

import io.github.tengigabytes.anomalops.core.camera.exposure.Exposure
import io.github.tengigabytes.anomalops.core.profile.CalibrationEntry
import io.github.tengigabytes.anomalops.core.profile.CalibrationKey
import io.github.tengigabytes.anomalops.core.profile.CalibrationSource
import io.github.tengigabytes.anomalops.core.profile.DepthBand
import io.github.tengigabytes.anomalops.core.profile.DeviceProfiles
import io.github.tengigabytes.anomalops.core.profile.LensFilter
import io.github.tengigabytes.anomalops.core.profile.ScenePreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RequestPlannerTest {
    private val blazer = requireNotNull(DeviceProfiles.load("blazer"))
    private val planner = RequestPlanner(blazer)
    private val shallow = CalibrationKey(DepthBand.SHALLOW, LensFilter.NONE, diveLight = false)

    @Test
    fun fr11_previewUsesProfileLensAutoExposureAndPresetFocus() {
        val expectedLens = mapOf(
            ScenePreset.SNAPSHOT to "2",
            ScenePreset.WIDE to "3",
            ScenePreset.FISH_SCHOOL to "2",
            ScenePreset.MACRO to "9",
            ScenePreset.LOW_LIGHT to "2",
        )
        ScenePreset.entries.forEach { preset ->
            val spec = planner.preview(preset, shallow)
            assertEquals("$preset", expectedLens.getValue(preset), spec.physicalId)
            assertEquals(AeMode.ON, spec.ae)
            assertNull(spec.exposure)
            val focus = if (preset == ScenePreset.MACRO) FocusSpec.Auto else FocusSpec.ContinuousPicture
            assertEquals("$preset", focus, spec.focus)
        }
    }

    @Test
    fun adr0009_stillSwitchesToManualExposureCappedAtPresetLimit() {
        val preview = planner.preview(ScenePreset.FISH_SCHOOL, shallow)
        val still = planner.still(preview, Exposure(timeNs = SECOND / 60, iso = 100), StillFormat.JPEG_R)
        assertEquals(AeMode.OFF, still.ae)
        assertEquals(SECOND / 250, still.exposure?.exposure?.timeNs)
        assertEquals(417, still.exposure?.exposure?.iso)
        assertEquals(preview.copy(ae = AeMode.OFF, exposure = still.exposure), still)
    }

    @Test
    fun fr61a_blazerCamerasPreferJpegR() {
        ScenePreset.entries.forEach { assertEquals(StillFormat.JPEG_R, StillFormat.bestFor(planner.camera(it))) }
    }

    @Test
    fun fr31_macroFallbackFocusesAtFiveCentimetres() {
        val fixed = planner.focusFallback(planner.preview(ScenePreset.MACRO, shallow))
        assertEquals(FocusSpec.Fixed(diopters = 20.0), fixed.focus)
        val snapshot = planner.preview(ScenePreset.SNAPSHOT, shallow)
        assertEquals(snapshot, planner.focusFallback(snapshot))
    }

    @Test
    fun adr0002_calibratedConditionsGiveManualColor() {
        val gains = listOf(2.0, 1.0, 1.0, 1.6)
        val matrix = List(9) { if (it % 4 == 0) 1.0 else 0.0 }
        val entry = CalibrationEntry("2", DepthBand.SHALLOW, LensFilter.NONE, false, gains, matrix, SOURCE)
        val calibrated = RequestPlanner(blazer.copy(calibration = listOf(entry)))
        assertEquals(ColorSpec.Manual(gains, matrix), calibrated.preview(ScenePreset.SNAPSHOT, shallow).color)
        assertEquals(ColorSpec.AutoApproximate, calibrated.preview(ScenePreset.WIDE, shallow).color)
    }

    @Test
    fun adr0002_uncalibratedConditionsFallBackToApproximateAuto() {
        val deep = shallow.copy(depthBand = DepthBand.DEEP)
        assertEquals(ColorSpec.AutoApproximate, planner.preview(ScenePreset.SNAPSHOT, deep).color)
    }

    @Test(expected = IllegalArgumentException::class)
    fun manualExposureWithAutoExposureIsRejected() {
        val preview = planner.preview(ScenePreset.SNAPSHOT, shallow)
        val still = planner.still(preview, Exposure(SECOND / 500, 100), StillFormat.JPEG)
        preview.copy(exposure = still.exposure)
    }

    private companion object {
        const val SECOND = 1_000_000_000L
        val SOURCE = CalibrationSource("2026-10-01", "grey card", "test", 0.0)
    }
}
