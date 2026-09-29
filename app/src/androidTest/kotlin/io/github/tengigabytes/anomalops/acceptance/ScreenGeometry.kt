// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.acceptance

import android.app.Activity
import androidx.compose.ui.geometry.Rect
import io.github.tengigabytes.anomalops.layout.MIN_KEY_DP
import io.github.tengigabytes.anomalops.layout.SAFE_MARGIN_MM

private const val MM_PER_INCH = 25.4f

/** Layout rounds millimetres to whole pixels; one pixel is about 0.05 mm on the Pixel 10 Pro. */
private const val TOLERANCE_MM = 0.1f
private const val TOLERANCE_DP = 0.5f

/**
 * FR-55 / FR-52 / 07-housing checks on laid-out rectangles in window pixels, converted with the display's
 * physical xdpi / ydpi (FR-55) and its density (dp). Needs a window that covers the whole display, which
 * [coversDisplay] reports, so window coordinates are screen coordinates.
 */
class ScreenGeometry(activity: Activity) {
    private val metrics = activity.resources.displayMetrics
    private val window = activity.windowManager.currentWindowMetrics.bounds
    private val display = activity.windowManager.maximumWindowMetrics.bounds

    val coversDisplay: Boolean get() = window == display

    val widthPx: Int get() = display.width()

    val widthMm: Float get() = display.width() / metrics.xdpi * MM_PER_INCH
    val heightMm: Float get() = display.height() / metrics.ydpi * MM_PER_INCH

    /** Distance in mm from [r] to the nearest screen edge. */
    fun edgeMm(r: Rect): Float = minOf(
        r.left / metrics.xdpi * MM_PER_INCH,
        r.top / metrics.ydpi * MM_PER_INCH,
        (display.width() - r.right) / metrics.xdpi * MM_PER_INCH,
        (display.height() - r.bottom) / metrics.ydpi * MM_PER_INCH,
    )

    fun widthDp(r: Rect): Float = r.width / metrics.density

    fun heightDp(r: Rect): Float = r.height / metrics.density

    /** Every FR-55 / 07-housing rule [controls] break; the shutter strip is also held to [minShutterDp]. */
    fun problems(controls: Map<String, Rect>, shutter: String, minShutterDp: Float): List<String> = buildList {
        controls.forEach { (name, r) ->
            if (edgeMm(r) < SAFE_MARGIN_MM - TOLERANCE_MM) add("$name is %.2f mm from an edge".format(edgeMm(r)))
            if (widthDp(r) < MIN_KEY_DP - TOLERANCE_DP || heightDp(r) < MIN_KEY_DP - TOLERANCE_DP) {
                add("$name is %.1f x %.1f dp".format(widthDp(r), heightDp(r)))
            }
        }
        controls[shutter]?.let { if (widthDp(it) < minShutterDp) add("shutter is %.1f dp wide".format(widthDp(it))) }
        val entries = controls.entries.toList()
        entries.forEachIndexed { i, (a, ra) ->
            entries.drop(i + 1).filter { (_, rb) -> ra.overlaps(rb) }.forEach { (b, _) -> add("$a overlaps $b") }
        }
    }
}
