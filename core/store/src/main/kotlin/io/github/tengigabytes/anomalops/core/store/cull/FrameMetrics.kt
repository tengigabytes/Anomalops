// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.core.store.cull

/**
 * An 8-bit luma image, row-major, values 0–255. FR-69 scores a small copy of each frame (for example the JPEG
 * decoded at 1/8 size), so the metrics compare frames of one stack, not absolute sharpness.
 */
class Luma(val width: Int, val height: Int, val pixels: IntArray) {
    init {
        require(width > 0 && height > 0) { "empty image" }
        require(pixels.size == width * height) { "expected ${width * height} pixels, got ${pixels.size}" }
    }

    operator fun get(x: Int, y: Int): Int = pixels[y * width + x]
}

/** FR-69's measurable criteria for one frame. */
data class FrameMetrics(
    /** Variance of the 4-neighbour Laplacian: higher is sharper. */
    val sharpness: Double,
    /** Share of pixels at or below [FrameMetrics.DARK_LEVEL]. */
    val darkClip: Double,
    /** Share of pixels at or above [FrameMetrics.BRIGHT_LEVEL]. */
    val brightClip: Double,
) {
    companion object {
        const val DARK_LEVEL = 2
        const val BRIGHT_LEVEL = 253
        private const val MIN_SIDE = 3
        private const val NEIGHBOURS = 4

        fun of(image: Luma): FrameMetrics {
            val total = image.pixels.size.toDouble()
            return FrameMetrics(
                sharpness = laplacianVariance(image),
                darkClip = image.pixels.count { it <= DARK_LEVEL } / total,
                brightClip = image.pixels.count { it >= BRIGHT_LEVEL } / total,
            )
        }

        /** Over the interior pixels; an image under 3 × 3 has no interior and scores 0. */
        fun laplacianVariance(image: Luma): Double {
            if (image.width < MIN_SIDE || image.height < MIN_SIDE) return 0.0
            var sum = 0.0
            var sumSquares = 0.0
            for (y in 1 until image.height - 1) {
                for (x in 1 until image.width - 1) {
                    val around = image[x - 1, y] + image[x + 1, y] + image[x, y - 1] + image[x, y + 1]
                    val l = around - NEIGHBOURS * image[x, y]
                    sum += l
                    sumSquares += l.toDouble() * l
                }
            }
            val n = (image.width - 2).toDouble() * (image.height - 2)
            val mean = sum / n
            return sumSquares / n - mean * mean
        }
    }
}
