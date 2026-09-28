// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.core.store.raw

/**
 * FR-62 / ADR-0005: the most recent [capacity] RAW frames, each kept for [ttlMs]. A frame leaves by [take] (the
 * caller then owns it), by being pushed out by newer ones, or by expiring; the last two hand it to [release].
 * Keys are the still's file stem. Thread-safe; the frame type is generic so the rules test on the JVM.
 */
class RawBuffer<T>(
    private val capacity: Int,
    private val ttlMs: Long,
    private val nowMs: () -> Long,
    private val release: (T) -> Unit,
) {
    private class Entry<T>(val key: String, val value: T, val addedAtMs: Long)

    private val entries = ArrayDeque<Entry<T>>()

    /** Keys still held, oldest first. */
    val keys: List<String> @Synchronized get() = sweep().let { entries.map { it.key } }

    @Synchronized
    fun offer(key: String, value: T) {
        sweep()
        entries.addLast(Entry(key, value, nowMs()))
        while (entries.size > capacity) release(entries.removeFirst().value)
    }

    /** Removes and returns the frame of [key]; null when it expired, was pushed out, or never came. */
    @Synchronized
    fun take(key: String): T? {
        sweep()
        val index = entries.indexOfFirst { it.key == key }
        return if (index < 0) null else entries.removeAt(index).value
    }

    /** Releases the frames older than [ttlMs]; entries are in capture order, so only the head can expire. */
    @Synchronized
    fun sweep() {
        val now = nowMs()
        while (entries.isNotEmpty() && now - entries.first().addedAtMs >= ttlMs) release(entries.removeFirst().value)
    }

    @Synchronized
    fun clear() {
        while (entries.isNotEmpty()) release(entries.removeFirst().value)
    }
}
