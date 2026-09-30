// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.core.store.library

/**
 * FR-67: bytes per shot for one setting (preset and format), from what was actually written: the mean over the
 * last [window] shots, plus the DNGs kept for them. Before any shot it uses [fallbackBytes] (for example the G1
 * mean of the format). Not thread-safe.
 */
class ShotSizes(private val fallbackBytes: Long, private val window: Int = WINDOW) {
    private class Entry(val bytes: Long, val isShot: Boolean)

    private val entries = ArrayDeque<Entry>()
    private var shots = 0

    init {
        require(fallbackBytes > 0) { "fallbackBytes must be positive" }
        require(window > 0) { "window must be positive" }
    }

    /** A photo was written. */
    fun onShot(bytes: Long) {
        entries.addLast(Entry(bytes, isShot = true))
        shots++
        if (shots <= window) return
        // Drop the oldest shot and the extras recorded after it, before the next shot.
        entries.removeFirst()
        shots--
        while (entries.first().isShot.not()) entries.removeFirst()
    }

    /** More bytes for the recent shots without a new shot, such as a DNG kept from the thumbnail (FR-62). */
    fun onExtra(bytes: Long) {
        if (shots > 0) entries.addLast(Entry(bytes, isShot = false))
    }

    val bytesPerShot: Long
        get() = if (shots == 0) fallbackBytes else entries.sumOf { it.bytes } / shots

    companion object {
        /** Proposed: long enough to smooth scene-to-scene size changes, short enough to follow a new scene. */
        const val WINDOW = 20
    }
}

/** FR-67: what is left, as the dive-lock screen shows it: shots, and video minutes from M7. */
data class Capacity(val shots: Long, val videoMinutes: Long?) {
    companion object {
        private const val SECONDS_PER_MINUTE = 60

        /**
         * [freeBytes] minus [reserveBytes] (the space the system keeps for itself before it refuses writes),
         * divided by the current [bytesPerShot]; video minutes only when a bitrate is given.
         */
        fun of(freeBytes: Long, reserveBytes: Long, bytesPerShot: Long, videoBytesPerSecond: Long? = null): Capacity {
            require(bytesPerShot > 0) { "bytesPerShot must be positive" }
            val usable = (freeBytes - reserveBytes).coerceAtLeast(0)
            val minutes = videoBytesPerSecond?.let {
                require(it > 0) { "videoBytesPerSecond must be positive" }
                usable / it / SECONDS_PER_MINUTE
            }
            return Capacity(usable / bytesPerShot, minutes)
        }
    }
}
