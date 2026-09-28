// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.conditions

import io.github.tengigabytes.anomalops.core.profile.CalibrationKey
import io.github.tengigabytes.anomalops.core.profile.DepthBand
import io.github.tengigabytes.anomalops.core.profile.LensFilter
import io.github.tengigabytes.anomalops.core.telemetry.depth.DepthSource
import io.github.tengigabytes.anomalops.core.telemetry.depth.DepthZone
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * FR-21/24/25: the calibration key for the current shooting conditions. The depth zone comes from a [DepthSource]
 * (FR-84, ADR-0008); the mounted filter and the dive light are the diver's settings. The camera module only sees
 * the resulting [CalibrationKey], so a new depth source changes nothing there (ADR-0007).
 */
class ShootingConditions(
    private val depth: DepthSource,
    filter: LensFilter = LensFilter.NONE,
    diveLight: Boolean = false,
) {
    private val filter = MutableStateFlow(filter)
    private val diveLight = MutableStateFlow(diveLight)

    /** The key right now, for opening the camera. */
    val current: CalibrationKey
        get() = key(depth.readings.value.zone, filter.value, diveLight.value)

    /** Every distinct key, starting with [current]; each change means a new preview request. */
    val keys: Flow<CalibrationKey> =
        combine(depth.readings, this.filter, this.diveLight) { reading, mounted, light ->
            key(reading.zone, mounted, light)
        }.distinctUntilChanged()

    /** FR-24: the filter on the housing port. */
    fun mount(filter: LensFilter) {
        this.filter.value = filter
    }

    /** FR-25: the subject is lit by a dive light. */
    fun light(on: Boolean) {
        diveLight.value = on
    }

    companion object {
        fun bandOf(zone: DepthZone): DepthBand = when (zone) {
            DepthZone.SHALLOW -> DepthBand.SHALLOW
            DepthZone.MID -> DepthBand.MID
            DepthZone.DEEP -> DepthBand.DEEP
        }

        private fun key(zone: DepthZone, filter: LensFilter, diveLight: Boolean) =
            CalibrationKey(bandOf(zone), filter, diveLight)
    }
}
