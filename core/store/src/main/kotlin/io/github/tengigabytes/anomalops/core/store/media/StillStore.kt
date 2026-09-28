// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.core.store.media

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import io.github.tengigabytes.anomalops.core.camera.session.StillCapture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.ZonedDateTime

/** A still that is visible in MediaStore. [writeMs]: from [StillStore.save] to `IS_PENDING = 0` (NFR-7). */
data class SavedStill(val uri: Uri, val displayName: String, val sizeBytes: Int, val writeMs: Long)

class StoreFailure(message: String) : IOException(message)

/**
 * Writes finished stills to MediaStore under [StillNames.RELATIVE_PATH] (ADR-0004, FR-61a). Apps may add their
 * own images there without a storage permission. The row stays pending until the bytes are written, and is
 * removed if writing fails, so a half-written photo never appears in the gallery.
 */
class StillStore(context: Context, private val clock: () -> ZonedDateTime = ZonedDateTime::now) {
    private val resolver = context.contentResolver
    private val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    suspend fun save(capture: StillCapture): SavedStill = withContext(Dispatchers.IO) {
        val started = SystemClock.elapsedRealtime()
        val takenAt = clock()
        val name = StillNames.stillName(StillNames.stem(takenAt))
        val pending = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, StillNames.MIME_TYPE)
            put(MediaStore.Images.Media.RELATIVE_PATH, StillNames.RELATIVE_PATH)
            put(MediaStore.Images.Media.DATE_TAKEN, takenAt.toInstant().toEpochMilli())
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, pending) ?: throw StoreFailure("MediaStore refused $name")
        var published = false
        try {
            val stream = resolver.openOutputStream(uri) ?: throw StoreFailure("no output stream for $uri")
            stream.use { it.write(capture.bytes) }
            val done = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)
            published = true
        } finally {
            if (!published) resolver.delete(uri, null, null)
        }
        SavedStill(uri, name, capture.bytes.size, SystemClock.elapsedRealtime() - started)
    }
}
