// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.core.telemetry.log

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.BatteryManager
import android.os.PowerManager
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.nanoseconds

/**
 * FR-45: writes one [SensorSample] per second to `sensors.csv` while the dive lock is on (ADR-0008). Sensors
 * stream into a [SensorLatch]; the tick reads the latest values, battery and thermal state.
 */
class SensorRecorder(context: Context, private val log: CsvLog) {
    private val appContext = context.applicationContext
    private val sensors = checkNotNull(appContext.getSystemService(SensorManager::class.java))
    private val power = checkNotNull(appContext.getSystemService(PowerManager::class.java))
    private val battery = checkNotNull(appContext.getSystemService(BatteryManager::class.java))
    private val latch = SensorLatch()
    private var job: Job? = null

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val v = event.values
            when {
                event.sensor.type == Sensor.TYPE_PRESSURE -> latch.pressureHpa = v[0]
                event.sensor.type == Sensor.TYPE_LIGHT -> latch.lux = v[0]
                event.sensor.type == Sensor.TYPE_MAGNETIC_FIELD -> latch.magnetic = Vector3(v[0], v[1], v[2])
                event.sensor.stringType == PRESSURE_TEMP_TYPE -> latch.pressureTempC = v[0]
            }
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
    }

    /** The sensors this device has; a missing one leaves its columns empty. */
    fun available(): List<Sensor> = listOfNotNull(
        sensors.getDefaultSensor(Sensor.TYPE_PRESSURE),
        sensors.getDefaultSensor(Sensor.TYPE_LIGHT),
        sensors.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD),
        sensors.getSensorList(Sensor.TYPE_ALL).firstOrNull { it.stringType == PRESSURE_TEMP_TYPE },
    )

    fun start(scope: CoroutineScope) {
        if (job != null) return
        available().forEach { sensors.registerListener(listener, it, SAMPLING_US) }
        job = scope.launch(Dispatchers.IO) {
            val startNs = SystemClock.elapsedRealtimeNanos()
            while (isActive) {
                log.append(latch.sample(SystemClock.elapsedRealtimeNanos(), powerState()).values())
                val now = SystemClock.elapsedRealtimeNanos()
                delay((nextTickNs(startNs, now, PERIOD_NS) - now).nanoseconds)
            }
        }
    }

    suspend fun stop() {
        job?.cancelAndJoin()
        job = null
        sensors.unregisterListener(listener)
    }

    private fun powerState(): PowerState {
        // A null receiver returns the sticky system battery broadcast (docs/test/m4-instrumented.md).
        val sticky = appContext.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            Context.RECEIVER_NOT_EXPORTED,
        )
        val tenths = sticky?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        val capacity = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return PowerState(
            batteryTempC = tenths?.takeIf { it != Int.MIN_VALUE }?.let { it / TENTHS },
            batteryPercent = capacity.takeIf { it != Int.MIN_VALUE },
            thermalStatus = power.currentThermalStatus,
            thermalHeadroom = power.getThermalHeadroom(0),
        )
    }

    private companion object {
        const val PERIOD_NS = 1_000_000_000L
        const val SAMPLING_US = 1_000_000
        const val TENTHS = 10f

        /** Barometer temperature found by the G0 probe (docs/test/g0-blazer.md); absent on other devices. */
        const val PRESSURE_TEMP_TYPE = "com.google.sensor.pressure_temp"
    }
}
