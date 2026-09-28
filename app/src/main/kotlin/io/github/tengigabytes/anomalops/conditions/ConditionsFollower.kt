// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.conditions

import io.github.tengigabytes.anomalops.core.profile.CalibrationKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.onEach

/**
 * Keeps the camera's preview on the current [ShootingConditions] key. Remembers the key the camera last got, so
 * a change is neither lost nor sent twice, whichever of the preview start and the collection of [changes] comes
 * first; while no preview runs, changes wait for the next [send].
 */
class ConditionsFollower(private val conditions: ShootingConditions) {
    @Volatile
    private var sent: CalibrationKey? = null

    /** The key to open or re-plan the preview with, marked as the one the camera has. */
    fun send(): CalibrationKey = conditions.current.also { sent = it }

    /** The preview stopped; the next start takes the key current then. */
    fun closed() {
        sent = null
    }

    /** Keys the running preview does not have yet, each marked as sent. */
    val changes: Flow<CalibrationKey> = conditions.keys
        .filter { key -> sent.let { it != null && it != key } }
        .onEach { sent = it }
}
