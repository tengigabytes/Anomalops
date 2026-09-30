// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops

import android.content.Context
import android.util.Log
import io.github.tengigabytes.anomalops.lock.DiveSession
import io.github.tengigabytes.anomalops.lock.LockState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException

/**
 * The process's FR-45 session (ADR-0008): open while dive lock is on, closed on unlock. It belongs to the process,
 * not to an activity instance, so recreating the activity neither restarts nor duplicates the 1 Hz sampler.
 * Opens and closes run one at a time.
 */
object Sessions {
    private const val TAG = "Sessions"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val changes = Mutex()

    @Volatile
    var current: DiveSession? = null
        private set

    fun follow(context: Context, state: LockState) {
        val appContext = context.applicationContext
        scope.launch {
            changes.withLock {
                val open = current
                val wanted = (state as? LockState.Locked)?.sessionId
                if (open != null && open.id != wanted) {
                    current = null
                    open.close()
                }
                if (wanted != null && current == null) {
                    current = try {
                        DiveSession.open(appContext, wanted, scope)
                    } catch (e: IOException) {
                        Log.e(TAG, "cannot open dive session $wanted", e)
                        null
                    } catch (e: IllegalStateException) {
                        Log.e(TAG, "cannot open dive session $wanted", e)
                        null
                    }
                }
            }
        }
    }
}
