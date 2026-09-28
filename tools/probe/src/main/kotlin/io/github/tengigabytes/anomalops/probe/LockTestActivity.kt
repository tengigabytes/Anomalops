// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.probe

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.ScrollView
import android.widget.TextView

/**
 * Platform behaviour test for the dive lock (ADR-0006): screen pinning, heads-up notifications,
 * power button, crash restart. Driven from a computer so every step can be screenshotted:
 * `adb shell am broadcast -p <pkg> -a io.github.tengigabytes.anomalops.probe.LOCKTEST --es cmd <lock|unlock|notify|crash|status>`
 * Every event is logged with tag AnomalopsLock.
 */
class LockTestActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var status: TextView

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = handle(intent.getStringExtra("cmd"))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashRestarter.install(this)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.apply { screenBrightness = 1f }
        status = TextView(this).apply {
            textSize = TEXT_SP
            setPadding(PADDING, PADDING, PADDING, PADDING)
        }
        setContentView(ScrollView(this).apply { addView(status) })
        window.insetsController?.apply {
            hide(WindowInsets.Type.systemBars())
            systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        registerReceiver(receiver, IntentFilter(ACTION), RECEIVER_EXPORTED)
        val crashedAt = intent.getLongExtra(CrashRestarter.EXTRA_CRASHED_AT, -1)
        if (crashedAt > 0) log("restarted after crash in ${SystemClock.elapsedRealtime() - crashedAt} ms")
        log("onCreate " + snapshot())
    }

    override fun onDestroy() {
        unregisterReceiver(receiver)
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        log("onResume")
    }

    override fun onPause() {
        log("onPause")
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        log("windowFocus=$hasFocus")
    }

    private fun handle(cmd: String?) {
        log("cmd $cmd")
        when (cmd) {
            "lock" -> startLockTask()
            "unlock" -> stopLockTask()
            "notify" -> LockTestNotifier.postHeadsUp(this)
            "crash" -> ui.post { throw IllegalStateException("LockTest deliberate crash") }
            "status" -> Unit
            else -> log("unknown cmd")
        }
        ui.postDelayed({ log("after $cmd: " + snapshot()) }, SNAPSHOT_DELAY_MS)
    }

    private fun snapshot(): String {
        val lockTask = getSystemService(ActivityManager::class.java).lockTaskModeState
        val notifications = getSystemService(NotificationManager::class.java)
        return listOf(
            "lockTask=" + (LOCK_TASK_NAMES[lockTask] ?: lockTask.toString()),
            "keyguardLocked=" + getSystemService(KeyguardManager::class.java).isKeyguardLocked,
            "interactive=" + getSystemService(PowerManager::class.java).isInteractive,
            "focus=" + hasWindowFocus(),
            "overlayAllowed=" + Settings.canDrawOverlays(this),
            "dndAccess=" + notifications.isNotificationPolicyAccessGranted,
            "postNotifications=" + (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED),
        ).joinToString(" ")
    }

    private fun log(message: String) {
        Log.i(TAG, message)
        status.append("${SystemClock.elapsedRealtime()} $message\n")
    }

    private companion object {
        const val TAG = "AnomalopsLock"
        const val ACTION = "io.github.tengigabytes.anomalops.probe.LOCKTEST"
        const val SNAPSHOT_DELAY_MS = 1_000L
        const val TEXT_SP = 16f
        const val PADDING = 32
        val LOCK_TASK_NAMES = mapOf(
            ActivityManager.LOCK_TASK_MODE_NONE to "NONE",
            ActivityManager.LOCK_TASK_MODE_PINNED to "PINNED",
            ActivityManager.LOCK_TASK_MODE_LOCKED to "LOCKED",
        )
    }
}
