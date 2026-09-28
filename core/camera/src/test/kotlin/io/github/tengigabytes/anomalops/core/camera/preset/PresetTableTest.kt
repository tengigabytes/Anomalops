// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.core.camera.preset

import io.github.tengigabytes.anomalops.core.profile.ScenePreset
import org.junit.Assert.assertEquals
import org.junit.Test

/** FR-11: the draft values of docs/product/mvp-scope.md, section 4. */
class PresetTableTest {
    @Test
    fun fr11_maxExposureMatchesScopeTable() {
        val expected = mapOf(
            ScenePreset.SNAPSHOT to 125,
            ScenePreset.WIDE to 60,
            ScenePreset.FISH_SCHOOL to 250,
            ScenePreset.MACRO to 125,
            ScenePreset.LOW_LIGHT to 30,
        )
        ScenePreset.entries.forEach { preset ->
            assertEquals("$preset", SECOND / expected.getValue(preset), PresetTable.parametersFor(preset).maxExposureNs)
        }
    }

    @Test
    fun fr11_onlyMacroUsesAutoFocusWithFiveCentimetreFallback() {
        ScenePreset.entries.filter { it != ScenePreset.MACRO }.forEach { preset ->
            assertEquals("$preset", FocusPolicy.ContinuousPicture, PresetTable.parametersFor(preset).focus)
        }
        assertEquals(FocusPolicy.AutoWithFixedFallback(0.05), PresetTable.parametersFor(ScenePreset.MACRO).focus)
    }

    private companion object {
        const val SECOND = 1_000_000_000L
    }
}
