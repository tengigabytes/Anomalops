// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.core.profile

import kotlinx.serialization.Serializable

/**
 * One lens's measured focus correction (ADR-0014; macro test plan T4): true diopters = [slope] x reported +
 * [offsetDiopters], least-squares over [points] points whose reported diopters span [fittedRangeDiopters]. The lens
 * reported [calibration] and [minFocusDiopters] when measured; a lens reporting otherwise at run time is treated as
 * having no table (ADR-0014, section 1). Read by `:core:camera`'s `FocusCalibrationTable`.
 */
@Serializable
data class FocusCalibrationEntry(
    val physicalId: String,
    val slope: Double,
    val offsetDiopters: Double,
    val fittedRangeDiopters: List<Double>,
    val residualMaxDiopters: Double,
    val residualRmsDiopters: Double,
    val points: Int,
    val calibration: FocusDistanceCalibration,
    val minFocusDiopters: Double,
    val source: FocusCalibrationSource,
)

/** `LENS_INFO_FOCUS_DISTANCE_CALIBRATION`. */
@Serializable
enum class FocusDistanceCalibration { UNCALIBRATED, APPROXIMATE, CALIBRATED }

/** When and how the points were measured, and where the record is. */
@Serializable
data class FocusCalibrationSource(val date: String, val method: String, val record: String)
