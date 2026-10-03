// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.capture

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.SystemClock
import io.github.tengigabytes.anomalops.core.gpu.GlesContext
import io.github.tengigabytes.anomalops.core.gpu.GpuLowLightPipeline
import io.github.tengigabytes.anomalops.core.gpu.LowLightBurst
import io.github.tengigabytes.anomalops.core.gpu.LowLightPicture
import io.github.tengigabytes.anomalops.core.imaging.align.FieldOfView
import io.github.tengigabytes.anomalops.core.imaging.align.Plane
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * FR-17 in the app: a burst merged on the GPU, cropped to what the camera's own still of the shot shows, and
 * encoded as a plain JPEG. The GL context belongs to one thread (ADR-0017), so everything GL runs on this object's
 * own thread; the context and the pipeline are made on the first burst and kept until [close].
 *
 * The crop: the RAW frame is wider than the camera's stills and preview, by an amount the camera does not report
 * and that changes with the focus distance on the main lens ([FieldOfView]). Each merged picture is matched
 * against the camera's still of the same shot; when no match is found (a dark or featureless scene) the lens's
 * last matched crop is used, and before any match the whole frame.
 */
class LowLightRenderer : AutoCloseable {
    /**
     * A merged picture as an upright JPEG, with what the pipeline did and how long merging, matching the field of
     * view and encoding took. [crop] is what was kept of the rendered frame (null: all of it); [cropMatched] tells
     * whether it was found on this shot.
     */
    class Rendered(
        val jpeg: ByteArray,
        val picture: LowLightPicture,
        val crop: FieldOfView.Crop?,
        val cropMatched: Boolean,
        val mergeMs: Long,
        val matchMs: Long,
        val encodeMs: Long,
    )

    private val executor = Executors.newSingleThreadExecutor { Thread(it, "lowlight-gpu") }
    private val dispatcher = executor.asCoroutineDispatcher()
    private var context: GlesContext? = null
    private var pipeline: GpuLowLightPipeline? = null
    private val lastCrops = HashMap<String, FieldOfView.Crop>()

    /**
     * [burst] merged and rendered, cropped to the field of view of [cameraStill] (the camera's encoded still of
     * the same shot, from lens [lensId]) and turned by [rotationDegrees] clockwise (the lens's sensor orientation).
     */
    suspend fun render(
        burst: LowLightBurst,
        rotationDegrees: Int,
        cameraStill: ByteArray? = null,
        lensId: String = "",
    ): Rendered = withContext(dispatcher) {
        val started = SystemClock.elapsedRealtime()
        val picture = pipeline().render(burst)
        val merged = SystemClock.elapsedRealtime()
        val flat = Bitmap.createBitmap(picture.argb, picture.width, picture.height, Bitmap.Config.ARGB_8888)
        val found = cameraStill?.let { matchedCrop(flat, it) }
        if (found != null) lastCrops[lensId] = found
        val crop = found ?: lastCrops[lensId]
        val matched = SystemClock.elapsedRealtime()
        val jpeg = encode(flat, crop, rotationDegrees)
        flat.recycle()
        val done = SystemClock.elapsedRealtime()
        Rendered(jpeg, picture, crop, found != null, merged - started, matched - merged, done - matched)
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

    /** [FieldOfView.crop] of [rendered] against the camera's still, both at about [MATCH_WIDTH] pixels across. */
    private fun matchedCrop(rendered: Bitmap, cameraStill: ByteArray): FieldOfView.Crop? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(cameraStill, 0, cameraStill.size, bounds)
        // The still's pixels are in the sensor's orientation, as the rendered frame is; EXIF turns it for viewing.
        val sameShape =
            bounds.outWidth > 0 && (bounds.outWidth > bounds.outHeight) == (rendered.width > rendered.height)
        val small = BitmapFactory.Options().apply {
            inSampleSize = Integer.highestOneBit((bounds.outWidth / MATCH_WIDTH).coerceAtLeast(1))
        }
        val camera = if (sameShape) BitmapFactory.decodeByteArray(cameraStill, 0, cameraStill.size, small) else null
        return camera?.let {
            val scaled = Bitmap.createScaledBitmap(rendered, it.width, it.height, true)
            val crop = FieldOfView.crop(luma(it), luma(scaled))
            it.recycle()
            if (scaled !== rendered) scaled.recycle()
            crop
        }
    }

    private fun luma(bitmap: Bitmap): Plane {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return Plane(
            bitmap.width,
            bitmap.height,
            FloatArray(pixels.size) {
                val p = pixels[it]
                LUMA_R * (p shr RED_SHIFT and CODE) + LUMA_G * (p shr GREEN_SHIFT and CODE) + LUMA_B * (p and CODE)
            },
        )
    }

    private fun encode(flat: Bitmap, crop: FieldOfView.Crop?, rotationDegrees: Int): ByteArray {
        val left = ((crop?.left ?: 0f) * flat.width).roundToInt().coerceIn(0, flat.width - 1)
        val top = ((crop?.top ?: 0f) * flat.height).roundToInt().coerceIn(0, flat.height - 1)
        val right = ((crop?.right ?: 1f) * flat.width).roundToInt().coerceIn(left + 1, flat.width)
        val bottom = ((crop?.bottom ?: 1f) * flat.height).roundToInt().coerceIn(top + 1, flat.height)
        val turn = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
        val upright = Bitmap.createBitmap(flat, left, top, right - left, bottom - top, turn, false)
        val out = ByteArrayOutputStream()
        upright.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        if (upright !== flat) upright.recycle()
        return out.toByteArray()
    }

    private companion object {
        const val JPEG_QUALITY = 95

        /** The match runs on copies about this wide: enough for a fit within half a percent, quick on the CPU. */
        const val MATCH_WIDTH = 192
        const val LUMA_R = 0.2126f
        const val LUMA_G = 0.7152f
        const val LUMA_B = 0.0722f
        const val RED_SHIFT = 16
        const val GREEN_SHIFT = 8
        const val CODE = 0xFF
    }
}
