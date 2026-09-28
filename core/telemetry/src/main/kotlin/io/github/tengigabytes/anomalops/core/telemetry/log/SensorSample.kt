// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.core.telemetry.log

/** Three-axis magnetometer reading in µT. */
data class Vector3(val x: Float, val y: Float, val z: Float)

/** Battery and thermal state (ADR-0008: stands in for the body temperature, which apps cannot read). */
data class PowerState(
    val batteryTempC: Float?,
    val batteryPercent: Int?,
    /** `PowerManager.THERMAL_STATUS_*`. */
    val thermalStatus: Int?,
    /** `PowerManager.getThermalHeadroom`, 1.0 means throttling; NaN when the platform has no value. */
    val thermalHeadroom: Float?,
)

/**
 * One `sensors.csv` row (FR-45, ADR-0008). The latest reading of each sensor at the tick; null when the sensor is
 * missing or has not reported yet. `pressureTempC` is the barometer's own temperature (docs/test/g0-blazer.md).
 */
data class SensorSample(
    val elapsedNs: Long,
    val pressureHpa: Float?,
    val lux: Float?,
    val magnetic: Vector3?,
    val pressureTempC: Float?,
    val power: PowerState,
) {
    fun values(): List<Any?> = listOf(
        elapsedNs,
        pressureHpa,
        lux,
        magnetic?.x,
        magnetic?.y,
        magnetic?.z,
        pressureTempC,
        power.batteryTempC,
        power.batteryPercent,
        power.thermalStatus,
        power.thermalHeadroom,
    )

    companion object {
        val HEADER = listOf(
            "elapsed_ns",
            "pressure_hpa",
            "light_lux",
            "mag_x_ut",
            "mag_y_ut",
            "mag_z_ut",
            "pressure_temp_c",
            "battery_temp_c",
            "battery_pct",
            "thermal_status",
            "thermal_headroom",
        )
    }
}

/**
 * Latest value of each streaming sensor. Sensor callbacks write, the 1 Hz tick reads; the magnetometer reports
 * no slower than every 0.8 s (docs/test/g0-blazer.md), so the tick down-samples by taking the latest.
 */
class SensorLatch {
    @Volatile var pressureHpa: Float? = null

    @Volatile var lux: Float? = null

    @Volatile var magnetic: Vector3? = null

    @Volatile var pressureTempC: Float? = null

    fun sample(elapsedNs: Long, power: PowerState) =
        SensorSample(elapsedNs, pressureHpa, lux, magnetic, pressureTempC, power)
}

/**
 * The first tick strictly after [nowNs] on the grid `startNs + k * periodNs`. Ticks stay on the grid so the rate
 * does not drift; a late tick skips the missed slots instead of catching up with a burst of rows.
 */
fun nextTickNs(startNs: Long, nowNs: Long, periodNs: Long): Long {
    require(periodNs > 0) { "period must be positive" }
    if (nowNs < startNs) return startNs
    return startNs + ((nowNs - startNs) / periodNs + 1) * periodNs
}
