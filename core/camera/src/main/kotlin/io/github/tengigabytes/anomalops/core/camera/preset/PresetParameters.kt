// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.core.camera.preset

import io.github.tengigabytes.anomalops.core.profile.ScenePreset

/** How a scene preset focuses (docs/product/mvp-scope.md, section 4). */
sealed interface FocusPolicy {
    /** `CONTROL_AF_MODE_CONTINUOUS_PICTURE`. */
    data object ContinuousPicture : FocusPolicy

    /** `CONTROL_AF_MODE_AUTO`; if the scan fails, focus at a fixed distance instead (FR-31). */
    data class AutoWithFixedFallback(val fallbackDistanceM: Double) : FocusPolicy
}

/**
 * The device-independent part of a scene preset (FR-11). The lens is device-specific and comes from the
 * device profile (ADR-0003); the white-balance starting point comes from the calibration table (ADR-0002).
 */
data class PresetParameters(val maxExposureNs: Long, val focus: FocusPolicy)

object PresetTable {
    // Draft values from docs/product/mvp-scope.md, section 4; finalised after the M1 test shots.
    private const val NS_PER_SECOND = 1_000_000_000L
    private const val SNAPSHOT_SHUTTER = 125
    private const val WIDE_SHUTTER = 60
    private const val FISH_SCHOOL_SHUTTER = 250
    private const val MACRO_SHUTTER = 125
    private const val LOW_LIGHT_SHUTTER = 30
    private const val MACRO_FALLBACK_M = 0.05

    private val macro = PresetParameters(
        maxExposureNs = shutter(MACRO_SHUTTER),
        focus = FocusPolicy.AutoWithFixedFallback(MACRO_FALLBACK_M),
    )

    fun parametersFor(preset: ScenePreset): PresetParameters = when (preset) {
        ScenePreset.SNAPSHOT -> continuous(SNAPSHOT_SHUTTER)
        ScenePreset.WIDE -> continuous(WIDE_SHUTTER)
        ScenePreset.FISH_SCHOOL -> continuous(FISH_SCHOOL_SHUTTER)
        ScenePreset.MACRO -> macro
        ScenePreset.LOW_LIGHT -> continuous(LOW_LIGHT_SHUTTER)
    }

    private fun continuous(denominator: Int) = PresetParameters(shutter(denominator), FocusPolicy.ContinuousPicture)

    /** Exposure time of a 1/[denominator] s shutter, in nanoseconds. */
    private fun shutter(denominator: Int): Long = NS_PER_SECOND / denominator
}
