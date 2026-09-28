// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.probe

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log

/**
 * Transparent activity in a separate process (`:restarter`) and in the same task as the crashing activity.
 * It keeps the pinned task non-empty while the crashed process dies, then relaunches the lock test activity.
 * Verified on Pixel 10 Pro / Android 17: pinning survives and the activity is back in ~450 ms
 * (docs/test/g1-dive-lock-platform.md).
 */
class RestartTrampolineActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "trampoline started in pid ${Process.myPid()}")
        val crashedAt = intent.getLongExtra(CrashRestarter.EXTRA_CRASHED_AT, -1)
        Handler(Looper.getMainLooper()).postDelayed({
            startActivity(
                Intent(this, LockTestActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    .putExtra(CrashRestarter.EXTRA_CRASHED_AT, crashedAt),
            )
            finish()
        }, RELAUNCH_DELAY_MS)
    }

    private companion object {
        const val TAG = "AnomalopsLock"
        const val RELAUNCH_DELAY_MS = 300L
    }
}
