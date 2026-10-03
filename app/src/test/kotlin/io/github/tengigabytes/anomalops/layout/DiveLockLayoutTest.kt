// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.layout

import io.github.tengigabytes.anomalops.capture.ShootingMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiveLockLayoutTest {
    // Pixel 10 Pro: 1280 x 2856 px at 495 ppi (public specs). UNVERIFIED(G1): check xdpi / ydpi on the device.
    private val widthMm = 1280f / 495f * 25.4f
    private val heightMm = 2856f / 495f * 25.4f

    // UNVERIFIED(G1): the nominal 160 dp per inch; the real ratio follows the display-size setting.
    private val nominalMmPerDp = 25.4f / 160f
    private val layout = DiveLockLayout(widthMm, heightMm)

    @Test
    fun fr55_pixel10ProLayoutBreaksNoRule() {
        assertEquals(emptyList<String>(), layout.violations(nominalMmPerDp))
    }

    @Test
    fun fr12_modesAndTheMergeSwitchRunDownTheRightLongEdgeInOrderWithoutScrolling() {
        val modes = layout.slots.filter { it.control == Control.MODE }
        assertEquals(ShootingMode.entries.toList(), modes.map { it.mode })
        val column = modes + layout.slot(Control.MERGE)
        assertTrue(column.all { it.rect.x == column.first().rect.x })
        assertTrue(column.zipWithNext().all { (a, b) -> b.rect.y > a.rect.bottom })
        assertTrue(column.last().rect.bottom <= layout.preview.bottom)
        // The switch sits on the last of the five rows, level with the dive-light key, a free row above it.
        assertEquals(layout.slot(Control.LIGHT).rect.y, layout.slot(Control.MERGE).rect.y, 0f)
    }

    @Test
    fun fr52_shutterSpansTheShortEdgeLessBothMarginsAndIsTheLargestControl() {
        val shutter = layout.slot(Control.SHUTTER).rect
        assertEquals(widthMm - 2 * SAFE_MARGIN_MM, shutter.width, 0.001f)
        assertEquals(heightMm - SAFE_MARGIN_MM, shutter.bottom, 0.001f)
        val area = shutter.width * shutter.height
        assertTrue(layout.slots.filter { it.control != Control.SHUTTER }.all { it.rect.width * it.rect.height < area })
    }

    @Test
    fun layoutMatchesTheDocumentedCoordinates() {
        // docs/product/dive-lock-layout.md, section 3.
        assertEquals(87.57f, layout.preview.height, 0.01f)
        assertEquals(42.68f, layout.slot(Control.MODE).rect.x, 0.01f)
        assertEquals(89.57f, layout.slot(Control.ZOOM).rect.y, 0.01f)
        assertEquals(27.34f, layout.slot(Control.HALF_PRESS).rect.x, 0.01f)
        assertEquals(117.57f, layout.slot(Control.SHUTTER).rect.y, 0.01f)
        assertEquals(16.98f, layout.slot(Control.SHUTTER).rect.height, 0.01f)
    }

    @Test
    fun v11SlotsAreReservedButNotShownInV10() {
        val later = layout.slots.filter { it.control.since == Release.V1_1 }.map { it.control }
        assertEquals(listOf(Control.ZOOM, Control.HALF_PRESS, Control.VIDEO), later)
    }

    @Test
    fun aScreenTooShortForTheRowsIsReported() {
        val short = DiveLockLayout(widthMm, widthMm * 4 / 3 + 40f)
        assertTrue(short.violations(nominalMmPerDp).any { it.startsWith("SHUTTER") })
    }
}
