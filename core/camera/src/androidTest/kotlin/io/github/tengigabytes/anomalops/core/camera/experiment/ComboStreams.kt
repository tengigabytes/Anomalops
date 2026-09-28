// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.core.camera.experiment

import android.graphics.ImageFormat
import android.media.ImageReader
import android.os.Handler
import android.os.SystemClock
import android.util.Size
import android.view.Surface
import io.github.tengigabytes.anomalops.core.profile.PhysicalCamera
import kotlinx.coroutines.CompletableDeferred

/**
 * The still readers of one session layout, next to a shared preview surface. On the Pixel 10 Pro a session with
 * both JPEG_R and JPEG fails to configure and restarts the camera provider (docs/test/m2-stream-combos.md), so
 * singles (JPEG_R + RAW) and bursts (JPEG) need separate sessions.
 */
internal class ComboStreams(camera: PhysicalCamera, burst: Boolean, private val handler: Handler) : AutoCloseable {
    val jpegR = if (burst) null else reader(camera, "JPEG_R", ImageFormat.JPEG_R, STILL_IMAGES)
    val raw = if (burst) null else reader(camera, "RAW_SENSOR", ImageFormat.RAW_SENSOR, RAW_IMAGES)
    val jpeg = if (burst) reader(camera, "JPEG", ImageFormat.JPEG, BURST_IMAGES) else null

    /** Sensor timestamps of burst JPEGs, and when the first one reached the app (elapsedRealtimeNanos). */
    val jpegTimestamps = mutableListOf<Long>()
    val firstJpeg = CompletableDeferred<Long>()

    val stillSurfaces: List<Surface> get() = listOfNotNull(jpegR, jpeg, raw).map { it.surface }

    init {
        jpeg?.setOnImageAvailableListener({ r ->
            r.acquireNextImage()?.use {
                jpegTimestamps += it.timestamp
                firstJpeg.complete(SystemClock.elapsedRealtimeNanos())
            }
        }, handler)
    }

    /** The next image of [reader]: its byte count, or WxH for RAW. */
    fun next(reader: ImageReader): CompletableDeferred<String> {
        val result = CompletableDeferred<String>()
        reader.setOnImageAvailableListener({ r ->
            r.acquireNextImage()?.use {
                val raw = it.format == ImageFormat.RAW_SENSOR
                result.complete(if (raw) "RAW ${it.width}x${it.height}" else "${it.planes[0].buffer.remaining()} B")
            }
        }, handler)
        return result
    }

    override fun close() {
        listOfNotNull(jpegR, raw, jpeg).forEach { it.close() }
    }

    private fun reader(camera: PhysicalCamera, key: String, format: Int, images: Int): ImageReader {
        val (width, height) = camera.outputs.getValue(key).max.split('x').map(String::toInt)
        return ImageReader.newInstance(width, height, format, images)
    }

    companion object {
        val PREVIEW = Size(1440, 1080)
        private const val STILL_IMAGES = 2
        private const val RAW_IMAGES = 7
        private const val BURST_IMAGES = 8
    }
}
