// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.probe

import android.app.Activity
import android.content.Intent
import android.os.Process
import android.os.SystemClock
import android.util.Log
import java.lang.ref.WeakReference
import kotlin.system.exitProcess

/**
 * Candidate NFR-1 restart strategy under test (ADR-0006).
 *
 * Findings so far on Pixel 10 Pro / Android 17 (docs/test/g1-dive-lock-platform.md): relaunching the crashing
 * singleTask activity from its own dying process is lost, and once the pinned task becomes empty, screen pinning
 * ends. So the handler starts [RestartTrampolineActivity] (separate process, same task) from the still-visible
 * activity before the process dies; the trampoline keeps the task alive and relaunches the activity.
 */
internal object CrashRestarter {
    const val EXTRA_CRASHED_AT = "crashedAtElapsedMs"
    private const val TAG = "AnomalopsLock"
    private const val EXIT_CODE = 10
    private var current = WeakReference<Activity>(null)

    fun install(activity: Activity) {
        current = WeakReference(activity)
        Thread.setDefaultUncaughtExceptionHandler { _, error ->
            Log.e(TAG, "uncaught ${error.javaClass.simpleName}: ${error.message}; starting trampoline")
            val host = current.get()
            val trampoline = Intent(host ?: activity.applicationContext, RestartTrampolineActivity::class.java)
                .putExtra(EXTRA_CRASHED_AT, SystemClock.elapsedRealtime())
            if (host == null) trampoline.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { (host ?: activity.applicationContext).startActivity(trampoline) }
                .onFailure { Log.e(TAG, "trampoline start failed", it) }
            Process.killProcess(Process.myPid())
            exitProcess(EXIT_CODE)
        }
    }
}
