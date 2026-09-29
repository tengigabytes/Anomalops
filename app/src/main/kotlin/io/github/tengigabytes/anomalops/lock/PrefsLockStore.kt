// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.lock

import android.content.Context

/** [LockStore] in private shared preferences, written with `commit()` so it is on disk before a crash. */
class PrefsLockStore(context: Context) : LockStore {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    override fun load(): String? = prefs.getString(KEY_SESSION, null)

    override fun save(sessionId: String?) {
        check(prefs.edit().putString(KEY_SESSION, sessionId).commit()) { "cannot store the dive-lock session" }
    }

    private companion object {
        const val FILE = "dive_lock"
        const val KEY_SESSION = "session_id"
    }
}
