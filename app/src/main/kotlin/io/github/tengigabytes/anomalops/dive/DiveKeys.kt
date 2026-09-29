// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.dive

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.tengigabytes.anomalops.R
import io.github.tengigabytes.anomalops.capture.LatestThumbnail
import io.github.tengigabytes.anomalops.capture.ShutterButton
import io.github.tengigabytes.anomalops.conditions.cycle
import io.github.tengigabytes.anomalops.core.profile.CalibrationKey
import io.github.tengigabytes.anomalops.core.profile.DepthBand
import io.github.tengigabytes.anomalops.core.profile.LensFilter
import io.github.tengigabytes.anomalops.core.profile.ScenePreset
import io.github.tengigabytes.anomalops.layout.Control
import io.github.tengigabytes.anomalops.layout.DiveLockLayout

/** Left long edge: lock or unlock, settings (normal mode only), depth band, dive light. */
@Composable
fun LeftKeys(
    layout: DiveLockLayout,
    scale: MmScale,
    locked: Boolean,
    key: CalibrationKey,
    actions: DiveActions,
    deps: DiveDeps,
) {
    val lockSlot = Modifier.place(layout.slot(Control.LOCK).rect, scale)
    if (locked) {
        UnlockKey(stringResource(R.string.unlock), stringResource(R.string.unlock_hint), actions.onUnlock, lockSlot)
    } else {
        Key(stringResource(R.string.lock_enter), lockSlot, onClick = actions.onLock)
        Key(stringResource(R.string.settings), Modifier.place(layout.slot(Control.SETTINGS).rect, scale)) {
            actions.onSettings()
        }
    }
    Key(
        label = stringResource(depthLabel(key.depthBand)),
        modifier = Modifier.place(layout.slot(Control.DEPTH).rect, scale),
        caption = stringResource(R.string.depth_label),
    ) { deps.depth.cycle() }
    Key(
        label = stringResource(if (key.diveLight) R.string.light_on else R.string.light_off),
        modifier = Modifier.place(layout.slot(Control.LIGHT).rect, scale),
        caption = stringResource(R.string.light_label),
    ) { deps.conditions.toggleLight() }
}

/** FR-12: the five presets down the right long edge, the current one selected. */
@Composable
fun PresetKeys(layout: DiveLockLayout, scale: MmScale, selected: ScenePreset, onSelect: (ScenePreset) -> Unit) {
    layout.slots.filter { it.control == Control.PRESET }.forEach { slot ->
        val preset = checkNotNull(slot.preset)
        Key(
            label = stringResource(presetLabel(preset)),
            modifier = Modifier.place(slot.rect, scale),
            selected = preset == selected,
        ) { onSelect(preset) }
    }
}

/** Below the preview: the thumbnail (FR-62) and the shutter strip (FR-52). v1.1 slots stay empty. */
@Composable
fun LowerKeys(layout: DiveLockLayout, scale: MmScale, shots: ShotControls) {
    LatestThumbnail(
        jpeg = shots.shot?.capture?.bytes,
        onLongPress = shots::keepRaw,
        modifier = Modifier.place(layout.slot(Control.THUMBNAIL).rect, scale),
    )
    ShutterButton(
        onPress = shots::shoot,
        onHold = shots::burst,
        modifier = Modifier.place(layout.slot(Control.SHUTTER).rect, scale),
    )
}

/** The status band's second line: depth band, filter, dive light, and whether white balance is approximate. */
@Composable
fun conditionsLine(key: CalibrationKey, approximate: Boolean): String = listOfNotNull(
    stringResource(depthLabel(key.depthBand)),
    stringResource(filterLabel(key.filter)),
    stringResource(if (key.diveLight) R.string.light_short_on else R.string.light_short_off),
    stringResource(R.string.wb_approximate).takeIf { approximate },
).joinToString(" · ")

private fun depthLabel(band: DepthBand): Int = when (band) {
    DepthBand.SHALLOW -> R.string.depth_shallow
    DepthBand.MID -> R.string.depth_mid
    DepthBand.DEEP -> R.string.depth_deep
}

private fun filterLabel(filter: LensFilter): Int = when (filter) {
    LensFilter.NONE -> R.string.filter_short_none
    LensFilter.RED -> R.string.filter_short_red
    LensFilter.MAGENTA -> R.string.filter_short_magenta
}

fun presetLabel(preset: ScenePreset): Int = when (preset) {
    ScenePreset.SNAPSHOT -> R.string.preset_snapshot
    ScenePreset.WIDE -> R.string.preset_wide
    ScenePreset.FISH_SCHOOL -> R.string.preset_fish_school
    ScenePreset.MACRO -> R.string.preset_macro
    ScenePreset.LOW_LIGHT -> R.string.preset_low_light
}
