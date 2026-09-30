// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.core.imaging.align

import kotlin.math.exp
import kotlin.random.Random

/**
 * A continuous test scene: Gaussian blobs of random position, size and brightness on a grey floor, so frames can
 * be rendered at any transform (and blur) with exact ground truth.
 */
class SyntheticScene(width: Int, height: Int, blobs: Int = 400, seed: Int = 1) {
    private class Blob(val x: Float, val y: Float, val sigma: Float, val amplitude: Float)

    private val blobs = Random(seed).let { r ->
        List(blobs) {
            Blob(
                r.nextFloat() * width,
                r.nextFloat() * height,
                MIN_SIGMA + r.nextFloat() * SIGMA_RANGE,
                (r.nextFloat() - 0.5f) * AMPLITUDE,
            )
        }
    }

    /** Brightness at scene point ([x], [y]) seen with an extra Gaussian blur of [blur] pixels (defocus). */
    fun at(x: Float, y: Float, blur: Float = 0f): Float {
        var v = FLOOR
        for (b in blobs) {
            val s2 = b.sigma * b.sigma + blur * blur
            val dx = x - b.x
            val dy = y - b.y
            val r2 = dx * dx + dy * dy
            if (r2 < CUTOFF * s2) v += b.amplitude * (b.sigma * b.sigma / s2) * exp(-r2 / (2 * s2))
        }
        return v
    }

    /**
     * A [width] x [height] frame in which the scene point that reference pixel p shows lies at [truth] applied to
     * p. [local] adds an extra shift inside a region of the frame (a subject moving on its own).
     */
    fun render(
        width: Int,
        height: Int,
        truth: Similarity = Similarity(),
        blur: Float = 0f,
        local: Pair<Region, Pair<Float, Float>>? = null,
    ): Plane {
        val out = Plane(width, height)
        val cx = Similarity.centre(width)
        val cy = Similarity.centre(height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                var fx = x.toFloat()
                var fy = y.toFloat()
                local?.let { (region, shift) ->
                    if (x in region.left until region.right && y in region.top until region.bottom) {
                        fx -= shift.first
                        fy -= shift.second
                    }
                }
                // Invert truth: the reference position p with truth(p) = (fx, fy).
                val px = cx + (fx - cx - truth.dx) / truth.scale
                val py = cy + (fy - cy - truth.dy) / truth.scale
                out[x, y] = at(px, py, blur)
            }
        }
        return out
    }

    private companion object {
        const val FLOOR = 100f
        const val MIN_SIGMA = 1.5f
        const val SIGMA_RANGE = 6f
        const val AMPLITUDE = 120f
        const val CUTOFF = 18f
    }
}
