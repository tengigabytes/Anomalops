// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.core.camera.focus

import io.github.tengigabytes.anomalops.core.profile.DeviceProfiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The T4 points of docs/test/m9-macro-land.md, section 5 (30-60 cm, tape-measured, sharpest fine-scan focus). */
class FocusCalibrationTableTest {
    private val tapeCm = listOf(30f, 35f, 39f, 50f, 55f, 60f)
    private val mainSharpest = listOf(3.183f, 2.807f, 2.514f, 1.950f, 1.718f, 1.567f)
    private val tele4Sharpest = listOf(3.200f, 2.797f, 2.455f, 1.891f, 1.734f, 1.582f)
    private val tele6Sharpest = listOf(3.188f, 2.773f, 2.443f, 1.879f, 1.734f, 1.558f)

    private fun points(sharpest: List<Float>) = sharpest.zip(tapeCm) { d, cm -> FocusPoint(d, 100f / cm) }

    private fun fit(sharpest: List<Float>, minFocus: Float) =
        FocusCalibrationTable.fit(points(sharpest), FocusCalibration.APPROXIMATE, minFocus)

    @Test
    fun t4_fitsMatchTheTableInTheTestRecord() {
        // Section 5's table: a, b, worst / RMS residual.
        listOf(
            Triple(fit(mainSharpest, 9.5238f), floatArrayOf(1.012f, 0.056f), floatArrayOf(0.056f, 0.037f)),
            Triple(fit(tele4Sharpest, 3.3333f), floatArrayOf(1.013f, 0.067f), floatArrayOf(0.043f, 0.022f)),
            Triple(fit(tele6Sharpest, 3.3333f), floatArrayOf(1.015f, 0.076f), floatArrayOf(0.034f, 0.020f)),
        ).forEach { (table, line, residual) ->
            assertEquals(line[0], table.slope, 1e-3f)
            assertEquals(line[1], table.offsetDiopters, 1e-3f)
            assertEquals(residual[0], table.residualMaxDiopters, 1e-3f)
            assertEquals(residual[1], table.residualRmsDiopters, 1e-3f)
        }
    }

    @Test
    fun t4_mainLensPassesTeleDoesNot() {
        // A quarter of the depth of field: main 2 x 0.169 / 4 = 0.0845 D, tele 2 x 0.0245 / 4 = 0.012 D.
        assertTrue(fit(mainSharpest, 9.5238f).precise(0.169f))
        assertFalse(fit(tele4Sharpest, 3.3333f).precise(0.0245f))
        assertFalse(fit(tele6Sharpest, 3.3333f).precise(0.0122f))
    }

    @Test
    fun conversionRoundTripsAndCorrectsTheReadings() {
        val table = fit(tele4Sharpest, 3.3333f)
        assertEquals(2.3f, table.reportedFor(table.trueDiopters(2.3f)), 1e-5f)
        // At 50 cm the lens read 1.891 D; corrected it should land near the tape's 2.000 D, within the residual.
        assertEquals(2.000f, table.trueDiopters(1.891f), table.residualMaxDiopters)
        assertTrue(table.covers(2.5f))
        assertFalse(table.covers(3.3f))
    }

    @Test
    fun tableAppliesOnlyToTheLensAsMeasured() {
        val table = fit(mainSharpest, 9.5238f)
        assertTrue(table.appliesTo(FocusCalibration.APPROXIMATE, 9.5238f))
        assertFalse(table.appliesTo(FocusCalibration.CALIBRATED, 9.5238f))
        assertFalse(table.appliesTo(FocusCalibration.APPROXIMATE, 10f))
    }

    @Test
    fun theProfileTableIsTheSameFit() {
        val profile = requireNotNull(DeviceProfiles.load("blazer"))
        val stored = FocusCalibrationTable.fromProfile(requireNotNull(profile.focusCalibrationFor("4")))
        val fitted = fit(tele4Sharpest, 3.3333f)
        assertEquals(fitted.slope, stored.slope, 1e-4f)
        assertEquals(fitted.offsetDiopters, stored.offsetDiopters, 1e-4f)
        assertEquals(fitted.residualMaxDiopters, stored.residualMaxDiopters, 1e-4f)
        assertEquals(fitted.fittedRange, stored.fittedRange)
        assertTrue(
            stored.appliesTo(
                FocusCalibration.APPROXIMATE,
                profile.physicalCamera("4")!!.minFocusDistanceDiopters.toFloat(),
            ),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun fitNeedsMoreThanOneDistance() {
        FocusCalibrationTable.fit(List(3) { FocusPoint(2f, 2.1f) }, FocusCalibration.APPROXIMATE, 9f)
    }
}
