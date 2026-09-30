// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.layout

import io.github.tengigabytes.anomalops.core.profile.ScenePreset

/** FR-55: nothing clickable within this distance of any screen edge. */
const val SAFE_MARGIN_MM = 12f

/** Every key; 07-housing asks for at least [MIN_KEY_DP]. */
const val KEY_MM = 11f

/** 07-housing: the smallest control on the dive-lock screen. */
const val MIN_KEY_DP = 64f

/** FR-52: the shutter strip is at least this wide. */
const val MIN_SHUTTER_DP = 88f

private const val PREVIEW_HEIGHT_PER_WIDTH = 4f / 3f
private const val PRESET_TOP_GAP_MM = 2f
private const val PRESET_BOTTOM_GAP_MM = 1.5f
private const val PREVIEW_TO_ZOOM_MM = 2f
private const val ROW_GAP_MM = 3f
private const val EPSILON_MM = 0.001f

// Left-column rows, level with the presets; row 2 stays free.
private const val DEPTH_ROW = 3
private const val LIGHT_ROW = 4

/** Which release shows a control; v1.1 slots stay empty in v1.0 so nothing moves later. */
enum class Release { V1_0, V1_1 }

enum class Control(val since: Release) {
    LOCK(Release.V1_0),
    SETTINGS(Release.V1_0),
    DEPTH(Release.V1_0),
    LIGHT(Release.V1_0),
    PRESET(Release.V1_0),
    ZOOM(Release.V1_1),
    THUMBNAIL(Release.V1_0),
    HALF_PRESS(Release.V1_1),
    VIDEO(Release.V1_1),
    SHUTTER(Release.V1_0),
}

/** A rectangle in millimetres from the screen's top-left corner, portrait. */
data class MmRect(val x: Float, val y: Float, val width: Float, val height: Float) {
    val right: Float get() = x + width
    val bottom: Float get() = y + height

    fun overlaps(other: MmRect): Boolean = x < other.right && other.x < right && y < other.bottom && other.y < bottom
}

data class Slot(val control: Control, val rect: MmRect, val preset: ScenePreset? = null)

/**
 * docs/product/dive-lock-layout.md, section 3, derived from the screen size so FR-55 holds on any display:
 * a 4:3 preview across the short edge, presets down the right long edge, lock / settings / depth / light down
 * the left, then the zoom bar, the thumbnail row and the shutter strip below the preview. Screen size comes
 * from pixels over xdpi / ydpi at run time (FR-55).
 */
class DiveLockLayout(val screenWidthMm: Float, val screenHeightMm: Float) {
    val preview = MmRect(0f, 0f, screenWidthMm, screenWidthMm * PREVIEW_HEIGHT_PER_WIDTH)

    /** Read-only status (07-housing): battery, temperature, conditions, FR-45 state. */
    val statusBand = MmRect(0f, screenHeightMm - SAFE_MARGIN_MM, screenWidthMm, SAFE_MARGIN_MM)

    val slots: List<Slot> = buildSlots()

    fun slot(control: Control): Slot = slots.first { it.control == control }

    /** Every FR-55 / FR-52 / 07-housing rule this layout breaks, given the display's millimetres per dp. */
    fun violations(mmPerDp: Float): List<String> = buildList {
        val minKey = MIN_KEY_DP * mmPerDp - EPSILON_MM
        slots.forEach { slot ->
            val r = slot.rect
            val inside = r.x >= SAFE_MARGIN_MM - EPSILON_MM && r.y >= SAFE_MARGIN_MM - EPSILON_MM &&
                r.right <= screenWidthMm - SAFE_MARGIN_MM + EPSILON_MM &&
                r.bottom <= screenHeightMm - SAFE_MARGIN_MM + EPSILON_MM
            if (!inside) add("${slot.name()} is within $SAFE_MARGIN_MM mm of an edge")
            if (r.width < minKey || r.height < minKey) add("${slot.name()} is smaller than $MIN_KEY_DP dp")
        }
        val shutter = slot(Control.SHUTTER).rect
        if (shutter.width < MIN_SHUTTER_DP * mmPerDp) add("shutter is narrower than $MIN_SHUTTER_DP dp")
        slots.forEachIndexed { i, a ->
            slots.drop(i + 1).filter { a.rect.overlaps(it.rect) }.forEach { add("${a.name()} overlaps ${it.name()}") }
        }
    }

    private fun Slot.name(): String = preset?.let { "$control $it" } ?: control.toString()

    private fun buildSlots(): List<Slot> {
        val left = SAFE_MARGIN_MM
        val right = screenWidthMm - SAFE_MARGIN_MM - KEY_MM
        val middle = (screenWidthMm - KEY_MM) / 2
        val inner = screenWidthMm - 2 * SAFE_MARGIN_MM
        val presets = ScenePreset.entries
        val top = SAFE_MARGIN_MM + PRESET_TOP_GAP_MM
        val step = (preview.bottom - PRESET_BOTTOM_GAP_MM - top - KEY_MM) / (presets.size - 1)
        fun row(i: Int) = top + i * step
        fun key(control: Control, x: Float, y: Float) = Slot(control, MmRect(x, y, KEY_MM, KEY_MM))
        val zoomY = preview.bottom + PREVIEW_TO_ZOOM_MM
        val rowY = zoomY + KEY_MM + ROW_GAP_MM
        val shutterY = rowY + KEY_MM + ROW_GAP_MM
        return presets.mapIndexed { i, p -> Slot(Control.PRESET, MmRect(right, row(i), KEY_MM, KEY_MM), p) } +
            listOf(
                key(Control.LOCK, left, row(0)),
                key(Control.SETTINGS, left, row(1)),
                key(Control.DEPTH, left, row(DEPTH_ROW)),
                key(Control.LIGHT, left, row(LIGHT_ROW)),
                Slot(Control.ZOOM, MmRect(left, zoomY, inner, KEY_MM)),
                key(Control.THUMBNAIL, left, rowY),
                key(Control.HALF_PRESS, middle, rowY),
                key(Control.VIDEO, right, rowY),
                Slot(Control.SHUTTER, MmRect(left, shutterY, inner, screenHeightMm - SAFE_MARGIN_MM - shutterY)),
            )
    }
}
