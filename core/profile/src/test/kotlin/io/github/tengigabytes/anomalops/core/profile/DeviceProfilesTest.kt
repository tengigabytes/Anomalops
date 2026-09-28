// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.core.profile

import kotlinx.serialization.SerializationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceProfilesTest {
    private val blazer = requireNotNull(DeviceProfiles.load("blazer")) { "blazer profile missing from resources" }

    @Test
    fun nfr9_blazerProfileLoadsAndValidates() {
        assertEquals("blazer", blazer.device.buildDevice)
        assertEquals(emptyList<String>(), ProfileValidator.validate(blazer))
    }

    @Test
    fun fr11_presetLensesResolveToFinalisedCameras() {
        assertCamera(ScenePreset.SNAPSHOT, "2", LensKind.MAIN, Readout.BINNED)
        assertCamera(ScenePreset.FISH_SCHOOL, "2", LensKind.MAIN, Readout.BINNED)
        assertCamera(ScenePreset.LOW_LIGHT, "2", LensKind.MAIN, Readout.BINNED)
        assertCamera(ScenePreset.WIDE, "3", LensKind.ULTRAWIDE, Readout.BINNED)
        assertCamera(ScenePreset.MACRO, "9", LensKind.ULTRAWIDE, Readout.CROP_2X)
    }

    @Test
    fun fr31_macroCameraFocusesWithinFiveCentimetres() {
        val macro = requireNotNull(blazer.cameraFor(ScenePreset.MACRO))
        val closestMetres = 1.0 / macro.minFocusDistanceDiopters
        assertTrue("closest focus $closestMetres m", closestMetres <= FIVE_CM)
    }

    @Test
    fun unknownDeviceReturnsNull() {
        assertNull(DeviceProfiles.load("no-such-device"))
    }

    @Test(expected = SerializationException::class)
    fun unknownFieldIsRejected() {
        DeviceProfiles.parse(blazerText().replaceFirst("{", "{\"unexpected\": 1,"))
    }

    @Test
    fun validatorFlagsPresetPointingToMissingCamera() {
        val broken = blazer.copy(presetLenses = blazer.presetLenses.copy(macro = "99"))
        assertTrue(ProfileValidator.validate(broken).any { "MACRO" in it && "99" in it })
    }

    @Test
    fun validatorFlagsMalformedCalibration() {
        val entry = CalibrationEntry(
            physicalId = "2",
            depthBand = DepthBand.SHALLOW,
            filter = LensFilter.NONE,
            diveLight = false,
            gains = listOf(2.0, 1.0, 1.0),
            colorMatrix = List(9) { 0.0 },
            source = CalibrationSource("2026-10-01", "grey card", "test", 3.0),
        )
        val problems = ProfileValidator.validate(blazer.copy(calibration = listOf(entry)))
        assertTrue(problems.toString(), problems.any { "gains" in it })
    }

    private fun assertCamera(preset: ScenePreset, id: String, lens: LensKind, readout: Readout) {
        val camera = blazer.cameraFor(preset)
        assertNotNull("$preset has no camera", camera)
        assertEquals(id, camera!!.id)
        assertEquals(lens, camera.lens)
        assertEquals(readout, camera.readout)
    }

    private fun blazerText(): String = requireNotNull(
        javaClass.classLoader?.getResourceAsStream("${DeviceProfiles.RESOURCE_DIR}/blazer.json"),
    ).bufferedReader().use { it.readText() }

    private companion object {
        const val FIVE_CM = 0.05
    }
}
