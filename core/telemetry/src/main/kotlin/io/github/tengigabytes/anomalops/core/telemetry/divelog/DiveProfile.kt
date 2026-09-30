// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.core.telemetry.divelog

import java.time.LocalDateTime

/**
 * One sample of a dive computer's profile: [offsetS] seconds after the dive's start, depth in metres and water
 * temperature in °C. Either value can be missing; logs write the temperature only now and then.
 */
data class ProfilePoint(val offsetS: Double, val depthM: Double?, val tempC: Double?)

/**
 * FR-44: one dive read from a dive computer's log. [start] is the computer's own wall clock as the log states it,
 * without a zone; the photo side corrects the clock offset (ADR-0008). [points] are in time order.
 */
data class DiveProfile(val start: LocalDateTime, val points: List<ProfilePoint>) {
    /** Seconds from [start] to the last sample. */
    val durationS: Double get() = points.lastOrNull()?.offsetS ?: 0.0
}

/** A log that is not UDDF or Subsurface XML, or a value this reader does not understand. */
class DiveLogFormatException(message: String, cause: Throwable? = null) : Exception(message, cause)
