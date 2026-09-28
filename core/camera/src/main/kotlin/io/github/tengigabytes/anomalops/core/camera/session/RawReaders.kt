// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.core.camera.session

import android.graphics.ImageFormat
import android.media.ImageReader
import io.github.tengigabytes.anomalops.core.profile.PhysicalCamera

/**
 * One `RAW_SENSOR` reader per lens, kept open across sessions. Closing an ImageReader invalidates every Image it
 * handed out, so a lens switch, a burst session or stop() must not close a reader while buffered RAW frames
 * (ADR-0005) still use it; such a reader is closed when its last frame is released. Camera thread only.
 */
internal class RawReaders {
    private class Entry(val reader: ImageReader) {
        var outstanding = 0
    }

    private val entries = mutableMapOf<String, Entry>()
    private var current: String? = null

    /** The RAW reader of [camera], now the current lens; null when the lens has no RAW output. */
    fun readerFor(camera: PhysicalCamera): ImageReader? {
        val size = camera.outputs[RAW_KEY]?.max ?: return null
        current = camera.id
        retireIdle()
        return entries.getOrPut(camera.id) {
            val (width, height) = size.split('x').map(String::toInt)
            Entry(ImageReader.newInstance(width, height, ImageFormat.RAW_SENSOR, MAX_RAW_IMAGES))
        }.reader
    }

    /**
     * Whether [lensId]'s reader can take one more frame. With [MAX_RAW_IMAGES] frames out, acquiring another throws
     * on the camera thread and kills the app, so such a still goes without RAW (docs/test/m4-af-timeline.md).
     */
    fun hasRoom(lensId: String): Boolean = (entries[lensId]?.outstanding ?: 0) < MAX_RAW_IMAGES

    /** A frame of [lensId] was handed out. */
    fun acquired(lensId: String) {
        entries[lensId]?.let { it.outstanding++ }
    }

    /** A frame of [lensId] was closed. */
    fun released(lensId: String) {
        entries[lensId]?.let { it.outstanding-- }
        retireIdle()
    }

    /** No lens is current any more (stop); readers close as soon as their frames are released. */
    fun retireAll() {
        current = null
        retireIdle()
    }

    private fun retireIdle() {
        entries.filter { (id, entry) -> id != current && entry.outstanding <= 0 }.forEach { (id, entry) ->
            entry.reader.close()
            entries.remove(id)
        }
    }

    private companion object {
        const val RAW_KEY = "RAW_SENSOR"

        /** ADR-0005: the 5 buffered frames plus 2 in flight. */
        const val MAX_RAW_IMAGES = 7
    }
}
