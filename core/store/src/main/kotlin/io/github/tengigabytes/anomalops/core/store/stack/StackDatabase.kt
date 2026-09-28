// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.core.store.stack

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction

/**
 * FR-68: one burst, grouped in the app only (v1.0 decision, requirements section 9). The cover is the first
 * burst frame; the still taken on press is not part of the stack.
 */
@Entity(tableName = "burst_stack")
data class BurstStackEntity(
    @PrimaryKey val stem: String,
    val takenAtMs: Long,
    val frameCount: Int,
    val coverName: String,
    val lensId: String,
    val preset: String,
)

@Entity(
    tableName = "burst_frame",
    primaryKeys = ["stem", "position"],
    foreignKeys = [
        ForeignKey(
            entity = BurstStackEntity::class,
            parentColumns = ["stem"],
            childColumns = ["stem"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("stem")],
)
data class BurstFrameEntity(
    val stem: String,
    /** 0-based frame index; the file is `<stem>_B<position + 1>.jpg`. */
    val position: Int,
    val displayName: String,
    val uri: String,
    val takenAtMs: Long,
)

@Dao
interface StackDao {
    @Insert
    suspend fun insertStack(stack: BurstStackEntity)

    @Insert
    suspend fun insertFrames(frames: List<BurstFrameEntity>)

    @Transaction
    suspend fun record(stack: BurstStackEntity, frames: List<BurstFrameEntity>) {
        insertStack(stack)
        insertFrames(frames)
    }

    @Query("SELECT * FROM burst_stack ORDER BY takenAtMs DESC")
    suspend fun stacks(): List<BurstStackEntity>

    @Query("SELECT * FROM burst_frame WHERE stem = :stem ORDER BY position")
    suspend fun frames(stem: String): List<BurstFrameEntity>
}

@Database(entities = [BurstStackEntity::class, BurstFrameEntity::class], version = 1, exportSchema = true)
abstract class StackDatabase : RoomDatabase() {
    abstract fun stacks(): StackDao
}
