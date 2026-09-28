// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.probe

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import org.json.JSONArray
import org.json.JSONObject

/**
 * Samples temperature-like sensors (including the MLX90632 FIR thermometer) and the barometer for a short time.
 * The meaning of each value index of vendor sensors is unknown, so every index is recorded as-is.
 * Purpose: can the FIR sensor estimate water temperature through the housing window? (ADR-0008 follow-up)
 */
internal class SensorSampler(private val sensorManager: SensorManager) {

    fun targets(): List<Sensor> = sensorManager.getSensorList(Sensor.TYPE_ALL).filter { isTarget(it) }

    fun sample(durationMs: Long, handler: Handler): JSONArray {
        val collectors = targets().map { Collector(it) }
        collectors.forEach { sensorManager.registerListener(it, it.sensor, it.sensor.minDelay.coerceAtLeast(0), handler) }
        try {
            Thread.sleep(durationMs)
        } finally {
            collectors.forEach { sensorManager.unregisterListener(it) }
        }
        return collectors.map { it.toJson(durationMs) }.toJsonArray()
    }

    private class Collector(val sensor: Sensor) : SensorEventListener {
        private val lock = Any()
        private var count = 0
        private var firstTimestampNs = 0L
        private var lastTimestampNs = 0L
        private val min = mutableListOf<Float>()
        private val max = mutableListOf<Float>()
        private val sum = mutableListOf<Double>()
        private val firstEvents = JSONArray()

        override fun onSensorChanged(event: SensorEvent) = synchronized(lock) {
            if (count == 0) firstTimestampNs = event.timestamp
            lastTimestampNs = event.timestamp
            count++
            event.values.forEachIndexed { i, v ->
                if (i >= min.size) {
                    min += v
                    max += v
                    sum += 0.0
                }
                min[i] = minOf(min[i], v)
                max[i] = maxOf(max[i], v)
                sum[i] += v.toDouble()
            }
            if (firstEvents.length() < KEPT_EVENTS) firstEvents.put(event.values.toJson())
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit

        fun toJson(durationMs: Long): JSONObject = synchronized(lock) {
            jsonOf(
                "name" to sensor.name,
                "stringType" to sensor.stringType,
                "requestedPeriodUs" to sensor.minDelay,
                "durationMs" to durationMs,
                "events" to count,
                "spanMs" to (lastTimestampNs - firstTimestampNs) / NANOS_PER_MS,
                "valueCount" to min.size,
                "min" to min.toFloatArray().toJson(),
                "max" to max.toFloatArray().toJson(),
                "mean" to sum.map { (it / count.coerceAtLeast(1)).toFloat().finiteOrString() }.toJsonArray(),
                "firstEvents" to firstEvents,
            )
        }
    }

    companion object {
        private const val KEPT_EVENTS = 3
        private const val NANOS_PER_MS = 1_000_000L

        fun isTarget(sensor: Sensor): Boolean =
            sensor.type == Sensor.TYPE_PRESSURE ||
                sensor.type == Sensor.TYPE_AMBIENT_TEMPERATURE ||
                sensor.stringType.contains("temperature", ignoreCase = true) ||
                sensor.stringType.endsWith("_temp", ignoreCase = true)
    }
}
