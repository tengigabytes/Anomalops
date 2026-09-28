// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.probe

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.BatteryManager
import android.os.PowerManager
import org.json.JSONObject

/** Sensor list and thermal state for FR-45 logging and ADR-0008 (which sensors exist, can they run at 1 Hz). */
internal object SensorProbe {
    private val fr45Types = setOf(
        Sensor.TYPE_PRESSURE,
        Sensor.TYPE_LIGHT,
        Sensor.TYPE_MAGNETIC_FIELD,
        Sensor.TYPE_AMBIENT_TEMPERATURE,
    )

    fun probe(context: Context): JSONObject {
        val sensorManager = context.getSystemService(SensorManager::class.java)
        val sensors = sensorManager.getSensorList(Sensor.TYPE_ALL).map { s ->
            jsonOf(
                "name" to s.name,
                "vendor" to s.vendor,
                "type" to s.type,
                "stringType" to s.stringType,
                "fr45Relevant" to (s.type in fr45Types),
                "minDelayUs" to s.minDelay,
                "maxDelayUs" to s.maxDelay,
                "reportingMode" to s.reportingMode,
                "resolution" to s.resolution.finiteOrString(),
                "maximumRange" to s.maximumRange.finiteOrString(),
                "powerMa" to s.power.finiteOrString(),
                "fifoMaxEventCount" to s.fifoMaxEventCount,
                "wakeUp" to s.isWakeUpSensor,
            )
        }
        val missing = fr45Types.filter { sensorManager.getDefaultSensor(it) == null }
        return jsonOf(
            "sensors" to sensors.toJsonArray(),
            "fr45MissingTypes" to missing.toJsonArray(),
            "thermal" to thermal(context),
        )
    }

    private fun thermal(context: Context): JSONObject {
        val power = context.getSystemService(PowerManager::class.java)
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        return jsonOf(
            "currentThermalStatus" to power.currentThermalStatus,
            "thermalHeadroom10s" to power.getThermalHeadroom(10).finiteOrString(),
            "batteryTemperatureTenthsC" to battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE),
        )
    }
}
