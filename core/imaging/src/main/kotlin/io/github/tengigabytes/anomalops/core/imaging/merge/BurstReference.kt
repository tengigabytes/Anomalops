// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.core.imaging.merge

import io.github.tengigabytes.anomalops.core.imaging.develop.RawFrame

/**
 * Which frame of an equal-exposure burst the others are merged onto: the sharpest, since the merge inherits its
 * reference's blur (a hand-held burst of 2026-10-02 merged onto a frame at 0.69 of the sharpest one's score came
 * out softer than a single frame). Measured on the RAW samples, before anything is decoded.
 */
object BurstReference {
    private const val SAME_COLOUR = 2
    private const val STEP = 4
    private const val CENTRE_WEIGHT = 4

    /** The index of the sharpest of [frames]; the first of equals. */
    fun sharpest(frames: List<RawFrame>): Int {
        require(frames.isNotEmpty()) { "no frames" }
        val scores = frames.map { sharpness(it) }
        return scores.indices.maxBy { scores[it] }
    }

    /**
     * The mean squared Laplacian of one Bayer phase (the cell's top-left photosite, its neighbours two photosites
     * away), on every other such photosite each way. Only for comparing frames of one scene and exposure: noise
     * adds the same amount to each.
     */
    fun sharpness(raw: RawFrame): Double {
        var sum = 0.0
        var n = 0
        for (y in SAME_COLOUR until raw.height - SAME_COLOUR step STEP) {
            for (x in SAME_COLOUR until raw.width - SAME_COLOUR step STEP) {
                val laplacian = CENTRE_WEIGHT * raw.linear(x, y) -
                    raw.linear(x - SAME_COLOUR, y) - raw.linear(x + SAME_COLOUR, y) -
                    raw.linear(x, y - SAME_COLOUR) - raw.linear(x, y + SAME_COLOUR)
                sum += laplacian.toDouble() * laplacian
                n++
            }
        }
        return if (n == 0) 0.0 else sum / n
    }
}
