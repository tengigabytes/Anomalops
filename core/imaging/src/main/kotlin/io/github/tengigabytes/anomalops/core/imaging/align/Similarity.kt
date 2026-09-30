// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.core.imaging.align

/**
 * Where a reference pixel lands in another frame: scaled by [scale] about the image centre (focus breathing,
 * FR-33) and shifted by [dx], [dy] (hand shake and surge), in full-resolution pixels of the plane being aligned.
 * `frame(map(x, y)) ≈ reference(x, y)`.
 */
data class Similarity(val scale: Float = 1f, val dx: Float = 0f, val dy: Float = 0f) {
    /** The frame position of reference pixel ([x], [y]) in a [width] x [height] plane. */
    fun mapX(x: Float, width: Int): Float = centre(width) + scale * (x - centre(width)) + dx

    fun mapY(y: Float, height: Int): Float = centre(height) + scale * (y - centre(height)) + dy

    /**
     * The same transform at pyramid [level] (each level halves the size with 2 x 2 means): positions on that level
     * are (x0 + 0.5) / 2^level - 0.5, so the centre moves with them and the shift shrinks by 2^level.
     */
    fun atLevel(level: Int): LevelTransform {
        val factor = (1 shl level).toFloat()
        return LevelTransform(scale, dx / factor, dy / factor, factor)
    }

    /** [atLevel] in the coordinates of one pyramid level, with the full-resolution centre carried down. */
    class LevelTransform(val scale: Float, val dx: Float, val dy: Float, private val factor: Float) {
        fun mapX(x: Float, fullWidth: Int): Float = levelCentre(fullWidth) + scale * (x - levelCentre(fullWidth)) + dx

        fun mapY(y: Float, fullHeight: Int): Float =
            levelCentre(fullHeight) + scale * (y - levelCentre(fullHeight)) + dy

        private fun levelCentre(fullSize: Int) = (centre(fullSize) + HALF) / factor - HALF
    }

    companion object {
        private const val HALF = 0.5f

        fun centre(size: Int): Float = (size - 1) * HALF
    }
}
