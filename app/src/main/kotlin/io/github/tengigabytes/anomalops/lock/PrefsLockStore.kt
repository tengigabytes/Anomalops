// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.lock

import android.content.Context

/**
 * [LockStore] and [RestartLog] in private shared preferences, written with `commit()` so they are on disk before
 * the process dies.
 */
class PrefsLockStore(context: Context) :
    LockStore,
    RestartLog {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    override fun load(): String? = prefs.getString(KEY_SESSION, null)

    override fun save(sessionId: String?) {
        check(prefs.edit().putString(KEY_SESSION, sessionId).commit()) { "cannot store the dive-lock session" }
    }

    override fun lastRestart(): Restart? {
        val sessionId = prefs.getString(KEY_RESTART_SESSION, null) ?: return null
        return Restart(sessionId, prefs.getLong(KEY_RESTART_AT, 0L))
    }

    override fun recordRestart(restart: Restart) {
        val edit = prefs.edit().putString(KEY_RESTART_SESSION, restart.sessionId).putLong(KEY_RESTART_AT, restart.atMs)
        check(edit.commit()) { "cannot store the crash restart" }
    }

    private companion object {
        const val FILE = "dive_lock"
        const val KEY_SESSION = "session_id"
        const val KEY_RESTART_SESSION = "restart_session_id"
        const val KEY_RESTART_AT = "restart_at_elapsed_ms"
    }
}
