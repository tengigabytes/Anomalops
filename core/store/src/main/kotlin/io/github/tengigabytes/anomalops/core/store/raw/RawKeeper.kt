// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.core.store.raw

import android.os.SystemClock
import io.github.tengigabytes.anomalops.core.camera.session.RawFrame
import io.github.tengigabytes.anomalops.core.store.media.SavedStill
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * FR-62: holds the RAW frames of the last [CAPACITY] stills for [TTL_MS]; a long press on the thumbnail keeps
 * one as a DNG. Frames that leave unkept are closed, which returns their camera buffers (ADR-0005). A sweep in
 * [scope] closes expired frames even when no photo is taken.
 */
class RawKeeper(
    private val dngStore: DngStore,
    scope: CoroutineScope,
    nowMs: () -> Long = SystemClock::elapsedRealtime,
) {
    private class Pending(val still: SavedStill, val frame: RawFrame)

    private val buffer = RawBuffer<Pending>(CAPACITY, TTL_MS, nowMs) { it.frame.close() }

    init {
        scope.launch {
            while (isActive) {
                delay(SWEEP_MS)
                buffer.sweep()
            }
        }
    }

    /** Stems whose RAW frame can still be kept, oldest first. */
    val heldStems: List<String> get() = buffer.keys

    /** Takes ownership of [frame], the RAW of [still]. */
    fun offer(still: SavedStill, frame: RawFrame) = buffer.offer(still.stem, Pending(still, frame))

    /** Writes the DNG for [stem]; null when its RAW frame is gone (expired, pushed out, or never held). */
    suspend fun keep(stem: String): SavedStill? {
        val pending = buffer.take(stem) ?: return null
        return dngStore.save(pending.still, pending.frame)
    }

    /** Closes every held frame, e.g. before the activity is destroyed. */
    fun clear() = buffer.clear()

    companion object {
        /** FR-62 defaults: 5 frames, 10 s. */
        const val CAPACITY = 5
        const val TTL_MS = 10_000L
        private const val SWEEP_MS = 1_000L
    }
}
