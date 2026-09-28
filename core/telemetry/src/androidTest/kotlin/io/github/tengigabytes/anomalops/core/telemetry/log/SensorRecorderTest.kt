// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.core.telemetry.log

import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * FR-45 on the device: one minute of `sensors.csv` in the app's external files directory (ADR-0008). Checks the
 * four files, rising timestamps, at least 98% of the expected rows, and which columns the device fills. Only the
 * summary is logged; the session directory is deleted afterwards.
 */
@RunWith(AndroidJUnit4::class)
class SensorRecorderTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val root = requireNotNull(context.getExternalFilesDir(null))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var session: File? = null

    @After
    fun tearDown() {
        scope.cancel()
        session?.deleteRecursively()
    }

    @Test
    fun fr45_oneMinuteOfSensorRows() = runBlocking<Unit> {
        val info = SessionInfo(
            "DIVE_TEST_${SystemClock.elapsedRealtime()}",
            System.currentTimeMillis(),
            SystemClock.elapsedRealtimeNanos(),
            Build.DEVICE,
            "test",
        )
        val log = DiveLog.open(root, info, touchHeader = listOf("elapsed_ns"), captureHeader = listOf("elapsed_ns"))
        session = log.dir
        val recorder = SensorRecorder(context, log.sensors)
        Log.i(TAG, "FR-45 sensors: ${recorder.available().joinToString { it.stringType }}")
        recorder.start(scope)
        delay(RECORD_MS)
        recorder.stop()
        log.close()

        assertEquals(DIVE_FILES, log.dir.list().orEmpty().sorted())
        val lines = File(log.dir, DiveLog.SENSORS_FILE).readLines()
        assertEquals(SensorSample.HEADER.joinToString(","), lines.first())
        val rows = lines.drop(1).map { it.split(",") }
        val times = rows.map { it[0].toLong() }
        assertTrue("timestamps rise", times.zipWithNext().all { (a, b) -> b > a })
        val gapsMs = times.zipWithNext { a, b -> (b - a) / NS_PER_MS }.sorted()
        val expected = ((times.last() - times.first()) / NS_PER_S).toInt() + 1
        Log.i(
            TAG,
            "FR-45 rows=${rows.size} expected=$expected gap median=%.1f min=%.1f max=%.1f ms".format(
                gapsMs[gapsMs.size / 2],
                gapsMs.first(),
                gapsMs.last(),
            ),
        )
        val filled = SensorSample.HEADER.indices.associate { c ->
            SensorSample.HEADER[c] to rows.count { it.getOrElse(c) { "" }.isNotEmpty() }
        }
        Log.i(TAG, "FR-45 filled rows per column: $filled")
        Log.i(TAG, "FR-45 last row: ${lines.last()}")
        assertTrue("rows ${rows.size} < 98% of $expected", rows.size >= expected * COMPLETENESS)
        REQUIRED_COLUMNS.forEach { column ->
            val count = filled.getValue(column)
            assertTrue("$column filled in $count of ${rows.size} rows", count >= rows.size - STARTUP_ROWS)
        }
    }

    private companion object {
        const val TAG = "M4Acceptance"
        const val RECORD_MS = 60_000L
        const val COMPLETENESS = 0.98
        const val NS_PER_MS = 1e6
        const val NS_PER_S = 1_000_000_000L

        /** The first tick fires at once, before a sensor may have reported. */
        const val STARTUP_ROWS = 1
        val DIVE_FILES = listOf("captures.csv", "sensors.csv", "session.json", "touches.csv")

        /** Sensors G0 found on the device, and the battery and thermal state every Android device has. */
        val REQUIRED_COLUMNS = listOf(
            "pressure_hpa",
            "mag_x_ut",
            "battery_temp_c",
            "battery_pct",
            "thermal_status",
        )
    }
}
