// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.lock

import io.github.tengigabytes.anomalops.touch.TouchVerdict
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionRowsTest {
    @Test
    fun fr45_touchRowsMatchTheHeader() {
        val down = SessionRows.touch(1_000, SessionRows.Action.DOWN, 10f, 20f, TouchVerdict.REPEAT)
        val up = SessionRows.touch(2_000, SessionRows.Action.UP, 10f, 20f, null)
        assertEquals(SessionRows.TOUCH_HEADER.size, down.size)
        assertEquals(listOf(1_000L, "down", 10f, 20f, true), down)
        assertEquals(listOf(2_000L, "up", 10f, 20f, null), up)
    }

    @Test
    fun fr45_captureRowMatchesTheHeader() {
        val capture = SessionRows.Capture(
            elapsedNs = 5,
            kind = "still",
            sensorTimestampNs = 4,
            preset = "SNAPSHOT",
            lens = "2",
            depthBand = "SHALLOW",
            filter = "NONE",
            diveLight = false,
            format = "JPEG_R",
            frames = 1,
            requestExposureNs = 8_000_000,
            requestIso = 711,
            isoClamped = false,
            requestGains = listOf(1.8, 1.0, 1.0, 2.1),
            reportedExposureNs = 7_998_240,
            reportedIso = 711,
            reportedGains = null,
            file = "ANM_20260929_202535.jpg",
        )
        val row = capture.row()
        assertEquals(SessionRows.CAPTURE_HEADER.size, row.size)
        assertEquals("1.8 1.0 1.0 2.1", row[SessionRows.CAPTURE_HEADER.indexOf("request_wb_gains")])
        assertEquals(null, row[SessionRows.CAPTURE_HEADER.indexOf("reported_wb_gains")])
    }
}
