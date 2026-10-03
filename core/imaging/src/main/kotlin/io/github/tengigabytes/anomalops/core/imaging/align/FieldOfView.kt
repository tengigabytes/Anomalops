// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.core.imaging.align

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * The part of a picture rendered from RAW that the camera's own still of the same shot shows, found by aligning
 * small copies of the two (maintainer's choice, 2026-10-03: match every shot rather than keep a table per lens).
 *
 * The camera's stills, like its preview, are a centred crop of the RAW frame, and the camera does not report it
 * (`SCALER_CROP_REGION` is the whole array). Pixel 10 Pro, 2026-10-03, seven shots: the ultra-wide sensor's stills
 * cover 84.8 to 85.2 % of the RAW's width and height, the main lens's 88.6 to 95 % depending on the focus
 * distance; no rotation, the centre within 0.1 %, and what distortion remains is under 2 pixels in 1512 except in
 * the ultra-wide's far corners (7 to 9), which the crop removes.
 */
object FieldOfView {
    /** Fractions of the rendered picture's width and height, from its top-left corner. */
    data class Crop(val left: Float, val top: Float, val right: Float, val bottom: Float)

    private const val MAX_SHIFT_SHARE = 0.03f
    private const val MAX_SCALE_DELTA = 0.25f
    private const val SCALE_STEP = 0.01f
    private const val MIN_LEVEL_SIZE = 24
    private const val MIN_SCALE = 0.75f
    private const val MAX_SCALE = 1.001f
    private const val MIN_CORRELATION = 0.5f
    private const val HALF = 0.5f

    /**
     * [camera] is the camera's still and [rendered] the picture from RAW, both as luma planes of the same small
     * size (about 250 pixels across is enough) and the same orientation. Null when no trustworthy match is found: a
     * dark or featureless scene, or a fit outside what a centred crop looks like. Brightness and contrast may
     * differ between the two; each plane is normalised first.
     */
    fun crop(camera: Plane, rendered: Plane): Crop? {
        require(camera.width == rendered.width && camera.height == rendered.height) { "planes differ in size" }
        val reference = normalised(camera)
        val frame = normalised(rendered)
        return if (reference == null || frame == null) null else match(reference, frame)
    }

    /** The crop from two normalised planes. */
    private fun match(reference: Plane, frame: Plane): Crop? {
        val w = reference.width
        val h = reference.height
        val maxShift = (MAX_SHIFT_SHARE * w).toInt().coerceAtLeast(1)
        val options = AlignOptions(maxShiftPx = maxShift, maxScaleDelta = MAX_SCALE_DELTA, scaleStep = SCALE_STEP)
        // A small top level keeps the exhaustive search over scale and shift short.
        val referencePyramid = Pyramid(reference, minSize = MIN_LEVEL_SIZE)
        val framePyramid = Pyramid(frame, minSize = MIN_LEVEL_SIZE, maxLevels = referencePyramid.top)
        val fit = GlobalAligner(options).align(referencePyramid, framePyramid)
        val plausible = fit.scale in MIN_SCALE..MAX_SCALE && abs(fit.dx) <= maxShift && abs(fit.dy) <= maxShift
        if (!plausible || correlation(reference, frame, fit) < MIN_CORRELATION) return null
        // The camera's pixel edges, -0.5 and size - 0.5, as positions in the rendered picture.
        return Crop(
            left = ((fit.mapX(-HALF, w) + HALF) / w).coerceIn(0f, 1f),
            top = ((fit.mapY(-HALF, h) + HALF) / h).coerceIn(0f, 1f),
            right = ((fit.mapX(w - HALF, w) + HALF) / w).coerceIn(0f, 1f),
            bottom = ((fit.mapY(h - HALF, h) + HALF) / h).coerceIn(0f, 1f),
        )
    }

    /** Zero mean, unit deviation; null for a plane without contrast. */
    private fun normalised(plane: Plane): Plane? {
        val mean = plane.data.average().toFloat()
        val deviation = sqrt(plane.data.sumOf { ((it - mean) * (it - mean)).toDouble() } / plane.data.size).toFloat()
        if (deviation <= 0f || !deviation.isFinite()) return null
        return Plane(plane.width, plane.height, FloatArray(plane.data.size) { (plane.data[it] - mean) / deviation })
    }

    /** The correlation of [reference] with [frame] read at the fitted positions, over the pixels that have data. */
    private fun correlation(reference: Plane, frame: Plane, fit: Similarity): Float {
        var ab = 0.0
        var aa = 0.0
        var bb = 0.0
        for (y in 0 until reference.height) {
            val fy = fit.mapY(y.toFloat(), reference.height)
            for (x in 0 until reference.width) {
                val b = frame.sample(fit.mapX(x.toFloat(), reference.width), fy)
                if (b.isNaN()) continue
                val a = reference[x, y]
                ab += a * b
                aa += a * a
                bb += b * b
            }
        }
        return if (aa <= 0.0 || bb <= 0.0) 0f else (ab / sqrt(aa * bb)).toFloat()
    }
}
