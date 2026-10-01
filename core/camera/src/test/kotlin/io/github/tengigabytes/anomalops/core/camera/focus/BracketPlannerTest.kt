// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.core.camera.focus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Lens values of the Pixel 10 Pro from docs/test/m9-macro-land.md and g0-blazer.md. */
class BracketPlannerTest {
    private val tele = BracketLens(3.3333f, 0.0245f, FocusCalibration.APPROXIMATE, focalLengthMm = 17.906f)
    private val ultraWideCrop = BracketLens(50f, 0.583f, FocusCalibration.APPROXIMATE, focalLengthMm = 2.02f)
    private val main = BracketLens(9.5238f, 0.169f, FocusCalibration.CALIBRATED, focalLengthMm = 6.9f)
    private val planner = BracketPlanner()

    private fun gapsWithin(plan: BracketPlan, maxGap: Float) =
        plan.requestedDiopters.zipWithNext().all { (a, b) -> a > b && a - b <= maxGap + 1e-5f }

    @Test
    fun fr36_teleOverAShallowSubjectAt31To32Cm() {
        val plan = planner.plan(tele, 31f, 32f)!!
        assertFalse(plan.truncated)
        assertFalse(plan.tooClose)
        // Nearest first, no step wider than 0.7 of the depth of field (2 x 0.0245 D).
        assertTrue(plan.requestedDiopters.toString(), gapsWithin(plan, 0.7f * 2 * 0.0245f))
        assertTrue("covers the near end", plan.covered.start >= 100f / 31f - 1e-4f)
        assertTrue("covers the far end", plan.covered.endInclusive <= 100f / 32f + 1e-4f)
    }

    @Test
    fun fr33_wideMacroAt2To3CmIsTooDeepForSixFrames() {
        // About 1 mm of depth of field at 2 cm (ADR-0012): a 1 cm deep subject takes far more than six frames.
        val plan = planner.plan(ultraWideCrop, 2f, 3f)!!
        assertTrue(plan.truncated)
        assertEquals(BracketPlanner.DEFAULT_MAX_FRAMES, plan.requestedDiopters.size)
        assertTrue(plan.requestedDiopters.zipWithNext().all { (a, b) -> a > b })
    }

    @Test
    fun approximateLensGetsOneMoreFrameThanACalibratedOne() {
        val calibrated = planner.plan(main.copy(calibration = FocusCalibration.CALIBRATED), 30f, 40f)!!
        val approximate = planner.plan(main.copy(calibration = FocusCalibration.APPROXIMATE), 30f, 40f)!!
        assertEquals(calibrated.requestedDiopters.size + 1, approximate.requestedDiopters.size)
        val measured = main.copy(calibration = FocusCalibration.APPROXIMATE, hasCalibrationTable = true)
        assertEquals(calibrated.requestedDiopters.size, planner.plan(measured, 30f, 40f)!!.requestedDiopters.size)
    }

    @Test
    fun uncalibratedLensWithoutATableCannotBePlanned() {
        val lens = main.copy(calibration = FocusCalibration.UNCALIBRATED)
        assertNull(planner.plan(lens, 30f, 40f))
        assertNotNull(planner.plan(lens.copy(hasCalibrationTable = true), 30f, 40f))
    }

    @Test
    fun measuredOffsetShiftsWhatIsRequested() {
        // T4: the lenses read about 0.07 D too far, true = reported + 0.07, so ask for 0.07 D less.
        val plain = planner.plan(main, 40f, 41f)!!
        val corrected = planner.plan(main.copy(offsetDiopters = 0.07f), 40f, 41f)!!
        plain.requestedDiopters.zip(corrected.requestedDiopters).forEach { (p, c) -> assertEquals(p - 0.07f, c, 1e-4f) }
    }

    @Test
    fun subjectCloserThanTheLensFocusesStartsAtItsLimit() {
        val plan = planner.plan(tele, 25f, 32f)!!
        assertTrue(plan.tooClose)
        assertTrue(plan.requestedDiopters.all { it <= tele.minFocusDiopters })
    }

    @Test
    fun shallowSubjectNeedsOneFrame() {
        val plan = planner.plan(main, 100f, 101f)!!
        assertEquals(1, plan.requestedDiopters.size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun fr33_frameLimitIsFiveToEight() {
        BracketPlanner(maxFrames = 9)
    }
}
