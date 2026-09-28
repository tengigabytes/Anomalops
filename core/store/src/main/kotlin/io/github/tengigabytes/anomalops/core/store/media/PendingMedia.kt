// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.core.store.media

import android.content.ContentResolver
import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import java.io.OutputStream

/**
 * Inserts a pending MediaStore image under [StillNames.RELATIVE_PATH], lets [write] fill it, then publishes it.
 * The row is deleted if anything fails, so a half-written file never appears in the gallery. Blocking I/O.
 */
internal fun ContentResolver.writePending(
    name: String,
    mimeType: String,
    takenAtMs: Long,
    write: (OutputStream) -> Unit,
): Uri {
    val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    val pending = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, name)
        put(MediaStore.Images.Media.MIME_TYPE, mimeType)
        put(MediaStore.Images.Media.RELATIVE_PATH, StillNames.RELATIVE_PATH)
        put(MediaStore.Images.Media.DATE_TAKEN, takenAtMs)
        put(MediaStore.Images.Media.IS_PENDING, 1)
    }
    val uri = insert(collection, pending) ?: throw StoreFailure("MediaStore refused $name")
    var published = false
    try {
        val stream = openOutputStream(uri) ?: throw StoreFailure("no output stream for $uri")
        stream.use(write)
        update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        published = true
    } finally {
        if (!published) delete(uri, null, null)
    }
    return uri
}
