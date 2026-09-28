// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.core.telemetry.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.LocalDateTime

class DiveLogTest {
    @get:Rule val temp = TemporaryFolder()

    private val info = SessionInfo(
        id = SessionInfo.idAt(LocalDateTime.of(2026, 10, 3, 9, 5, 7)),
        startUtcMs = 1_790_989_507_000L,
        startElapsedNs = 123_456_789_000L,
        device = "test\"device",
        appVersion = "0.0.1",
    )

    @Test
    fun csvQuotesOnlyWhatNeedsItAndFormatsNumbersWithoutLocale() {
        assertEquals(
            "1,,1013.250,\"a,b\",\"say \"\"hi\"\"\",",
            Csv.row(listOf(1, null, 1013.25f, "a,b", "say \"hi\"", Float.NaN)),
        )
    }

    @Test
    fun fr45_sessionHasAllFourFilesAndOneHeaderEach() {
        val log = open()
        log.sensors.append(sample(1_000L).values())
        log.close()
        val files = log.dir.list()!!.sorted()
        assertEquals(listOf("captures.csv", "sensors.csv", "session.json", "touches.csv"), files)
        assertEquals(File(File(temp.root, "dives"), "DIVE_20261003_090507"), log.dir)
        assertEquals(SensorSample.HEADER.joinToString(","), lines(log, DiveLog.SENSORS_FILE).first())
        assertEquals(listOf("elapsed_ns,x,y"), lines(log, DiveLog.TOUCHES_FILE))
    }

    @Test
    fun adr0006_reopeningAfterACrashAppendsWithoutASecondHeaderOrNewPairing() {
        open().use { it.sensors.append(sample(1_000L).values()) }
        val reopened = open(info.copy(startElapsedNs = 999L))
        reopened.use { it.sensors.append(sample(2_000L).values()) }
        val rows = lines(reopened, DiveLog.SENSORS_FILE)
        assertEquals(3, rows.size)
        assertTrue(rows[2].startsWith("2000,"))
        val session = File(reopened.dir, DiveLog.SESSION_FILE).readText()
        assertTrue(session.contains("\"start_elapsed_ns\": 123456789000,"))
        assertTrue(session.contains("\"device\": \"test\\\"device\","))
        assertTrue(session.contains("\"start_utc\": \"2026-10-03T01:05:07Z\","))
    }

    @Test(expected = IllegalArgumentException::class)
    fun aRowWithTheWrongFieldCountIsRejected() {
        open().use { it.touches.append(listOf(1L)) }
    }

    @Test
    fun missingSensorsLeaveEmptyColumns() {
        val power = PowerState(
            batteryTempC = 31.5f,
            batteryPercent = 87,
            thermalStatus = 0,
            thermalHeadroom = Float.NaN,
        )
        val latch = SensorLatch().apply { pressureHpa = 1013.2f }
        assertEquals("5,1013.200,,,,,,31.500,87,0,", Csv.row(latch.sample(5L, power).values()))
    }

    @Test
    fun ticksStayOnTheOneSecondGridAndSkipMissedSlots() {
        val second = 1_000_000_000L
        assertEquals(10 * second + second, nextTickNs(10 * second, 10 * second, second))
        assertEquals(10 * second + 2 * second, nextTickNs(10 * second, 11 * second + 300, second))
        assertEquals("late by 3.5 slots", 10 * second + 5 * second, nextTickNs(10 * second, 14 * second + 5, second))
        assertEquals(10 * second, nextTickNs(10 * second, 9 * second, second))
    }

    private fun open(session: SessionInfo = info) = DiveLog.open(
        temp.root,
        session,
        touchHeader = listOf("elapsed_ns", "x", "y"),
        captureHeader = listOf("elapsed_ns"),
    )

    private fun lines(log: DiveLog, name: String) = File(log.dir, name).readLines()

    private fun sample(elapsedNs: Long) = SensorSample(
        elapsedNs,
        pressureHpa = 1013.25f,
        lux = 120f,
        magnetic = Vector3(1f, 2f, 3f),
        pressureTempC = 28f,
        power = PowerState(30f, 90, 0, 0.4f),
    )
}
