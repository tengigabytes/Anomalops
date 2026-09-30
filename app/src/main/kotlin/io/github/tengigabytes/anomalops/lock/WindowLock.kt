// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.lock

import android.app.Activity
import android.app.ActivityManager
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager

/**
 * ADR-0006 window state. Locked: shown over the keyguard and turning the screen on (FR-56), never sleeping, full
 * brightness, system bars hidden (FR-51). Normal mode undoes all of it, so the normal camera is not shown over the
 * keyguard (docs/product/dive-lock-layout.md, section 2). Orientation is fixed in the manifest.
 */
fun Activity.applyDiveLockWindow(locked: Boolean) {
    // G1 (docs/test/m3-instrumented.md, section 6): while pinned the system disables the keyguard, so the power key
    // does not lock and FR-56 holds without this; normal mode shows the keyguard as intended. Kept for a keyguard
    // that appears anyway (UNVERIFIED(G1): switching showWhenLocked at run time was not exercised).
    setShowWhenLocked(locked)
    setTurnScreenOn(locked)
    if (locked) {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    } else {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    window.attributes = window.attributes.apply {
        screenBrightness = if (locked) 1f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
    }
    window.insetsController?.let { bars ->
        if (locked) {
            bars.hide(WindowInsets.Type.systemBars())
            bars.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            bars.show(WindowInsets.Type.systemBars())
        }
    }
}

/** Screen pinning is on (ADR-0006; the app is not a device owner, so pinning is the only lock-task mode). */
fun Activity.isPinned(): Boolean =
    getSystemService(ActivityManager::class.java).lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE
