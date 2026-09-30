// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.lock

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Process
import android.os.SystemClock
import android.util.Log
import java.lang.ref.WeakReference
import kotlin.system.exitProcess

/**
 * NFR-1 / ADR-0006 crash restart, ported from `:tools:probe` where it was verified on Pixel 10 Pro (G1 platform
 * test, section 2): the crashing process cannot relaunch itself, and once the pinned task is empty the pin ends.
 * So, while in dive lock, the handler starts [RestartTrampolineActivity] (process `:restarter`, same task) from the
 * still-visible activity; the trampoline keeps the task alive and relaunches the main activity.
 *
 * Outside dive lock a crash is an ordinary crash: the previous handler runs. So does a crash soon after a restart
 * ([CrashLoop]), which lets the system end the pin.
 */
object CrashRestarter {
    const val EXTRA_CRASHED_AT = "crashedAtElapsedMs"
    private const val TAG = "AnomalopsLock"
    private const val EXIT_CODE = 10
    private var current = WeakReference<Activity>(null)
    private var installed = false

    fun install(activity: Activity, store: LockStore, restarts: RestartLog) {
        current = WeakReference(activity)
        if (installed) return
        installed = true
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        val appContext = activity.applicationContext
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            val sessionId = store.load()
            val now = SystemClock.elapsedRealtime()
            val last = runCatching { restarts.lastRestart() }.getOrNull()
            if (sessionId == null) {
                previous?.uncaughtException(thread, error)
            } else if (!CrashLoop.shouldRestart(sessionId, now, last)) {
                val name = error.javaClass.simpleName
                Log.e(TAG, "uncaught $name ${now - (last?.atMs ?: now)} ms after a restart; not restarting", error)
                previous?.uncaughtException(thread, error)
            } else {
                Log.e(TAG, "uncaught ${error.javaClass.simpleName} in dive lock; starting trampoline", error)
                runCatching { restarts.recordRestart(Restart(sessionId, now)) }
                    .onFailure { Log.e(TAG, "cannot record the restart", it) }
                startTrampoline(current.get(), appContext, now)
            }
            Process.killProcess(Process.myPid())
            exitProcess(EXIT_CODE)
        }
    }

    private fun startTrampoline(host: Activity?, appContext: Context, crashedAt: Long) {
        val trampoline = Intent(host ?: appContext, RestartTrampolineActivity::class.java)
            .putExtra(EXTRA_CRASHED_AT, crashedAt)
        if (host == null) trampoline.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { (host ?: appContext).startActivity(trampoline) }
            .onFailure { Log.e(TAG, "trampoline start failed", it) }
    }
}
