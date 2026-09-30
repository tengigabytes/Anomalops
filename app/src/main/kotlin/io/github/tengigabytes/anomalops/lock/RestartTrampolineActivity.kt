// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.lock

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import io.github.tengigabytes.anomalops.MainActivity

/**
 * Transparent activity in process `:restarter`, in the same task as [MainActivity]. It keeps the pinned task
 * non-empty while the crashed process dies, then relaunches the main activity, which finds the stored session and
 * resumes dive lock (NFR-1). On Pixel 10 Pro the probe version was back in 449 ms (G1 platform test).
 */
class RestartTrampolineActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val crashedAt = intent.getLongExtra(CrashRestarter.EXTRA_CRASHED_AT, -1)
        Log.i(TAG, "trampoline started; crash at $crashedAt")
        Handler(Looper.getMainLooper()).postDelayed({
            startActivity(
                Intent(this, MainActivity::class.java)
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
