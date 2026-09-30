// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.dive

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import io.github.tengigabytes.anomalops.R
import io.github.tengigabytes.anomalops.theme.DivePalette
import kotlinx.coroutines.delay

private const val READ_EVERY_MS = 10_000L
private const val TENTHS = 10f

/**
 * 07-housing: the warning threshold for body temperature, the system's thermal status at MODERATE or above
 * (maintainer decision of 2026-09-29, requirements section 9); NFR-2 asks the dive to stay at LIGHT or below.
 * UNVERIFIED(G2): whether the warning comes at a useful moment in the water bath.
 */
private const val HOT_FROM = PowerManager.THERMAL_STATUS_MODERATE

/** Battery level and temperature (ADR-0008: the battery stands in for body temperature) and the warning. */
data class Readings(val batteryPct: Int, val temperatureC: Float, val hot: Boolean)

@Composable
fun rememberReadings(): Readings? {
    val context = LocalContext.current
    var readings by remember { mutableStateOf<Readings?>(null) }
    LaunchedEffect(context) {
        while (true) {
            readings = read(context)
            delay(READ_EVERY_MS)
        }
    }
    return readings
}

private fun read(context: Context): Readings {
    val battery = context.getSystemService(BatteryManager::class.java)
    val power = context.getSystemService(PowerManager::class.java)
    // A sticky broadcast: registering with a null receiver just returns the last value.
    val sticky = context.registerReceiver(
        null,
        IntentFilter(Intent.ACTION_BATTERY_CHANGED),
        Context.RECEIVER_NOT_EXPORTED,
    )
    val tenths = sticky?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
    return Readings(
        batteryPct = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY),
        temperatureC = tenths / TENTHS,
        hot = power.currentThermalStatus >= HOT_FROM,
    )
}

/**
 * The read-only bottom band (docs/product/dive-lock-layout.md, section 3), black, 16 sp (FR-41): battery and
 * temperature with the warning, lock state (FR-45 logging while locked), and the conditions line, which [note]
 * replaces for a moment (a kept RAW, a clamped ISO, a camera error).
 */
@Composable
fun StatusBand(
    readings: Readings?,
    locked: Boolean,
    conditions: String,
    note: String?,
    sidePadding: Dp,
    modifier: Modifier = Modifier,
) {
    val white = rgb(DivePalette.TEXT)
    val grey = rgb(DivePalette.SECONDARY)
    val alert = rgb(DivePalette.ALERT)
    Column(
        modifier.background(rgb(DivePalette.BACKGROUND)).padding(horizontal = sidePadding),
        verticalArrangement = Arrangement.Center,
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            val power = readings?.let { stringResource(R.string.status_battery, it.batteryPct, it.temperatureC) }
            val hot = readings?.hot == true
            val left = listOfNotNull(power, stringResource(R.string.status_hot).takeIf { hot }).joinToString(" · ")
            Text(left, color = if (hot) alert else white, fontSize = 16.sp, maxLines = 1)
            if (locked) {
                Text("● " + stringResource(R.string.status_logging), color = alert, fontSize = 16.sp, maxLines = 1)
            } else {
                Text(stringResource(R.string.status_unlocked), color = grey, fontSize = 16.sp, maxLines = 1)
            }
        }
        Text(
            note ?: conditions,
            color = if (note == null) grey else white,
            fontSize = 16.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
