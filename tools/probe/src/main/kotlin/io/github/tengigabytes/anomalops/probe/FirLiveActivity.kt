// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.probe

import android.app.Activity
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.WindowManager
import android.widget.ScrollView
import android.widget.TextView
import java.util.concurrent.ConcurrentHashMap

/**
 * Live view of temperature-like sensors for bench tests (point the phone at warm / cold water).
 * Shows the latest values on screen and logs one line per second with tag AnomalopsProbe:
 * `adb logcat -s AnomalopsProbe:I`. Launch: `adb shell am start -n <pkg>/.FirLiveActivity`.
 */
class FirLiveActivity :
    Activity(),
    SensorEventListener {
    private val latest = ConcurrentHashMap<String, FloatArray>()
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var sensorManager: SensorManager
    private lateinit var output: TextView
    private var ticks = 0

    private val refresh = object : Runnable {
        override fun run() {
            val text = render()
            output.text = text
            if (ticks++ % TICKS_PER_LOG == 0) Log.i(TAG, "live " + text.replace('\n', ' '))
            ui.postDelayed(this, REFRESH_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        sensorManager = getSystemService(SensorManager::class.java)
        output = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, TEXT_SP)
            setPadding(PADDING, PADDING, PADDING, PADDING)
        }
        setContentView(ScrollView(this).apply { addView(output) })
    }

    override fun onResume() {
        super.onResume()
        SensorSampler(sensorManager).targets().forEach {
            sensorManager.registerListener(this, it, it.minDelay.coerceAtLeast(0))
        }
        ui.post(refresh)
    }

    override fun onPause() {
        sensorManager.unregisterListener(this)
        ui.removeCallbacks(refresh)
        super.onPause()
    }

    override fun onSensorChanged(event: SensorEvent) {
        latest[event.sensor.stringType.substringAfterLast('.')] = event.values.clone()
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit

    private fun render(): String {
        if (latest.isEmpty()) return "Waiting for sensor events…"
        return latest.toSortedMap().entries.joinToString("\n") { (name, values) ->
            "$name = " + values.joinToString(", ") { "%.2f".format(it) }
        }
    }

    private companion object {
        const val TAG = "AnomalopsProbe"
        const val REFRESH_MS = 250L
        const val TICKS_PER_LOG = 4
        const val TEXT_SP = 20f
        const val PADDING = 32
    }
}
