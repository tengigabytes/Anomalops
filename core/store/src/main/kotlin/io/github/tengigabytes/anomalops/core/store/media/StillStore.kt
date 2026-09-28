// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.core.store.media

import android.content.Context
import android.net.Uri
import android.os.SystemClock
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

    suspend fun save(capture: StillCapture): SavedStill = withContext(Dispatchers.IO) {
        val started = SystemClock.elapsedRealtime()
        val takenAt = takenAt(capture.sensorTimestampNs)
        val stem = StillNames.stem(takenAt)
        val name = StillNames.stillName(stem)
        val takenAtMs = takenAt.toInstant().toEpochMilli()
        val uri = resolver.writePending(name, StillNames.MIME_TYPE, takenAtMs) { it.write(capture.bytes) }
        SavedStill(uri, name, stem, takenAtMs, capture.bytes.size.toLong(), SystemClock.elapsedRealtime() - started)
    }

    private companion object {
        const val MAX_AGE_NS = 5_000_000_000L
    }
}
