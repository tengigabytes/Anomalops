// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.core.telemetry.divelog

import java.time.Duration
import java.time.LocalDateTime

/** Depth and water temperature at one photo; either can be unknown. */
data class PhotoReading(val depthM: Double?, val tempC: Double?)

/**
 * FR-44: the depth and water temperature at a photo's time. Depth is interpolated between the two samples around
 * it; temperature is the latest one written before it (logs repeat it only when it changes). A photo outside every
 * dive, or in a gap longer than [maxGapS] between depth samples, gets no depth.
 */
class ProfileAligner(dives: List<DiveProfile>, private val maxGapS: Double = MAX_GAP_S) {
    private val dives = dives.sortedBy { it.start }

    /**
     * [photo] is the phone's wall clock (EXIF `DateTimeOriginal`); [offset] is the dive computer's clock minus the
     * phone's, which the user corrects by hand (FR-44). Null when the photo falls outside every dive.
     */
    fun at(photo: LocalDateTime, offset: Duration = Duration.ZERO): PhotoReading? {
        val computerTime = photo.plus(offset)
        for (dive in dives) {
            val s = Duration.between(dive.start, computerTime).toMillis() / MILLIS_PER_SECOND
            if (s >= 0.0 && s <= dive.durationS) return PhotoReading(depthAt(dive.points, s), tempAt(dive.points, s))
        }
        return null
    }

    private fun depthAt(points: List<ProfilePoint>, s: Double): Double? {
        val before = points.lastOrNull { it.depthM != null && it.offsetS <= s }
        val after = points.firstOrNull { it.depthM != null && it.offsetS >= s }
        if (before?.depthM == null || after?.depthM == null || after.offsetS - before.offsetS > maxGapS) return null
        val span = after.offsetS - before.offsetS
        val fraction = if (span == 0.0) 0.0 else (s - before.offsetS) / span
        return before.depthM + (after.depthM - before.depthM) * fraction
    }

    private fun tempAt(points: List<ProfilePoint>, s: Double): Double? {
        points.lastOrNull { it.tempC != null && it.offsetS <= s }?.let { return it.tempC }
        return points.firstOrNull { it.tempC != null && it.offsetS - s <= maxGapS }?.tempC
    }

    companion object {
        /**
         * Agreed on 2026-09-30 (docs/product/early-logic.md, section 3): logs sample every 2–30 s; a longer hole
         * means the computer lost the profile there.
         */
        const val MAX_GAP_S = 60.0
        private const val MILLIS_PER_SECOND = 1000.0
    }
}
