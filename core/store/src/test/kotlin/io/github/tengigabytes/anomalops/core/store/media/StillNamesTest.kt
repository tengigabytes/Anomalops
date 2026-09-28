// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.core.store.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class StillNamesTest {
    private val taipei = ZoneId.of("Asia/Taipei")

    @Test
    fun stemIsLocalTimeToTheMillisecond() {
        val at = ZonedDateTime.of(2026, 9, 28, 17, 2, 41, 51_000_000, taipei)
        assertEquals("ANM_20260928_170241_051", StillNames.stem(at))
        assertEquals("ANM_20260928_170241_051.jpg", StillNames.stillName(StillNames.stem(at)))
    }

    @Test
    fun stemsSortInCaptureOrder() {
        val first = ZonedDateTime.of(2026, 9, 28, 9, 59, 59, 999_000_000, taipei)
        val second = first.plusNanos(1_000_000)
        assertTrue(StillNames.stem(first) < StillNames.stem(second))
    }

    @Test
    fun adr0004_stillsGoToPicturesAnomalops() {
        assertEquals("Pictures/Anomalops", StillNames.RELATIVE_PATH)
    }
}
