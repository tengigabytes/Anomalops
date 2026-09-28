// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.core.profile

import kotlinx.serialization.Serializable

/**
 * One white-balance calibration result from a calibration dive (ADR-0002, ADR-0003, FR-21/24/25).
 * Keyed by physical camera x depth band x lens filter x dive light.
 */
@Serializable
data class CalibrationEntry(
    val physicalId: String,
    val depthBand: DepthBand,
    val filter: LensFilter,
    val diveLight: Boolean,
    /** COLOR_CORRECTION_GAINS in the order R, G even, G odd, B. */
    val gains: List<Double>,
    /** COLOR_CORRECTION_TRANSFORM, 3x3 row-major. */
    val colorMatrix: List<Double>,
    val source: CalibrationSource,
)

/** The shooting conditions a calibration entry is chosen by, besides the physical camera (ADR-0002). */
data class CalibrationKey(val depthBand: DepthBand, val filter: LensFilter, val diveLight: Boolean)

internal fun CalibrationEntry.matches(physicalId: String, key: CalibrationKey): Boolean =
    this.physicalId == physicalId && depthBand == key.depthBand && filter == key.filter && diveLight == key.diveLight

/** FR-21 depth bands; the boundaries themselves are still an open item (docs/product/requirements/09-open-items.md). */
@Serializable
enum class DepthBand { SHALLOW, MID, DEEP }

/** FR-24 filter mounted on the housing port. */
@Serializable
enum class LensFilter { NONE, RED, MAGENTA }

@Serializable
data class CalibrationSource(val date: String, val target: String, val site: String, val depthM: Double)
