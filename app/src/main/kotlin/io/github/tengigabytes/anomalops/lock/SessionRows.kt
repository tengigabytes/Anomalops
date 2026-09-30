// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.lock

import io.github.tengigabytes.anomalops.touch.TouchVerdict

/**
 * `touches.csv` and `captures.csv` columns (ADR-0008). `:core:telemetry` leaves them to `:app`, which owns these
 * events. All times are `elapsedRealtimeNanos`; `session.json` pairs that clock with UTC once.
 */
object SessionRows {
    /** Presses and releases with their position and whether NFR-6 dropped them (analysed in G3 / G4). */
    val TOUCH_HEADER = listOf("elapsed_ns", "action", "x_px", "y_px", "filtered")

    val CAPTURE_HEADER = listOf(
        "elapsed_ns",
        "kind",
        "sensor_timestamp_ns",
        "preset",
        "lens",
        "depth_band",
        "filter",
        "dive_light",
        "format",
        "frames",
        "request_exposure_ns",
        "request_iso",
        "iso_clamped",
        "request_wb_gains",
        "reported_exposure_ns",
        "reported_iso",
        "reported_wb_gains",
        "file",
    )

    enum class Action { DOWN, UP }

    fun touch(elapsedNs: Long, action: Action, x: Float, y: Float, verdict: TouchVerdict?): List<Any?> =
        listOf(elapsedNs, action.name.lowercase(), x, y, verdict?.let { it == TouchVerdict.REPEAT })

    /**
     * One capture. A still has its own request and result; a burst (FR-15) is one row with its frame count and
     * the first file's stem. Gains are "R G G B"; a missing value (auto white balance, no result) is empty.
     */
    data class Capture(
        val elapsedNs: Long,
        val kind: String,
        val sensorTimestampNs: Long?,
        val preset: String,
        val lens: String?,
        val depthBand: String,
        val filter: String,
        val diveLight: Boolean,
        val format: String?,
        val frames: Int,
        val requestExposureNs: Long?,
        val requestIso: Int?,
        val isoClamped: Boolean?,
        val requestGains: List<Double>?,
        val reportedExposureNs: Long?,
        val reportedIso: Int?,
        val reportedGains: List<Double>?,
        val file: String?,
    ) {
        fun row(): List<Any?> = listOf(
            elapsedNs,
            kind,
            sensorTimestampNs,
            preset,
            lens,
            depthBand,
            filter,
            diveLight,
            format,
            frames,
            requestExposureNs,
            requestIso,
            isoClamped,
            requestGains?.joinToString(" "),
            reportedExposureNs,
            reportedIso,
            reportedGains?.joinToString(" "),
            file,
        )
    }
}
