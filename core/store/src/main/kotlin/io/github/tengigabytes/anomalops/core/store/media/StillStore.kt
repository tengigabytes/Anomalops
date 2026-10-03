// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.core.store.media

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import io.github.tengigabytes.anomalops.core.camera.session.BurstFrame
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

    /**
     * Wall-clock time of a sensor timestamp, so names and DATE_TAKEN follow the exposure, not the save. Camera2
     * REALTIME timestamps share the base of `SystemClock.elapsedRealtimeNanos` (Pixel 10 Pro, docs/test/g0-blazer.md).
     */
    fun takenAt(sensorTimestampNs: Long): ZonedDateTime {
        val now = clock()
        val ageNs = SystemClock.elapsedRealtimeNanos() - sensorTimestampNs
        // UNVERIFIED(G0): other models may report SENSOR_INFO_TIMESTAMP_SOURCE_UNKNOWN; implausible ages fall back to now.
        return if (ageNs in 0..MAX_AGE_NS) now.minusNanos(ageNs) else now
    }

    /** FR-68: a burst is named after the exposure of its first frame. */
    fun burstStem(first: BurstFrame): String = StillNames.stem(takenAt(first.sensorTimestampNs))

    /** FR-68: one plain-JPEG burst frame under the burst's [stem], dated by its own exposure. */
    suspend fun saveBurstFrame(stem: String, frame: BurstFrame): SavedStill = withContext(Dispatchers.IO) {
        val started = SystemClock.elapsedRealtime()
        val takenAtMs = takenAt(frame.sensorTimestampNs).toInstant().toEpochMilli()
        val name = StillNames.burstName(stem, frame.index)
        val uri = resolver.writePending(name, StillNames.MIME_TYPE, takenAtMs) { it.write(frame.bytes) }
        SavedStill(uri, name, stem, takenAtMs, frame.bytes.size.toLong(), SystemClock.elapsedRealtime() - started)
    }

    /**
     * The still of [capture], named and dated by its exposure. [bytes] is the encoded picture to store: the
     * camera's own by default, or one the app rendered from the same moment (FR-17), also a JPEG.
     */
    suspend fun save(capture: StillCapture, bytes: ByteArray = capture.bytes): SavedStill =
        withContext(Dispatchers.IO) {
            val started = SystemClock.elapsedRealtime()
            val takenAt = takenAt(capture.sensorTimestampNs)
            val stem = StillNames.stem(takenAt)
            val name = StillNames.stillName(stem)
            val takenAtMs = takenAt.toInstant().toEpochMilli()
            val uri = resolver.writePending(name, StillNames.MIME_TYPE, takenAtMs) { it.write(bytes) }
            SavedStill(uri, name, stem, takenAtMs, bytes.size.toLong(), SystemClock.elapsedRealtime() - started)
        }

    /**
     * FR-17: [bytes], a picture rendered after [saved] was stored, takes its place under the same name and date.
     * It is written as a file of its own first ([StillNames.mergedName]), then the old one is deleted and the new
     * one renamed, so a process killed on the way leaves one whole picture or both, never a broken one. When the
     * rename is refused the picture keeps its own name.
     */
    suspend fun replace(saved: SavedStill, bytes: ByteArray): SavedStill = withContext(Dispatchers.IO) {
        val started = SystemClock.elapsedRealtime()
        val name = StillNames.mergedName(saved.stem)
        val uri = resolver.writePending(name, StillNames.MIME_TYPE, saved.takenAtMs) { it.write(bytes) }
        resolver.delete(saved.uri, null, null)
        val renamed = ContentValues().apply { put(MediaStore.Images.Media.DISPLAY_NAME, saved.displayName) }
        // UNVERIFIED(G0): renaming an own MediaStore item works on the Pixel 10 Pro (Android 17); other models unknown.
        val done = resolver.update(uri, renamed, null, null) > 0
        saved.copy(
            uri = uri,
            displayName = if (done) saved.displayName else name,
            sizeBytes = bytes.size.toLong(),
            writeMs = SystemClock.elapsedRealtime() - started,
        )
    }

    private companion object {
        const val MAX_AGE_NS = 5_000_000_000L
    }
}
