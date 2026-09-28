// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.core.store.media

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import io.github.tengigabytes.anomalops.core.camera.session.StillCapture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.ZonedDateTime

/**
 * A file that is visible in MediaStore. [stem] is shared by a still and its DNG (ADR-0005); [writeMs] runs from
 * the save call to `IS_PENDING = 0` (NFR-7).
 */
data class SavedStill(
    val uri: Uri,
    val displayName: String,
    val stem: String,
    val takenAtMs: Long,
    val sizeBytes: Long,
    val writeMs: Long,
)

class StoreFailure(message: String) : IOException(message)

/**
 * Writes finished stills to MediaStore under [StillNames.RELATIVE_PATH] (ADR-0004, FR-61a). Apps may add their
 * own images there without a storage permission.
 */
class StillStore(context: Context, private val clock: () -> ZonedDateTime = ZonedDateTime::now) {
    private val resolver = context.contentResolver

    suspend fun save(capture: StillCapture): SavedStill = withContext(Dispatchers.IO) {
        val started = SystemClock.elapsedRealtime()
        val takenAt = clock()
        val stem = StillNames.stem(takenAt)
        val name = StillNames.stillName(stem)
        val takenAtMs = takenAt.toInstant().toEpochMilli()
        val uri = resolver.writePending(name, StillNames.MIME_TYPE, takenAtMs) { it.write(capture.bytes) }
        SavedStill(uri, name, stem, takenAtMs, capture.bytes.size.toLong(), SystemClock.elapsedRealtime() - started)
    }
}
