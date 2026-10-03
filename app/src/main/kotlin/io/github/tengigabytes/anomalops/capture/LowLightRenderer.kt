// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.capture

import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import io.github.tengigabytes.anomalops.core.gpu.GlesContext
import io.github.tengigabytes.anomalops.core.gpu.GpuLowLightPipeline
import io.github.tengigabytes.anomalops.core.gpu.LowLightBurst
import io.github.tengigabytes.anomalops.core.gpu.LowLightPicture
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

/**
 * FR-17 in the app: a burst merged on the GPU and encoded as a plain JPEG. The GL context belongs to one thread
 * (ADR-0017), so everything GL runs on this object's own thread; the context and the pipeline are made on the
 * first burst and kept until [close].
 */
class LowLightRenderer : AutoCloseable {
    /** A merged picture as an upright JPEG, with what the pipeline did and how long merging and encoding took. */
    class Rendered(val jpeg: ByteArray, val picture: LowLightPicture, val mergeMs: Long, val encodeMs: Long)

    private val executor = Executors.newSingleThreadExecutor { Thread(it, "lowlight-gpu") }
    private val dispatcher = executor.asCoroutineDispatcher()
    private var context: GlesContext? = null
    private var pipeline: GpuLowLightPipeline? = null

    /** [burst] merged and rendered, turned by [rotationDegrees] clockwise (the lens's sensor orientation). */
    suspend fun render(burst: LowLightBurst, rotationDegrees: Int): Rendered = withContext(dispatcher) {
        val started = SystemClock.elapsedRealtime()
        val picture = pipeline().render(burst)
        val merged = SystemClock.elapsedRealtime()
        val jpeg = encode(picture, rotationDegrees)
        Rendered(jpeg, picture, merged - started, SystemClock.elapsedRealtime() - merged)
    }

    override fun close() {
        executor.execute {
            pipeline?.close()
            context?.close()
            pipeline = null
            context = null
        }
        executor.shutdown()
    }

    private fun pipeline(): GpuLowLightPipeline {
        if (context == null) context = GlesContext.create()
        return pipeline ?: GpuLowLightPipeline().also { pipeline = it }
    }

    private fun encode(picture: LowLightPicture, rotationDegrees: Int): ByteArray {
        val flat = Bitmap.createBitmap(picture.argb, picture.width, picture.height, Bitmap.Config.ARGB_8888)
        val turn = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
        val upright = Bitmap.createBitmap(flat, 0, 0, flat.width, flat.height, turn, false)
        val out = ByteArrayOutputStream()
        upright.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        if (upright !== flat) upright.recycle()
        flat.recycle()
        return out.toByteArray()
    }

    private companion object {
        const val JPEG_QUALITY = 95
    }
}
