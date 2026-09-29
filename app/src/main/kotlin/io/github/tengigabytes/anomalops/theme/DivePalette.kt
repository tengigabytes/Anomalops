// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.theme

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** NFR-5: every text and icon colour against its background. */
const val MIN_CONTRAST = 7.0

/**
 * NFR-5 colours of the dive-lock screen as 0xRRGGBB (docs/product/dive-lock-layout.md, section 4): black, white
 * and amber only, no blue for state. Keys have an opaque dark fill, so their contrast does not depend on the preview.
 */
object DivePalette {
    const val BACKGROUND = 0x000000
    const val KEY_FILL = 0x0D0D0D
    const val TEXT = 0xFFFFFF
    const val SECONDARY = 0xB8B8B8
    const val SELECTED_FILL = 0xFFC933
    const val SELECTED_TEXT = 0x000000

    /** Recording (FR-45) and warnings such as rising body temperature (07-housing). */
    const val ALERT = 0xFF8A7F

    /** Every foreground / background pair the screen draws. */
    val pairs: List<Pair<Int, Int>> = listOf(
        TEXT to BACKGROUND,
        TEXT to KEY_FILL,
        SECONDARY to BACKGROUND,
        SECONDARY to KEY_FILL,
        SELECTED_TEXT to SELECTED_FILL,
        ALERT to BACKGROUND,
        ALERT to KEY_FILL,
    )

    /** Colours that carry state (selected preset, recording, warnings); NFR-5 rules out blue for these. */
    val stateColors: List<Int> = listOf(SELECTED_FILL, ALERT)
}

private const val CHANNEL_MAX = 255.0
private const val RED_SHIFT = 16
private const val GREEN_SHIFT = 8
private const val CHANNEL_MASK = 0xFF
private const val SRGB_LINEAR_LIMIT = 0.04045
private const val SRGB_LINEAR_SLOPE = 12.92
private const val SRGB_OFFSET = 0.055
private const val SRGB_SCALE = 1.055
private const val SRGB_GAMMA = 2.4
private const val LUMA_RED = 0.2126
private const val LUMA_GREEN = 0.7152
private const val LUMA_BLUE = 0.0722
private const val FLARE = 0.05
private const val DEGREES_PER_SECTOR = 60.0
private const val FULL_TURN = 360.0
private const val HUE_SECTORS = 6.0
private const val GREEN_SECTOR = 2.0
private const val BLUE_SECTOR = 4.0
private const val BLUE_HUE_FROM = 190.0
private const val BLUE_HUE_TO = 250.0
private const val MIN_SATURATION = 0.2

/** WCAG 2 contrast ratio of two 0xRRGGBB colours, 1 to 21. */
fun contrastRatio(a: Int, b: Int): Double {
    val la = luminance(a)
    val lb = luminance(b)
    return (max(la, lb) + FLARE) / (min(la, lb) + FLARE)
}

/** True for a saturated colour whose hue lies in 190°–250°, what NFR-5 means by blue. */
fun isBlue(rgb: Int): Boolean {
    val (r, g, b) = channels(rgb)
    val hi = maxOf(r, g, b)
    val lo = minOf(r, g, b)
    if (hi == 0.0 || (hi - lo) / hi < MIN_SATURATION) return false
    val d = hi - lo
    val sector = when (hi) {
        r -> ((g - b) / d).mod(HUE_SECTORS)
        g -> (b - r) / d + GREEN_SECTOR
        else -> (r - g) / d + BLUE_SECTOR
    }
    val hue = (sector * DEGREES_PER_SECTOR).mod(FULL_TURN)
    return hue in BLUE_HUE_FROM..BLUE_HUE_TO
}

private fun luminance(rgb: Int): Double {
    val (r, g, b) = channels(rgb).map { c ->
        if (c <= SRGB_LINEAR_LIMIT) c / SRGB_LINEAR_SLOPE else ((c + SRGB_OFFSET) / SRGB_SCALE).pow(SRGB_GAMMA)
    }
    return LUMA_RED * r + LUMA_GREEN * g + LUMA_BLUE * b
}

private fun channels(rgb: Int): List<Double> = listOf(
    (rgb shr RED_SHIFT and CHANNEL_MASK) / CHANNEL_MAX,
    (rgb shr GREEN_SHIFT and CHANNEL_MASK) / CHANNEL_MAX,
    (rgb and CHANNEL_MASK) / CHANNEL_MAX,
)
