// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.core.camera.exposure

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** One exposure setting: `SENSOR_EXPOSURE_TIME` in nanoseconds and `SENSOR_SENSITIVITY` in ISO. */
data class Exposure(val timeNs: Long, val iso: Int)

/**
 * A still-capture exposure after the ADR-0009 conversion.
 *
 * @property isoClamped the ISO needed to keep the metered exposure fell outside the sensor range, so the
 *   capture is under- or over-exposed; ADR-0009 accepts this and records it in `captures.csv`.
 */
data class StillExposure(val exposure: Exposure, val frameDurationNs: Long, val isoClamped: Boolean)

/**
 * ADR-0009: the preview auto-exposure acts as a light meter; the still keeps its exposure value
 * (time x ISO) but never exposes longer than the preset's limit.
 */
object ShutterPriority {
    fun convert(metered: Exposure, maxExposureNs: Long, isoRange: IntRange, minFrameNs: Long): StillExposure {
        require(metered.timeNs > 0 && metered.iso > 0) { "metered exposure must be positive: $metered" }
        require(maxExposureNs > 0) { "maxExposureNs must be positive" }
        // UNVERIFIED(G1): assumes brightness is proportional to time x ISO across the analog/digital gain switch.
        val timeNs = min(metered.timeNs, maxExposureNs)
        val neededIso = (metered.iso.toDouble() * metered.timeNs / timeNs).roundToInt()
        val iso = neededIso.coerceIn(isoRange)
        return StillExposure(
            exposure = Exposure(timeNs, iso),
            frameDurationNs = max(timeNs, minFrameNs),
            isoClamped = iso != neededIso,
        )
    }
}
