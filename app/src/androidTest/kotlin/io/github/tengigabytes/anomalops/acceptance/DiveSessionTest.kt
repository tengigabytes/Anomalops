// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.acceptance

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.tengigabytes.anomalops.core.telemetry.log.DiveLog
import io.github.tengigabytes.anomalops.lock.DiveSession
import io.github.tengigabytes.anomalops.lock.PrefsLockStore
import io.github.tengigabytes.anomalops.lock.SessionRows
import io.github.tengigabytes.anomalops.touch.TouchVerdict
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * FR-45 in the app (ADR-0008, docs/test/m3-test-plan.md): a session writes the `:app` columns of `touches.csv` and
 * `captures.csv`, and reopening the same ID, as a crash restart does (NFR-1), appends to the same files and keeps
 * the first `session.json`. The session directory is deleted afterwards; no photos are taken.
 */
@RunWith(AndroidJUnit4::class)
class DiveSessionTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val root = requireNotNull(context.getExternalFilesDir(null))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val id = "DIVE_TEST_${SystemClock.elapsedRealtime()}"
    private val dir = File(File(root, "dives"), id)

    @After
    fun tearDown() {
        scope.cancel()
        dir.deleteRecursively()
    }

    @Test
    fun fr45_sessionWritesRowsAndARestartAppends() = runBlocking<Unit> {
        val first = DiveSession.open(context, id, scope)
        first.touch(SessionRows.Action.DOWN, 100f, 200f, TouchVerdict.ACCEPTED)
        first.touch(SessionRows.Action.DOWN, 101f, 200f, TouchVerdict.REPEAT)
        first.touch(SessionRows.Action.UP, 101f, 201f, null)
        first.capture(capture())
        delay(RECORD_MS)
        first.close()
        val sessionJson = File(dir, DiveLog.SESSION_FILE).readText()

        val second = DiveSession.open(context, id, scope)
        second.touch(SessionRows.Action.DOWN, 300f, 400f, TouchVerdict.ACCEPTED)
        delay(RECORD_MS)
        second.close()

        assertEquals(sessionJson, File(dir, DiveLog.SESSION_FILE).readText())
        val touches = lines(DiveLog.TOUCHES_FILE)
        assertEquals(SessionRows.TOUCH_HEADER.joinToString(","), touches.first())
        assertEquals(1, touches.count { it == touches.first() })
        assertEquals(listOf("down", "down", "up", "down"), touches.drop(1).map { it.split(",")[1] })
        assertEquals(listOf("false", "true", "", "false"), touches.drop(1).map { it.split(",")[4] })
        val captures = lines(DiveLog.CAPTURES_FILE)
        assertEquals(SessionRows.CAPTURE_HEADER.joinToString(","), captures.first())
        assertEquals(2, captures.size)
        val sensorTimes = lines(DiveLog.SENSORS_FILE).drop(1).map { it.split(",")[0].toLong() }
        Log.i(TAG, "FR-45 app session: ${touches.size - 1} touches, ${sensorTimes.size} sensor rows over two opens")
        assertTrue("sensor rows from both opens", sensorTimes.size >= 2 * MIN_ROWS_PER_OPEN)
        assertTrue("sensor timestamps rise across the restart", sensorTimes.zipWithNext().all { (a, b) -> b > a })
    }

    @Test
    fun nfr1_lockStoreSurvivesANewInstanceAndClears() {
        PrefsLockStore(context).save(id)
        assertEquals(id, PrefsLockStore(context).load())
        PrefsLockStore(context).save(null)
        assertNull(PrefsLockStore(context).load())
    }

    private fun lines(name: String): List<String> = File(dir, name).readLines().filter { it.isNotEmpty() }

    private fun capture() = SessionRows.Capture(
        elapsedNs = SystemClock.elapsedRealtimeNanos(),
        kind = "still",
        sensorTimestampNs = null,
        preset = "SNAPSHOT",
        lens = null,
        depthBand = "SHALLOW",
        filter = "NONE",
        diveLight = false,
        format = null,
        frames = 1,
        requestExposureNs = null,
        requestIso = null,
        isoClamped = null,
        requestGains = null,
        reportedExposureNs = null,
        reportedIso = null,
        reportedGains = null,
        file = null,
    )

    private companion object {
        const val TAG = "M3Acceptance"
        const val RECORD_MS = 3_000L

        /** 3 s at 1 Hz gives 3 or 4 rows; allow one missing at each start. */
        const val MIN_ROWS_PER_OPEN = 2
    }
}
