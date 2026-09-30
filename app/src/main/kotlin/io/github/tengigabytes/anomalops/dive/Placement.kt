// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.dive

import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.tengigabytes.anomalops.layout.MmRect

private const val MM_PER_INCH = 25.4f

/**
 * FR-55: millimetres on this display, from the physical `xdpi` / `ydpi` rather than the logical density, so the
 * 12 mm margin is 12 mm whatever the display-size setting.
 */
class MmScale(private val xdpi: Float, private val ydpi: Float, private val density: Float) {
    fun xDp(mm: Float): Dp = (mm / MM_PER_INCH * xdpi / density).dp

    fun yDp(mm: Float): Dp = (mm / MM_PER_INCH * ydpi / density).dp

    fun widthMm(px: Int): Float = px / xdpi * MM_PER_INCH

    fun heightMm(px: Int): Float = px / ydpi * MM_PER_INCH

    /** Millimetres per dp across the screen, for the 64 dp and 88 dp checks. */
    val mmPerDp: Float get() = density / xdpi * MM_PER_INCH
}

@Composable
fun rememberMmScale(): MmScale {
    val metrics = LocalContext.current.resources.displayMetrics
    return remember(metrics.xdpi, metrics.ydpi, metrics.density) {
        MmScale(metrics.xdpi, metrics.ydpi, metrics.density)
    }
}

/** Puts a child of a full-screen box at [rect]. */
fun Modifier.place(rect: MmRect, scale: MmScale): Modifier =
    offset(scale.xDp(rect.x), scale.yDp(rect.y)).size(scale.xDp(rect.width), scale.yDp(rect.height))
