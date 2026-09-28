// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.core.telemetry.depth

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * FR-21 depth zones as depth sources report them (shallow 0–6 m, mid 6–15 m, deep 15 m and more; the boundaries
 * are still an open item). `:app` maps them to the calibration table's depth bands, because this module depends
 * on no other module (ADR-0007).
 */
enum class DepthZone { SHALLOW, MID, DEEP }

/** One reading: the zone that drives white balance, and the depth in metres when the source measures one. */
data class DepthReading(val zone: DepthZone, val depthM: Double?, val source: String)

/**
 * ADR-0008 / FR-84: a real-time depth source. A new source (for example BLE, FR-85, FR-86) implements this
 * interface; the capture pipeline does not change. Readings are conflated: consumers want the current zone.
 */
interface DepthSource {
    val readings: StateFlow<DepthReading>
}

/** v1.0: the diver sets the zone with the depth-band switch (M3); there is no measured depth. */
class ManualDepthSource(initial: DepthZone = DepthZone.SHALLOW) : DepthSource {
    private val state = MutableStateFlow(DepthReading(initial, depthM = null, SOURCE))
    override val readings: StateFlow<DepthReading> = state.asStateFlow()

    fun select(zone: DepthZone) {
        state.value = DepthReading(zone, depthM = null, SOURCE)
    }

    companion object {
        const val SOURCE = "manual"
    }
}
