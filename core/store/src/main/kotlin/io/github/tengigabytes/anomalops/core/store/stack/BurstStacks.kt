// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.core.store.stack

import android.content.Context
import androidx.room.Room
import io.github.tengigabytes.anomalops.core.store.media.SavedStill

/** FR-68: records each burst as one stack of its saved frames, cover first (ADR-0007: Room). */
class BurstStacks(context: Context) {
    private val database = Room.databaseBuilder(context, StackDatabase::class.java, DATABASE_NAME).build()
    private val dao = database.stacks()

    /** Records a burst of [frames] (in frame order) under [stem]; a burst without frames leaves no stack. */
    suspend fun record(stem: String, frames: List<SavedStill>, lensId: String, preset: String) {
        val first = frames.firstOrNull() ?: return
        val stack = BurstStackEntity(stem, first.takenAtMs, frames.size, first.displayName, lensId, preset)
        val rows = frames.mapIndexed { index, saved ->
            BurstFrameEntity(stem, index, saved.displayName, saved.uri.toString(), saved.takenAtMs)
        }
        dao.record(stack, rows)
    }

    suspend fun stacks(): List<BurstStackEntity> = dao.stacks()

    suspend fun frames(stem: String): List<BurstFrameEntity> = dao.frames(stem)

    private companion object {
        const val DATABASE_NAME = "burst-stacks.db"
    }
}
