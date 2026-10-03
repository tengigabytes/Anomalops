// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.capture

import android.content.Context
import io.github.tengigabytes.anomalops.core.profile.ScenePreset

/**
 * The modes on the dive screen (maintainer, 2026-10-03: three modes in place of the five preset keys). Each
 * stands for one of FR-11's presets, which still set the lens, the longest exposure and the focus policy.
 */
enum class ShootingMode {
    AUTO,
    MACRO,
    WIDE,
    ;

    /**
     * The preset behind this mode. [AUTO] is the snapshot preset; with the merge switch on it is the low-light
     * preset on the same lens, whose longer exposure is what FR-17's merge is for. Macro and wide keep their own.
     */
    fun preset(merge: Boolean): ScenePreset = when (this) {
        AUTO -> if (merge) ScenePreset.LOW_LIGHT else ScenePreset.SNAPSHOT
        MACRO -> ScenePreset.MACRO
        WIDE -> ScenePreset.WIDE
    }
}

/**
 * The merge switch on the dive screen: whether a shot is a burst merged into one picture (FR-17) or the camera's
 * single still. Kept across launches; off until switched on.
 */
class MergeSwitch(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    var on: Boolean
        get() = prefs.getBoolean(KEY, false)
        set(value) = prefs.edit().putBoolean(KEY, value).apply()

    private companion object {
        const val FILE = "settings"
        const val KEY = "merge_shots"
    }
}
