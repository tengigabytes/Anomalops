// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.core.imaging.develop

/**
 * Lens shading gains (`STATISTICS_LENS_SHADING_CORRECTION_MAP`, Camera2 `LensShadingMap`) on a [columns] x [rows]
 * grid, interpolated bilinearly between grid points; the first and last points sit on the image's edges. [gains]
 * is in `LensShadingMap.copyGainFactors` order: for each grid point, row by row, red, green on even rows, green on
 * odd rows, blue. Each gain is >= 1 and brightens the darker corners back to the centre's level, per channel, so it
 * also removes colour shading.
 *
 * UNVERIFIED(G0): that the Pixel's RAW_SENSOR output has no shading applied (Camera2 leaves RAW uncorrected and
 * DNGs carry the map as a gain-map opcode), and that the map spans the RAW image of a 2x-crop physical stream
 * rather than the whole sensor. The request must set `STATISTICS_LENS_SHADING_MAP_MODE_ON` to get the map.
 * 2026-10-01 (docs/test/m9-macro-land.md, section 7): all six back lenses return a 33 x 25 map, and the 2x-crop
 * streams' maps fall off far less at the corners, as they would if each covered its own RAW image.
 */
class ShadingMap(val columns: Int, val rows: Int, val gains: FloatArray) {
    init {
        require(columns >= 2 && rows >= 2) { "grid ${columns}x$rows" }
        require(gains.size == CHANNELS * columns * rows) { "gains ${gains.size} for ${columns}x$rows" }
    }

    /**
     * Red, green (the mean of both greens) and blue gains into [out] at position ([x], [y]) of a [width] x [height]
     * image; positions outside the image take the edge's gains.
     */
    fun gainsAt(x: Float, y: Float, width: Int, height: Int, out: FloatArray) {
        val gx = (x / (width - 1) * (columns - 1)).coerceIn(0f, columns - 1f)
        val gy = (y / (height - 1) * (rows - 1)).coerceIn(0f, rows - 1f)
        val x0 = gx.toInt().coerceAtMost(columns - 2)
        val y0 = gy.toInt().coerceAtMost(rows - 2)
        val fx = gx - x0
        val fy = gy - y0
        val red = at(x0, y0, fx, fy, RED)
        val green = (at(x0, y0, fx, fy, GREEN_EVEN) + at(x0, y0, fx, fy, GREEN_ODD)) / 2
        out[CfaLayout.RED] = red
        out[CfaLayout.GREEN] = green
        out[CfaLayout.BLUE] = at(x0, y0, fx, fy, BLUE)
    }

    private fun at(x0: Int, y0: Int, fx: Float, fy: Float, channel: Int): Float {
        fun g(x: Int, y: Int) = gains[(y * columns + x) * CHANNELS + channel]
        val top = g(x0, y0) + (g(x0 + 1, y0) - g(x0, y0)) * fx
        val bottom = g(x0, y0 + 1) + (g(x0 + 1, y0 + 1) - g(x0, y0 + 1)) * fx
        return top + (bottom - top) * fy
    }

    private companion object {
        const val CHANNELS = 4
        const val RED = 0
        const val GREEN_EVEN = 1
        const val GREEN_ODD = 2
        const val BLUE = 3
    }
}
