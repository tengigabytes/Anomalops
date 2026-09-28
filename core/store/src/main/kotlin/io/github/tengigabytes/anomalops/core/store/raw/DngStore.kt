// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.core.store.raw

import android.content.Context
import android.hardware.camera2.DngCreator
import android.media.ExifInterface
import android.os.SystemClock
import io.github.tengigabytes.anomalops.core.camera.session.RawFrame
import io.github.tengigabytes.anomalops.core.store.media.SavedStill
import io.github.tengigabytes.anomalops.core.store.media.StillNames
import io.github.tengigabytes.anomalops.core.store.media.writePending
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FilterOutputStream
import java.io.OutputStream

/**
 * Writes a RAW frame as a DNG next to its still, with the same stem (ADR-0005, FR-16, FR-64). `DngCreator`
 * writes it uncompressed; compression is v1.1 (FR-64). The frame is closed afterwards, written or not.
 */
class DngStore(context: Context) {
    private val resolver = context.contentResolver

    suspend fun save(still: SavedStill, frame: RawFrame): SavedStill = withContext(Dispatchers.IO) {
        frame.use {
            val started = SystemClock.elapsedRealtime()
            val name = StillNames.dngName(still.stem)
            var written = 0L
            val uri = resolver.writePending(name, StillNames.DNG_MIME_TYPE, still.takenAtMs) { out ->
                val counting = CountingOutputStream(out)
                DngCreator(frame.characteristics, frame.result).use { dng ->
                    dng.setOrientation(exifOrientation(frame.sensorOrientation))
                    dng.writeImage(counting, frame.image)
                }
                written = counting.count
            }
            SavedStill(uri, name, still.stem, still.takenAtMs, written, SystemClock.elapsedRealtime() - started)
        }
    }

    private fun exifOrientation(sensorDegrees: Int): Int = when (sensorDegrees) {
        QUARTER -> ExifInterface.ORIENTATION_ROTATE_90
        HALF -> ExifInterface.ORIENTATION_ROTATE_180
        THREE_QUARTERS -> ExifInterface.ORIENTATION_ROTATE_270
        else -> ExifInterface.ORIENTATION_NORMAL
    }

    private companion object {
        const val QUARTER = 90
        const val HALF = 180
        const val THREE_QUARTERS = 270
    }
}

/** Counts the bytes written through it, to record the DNG size (FR-64). */
private class CountingOutputStream(out: OutputStream) : FilterOutputStream(out) {
    var count = 0L
        private set

    override fun write(b: Int) {
        out.write(b)
        count++
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        out.write(b, off, len)
        count += len
    }
}
