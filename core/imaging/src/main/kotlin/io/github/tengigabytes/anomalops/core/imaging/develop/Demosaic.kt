// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.core.imaging.develop

import io.github.tengigabytes.anomalops.core.imaging.align.Plane

/**
 * RAW to linear camera RGB, the first step of ADR-0015's pipeline (decode, align, merge, tone-map). Two speeds:
 * [halfSize] makes one RGB pixel of each 2 x 2 cell (fast, for alignment and choosing frames), [bilinear] keeps
 * every photosite and fills the two missing colours from the nearest photosites of that colour.
 */
object Demosaic {
    /** Half width and height: each cell's red, mean of its greens, and blue. */
    fun halfSize(raw: RawFrame): Rgb {
        val w = raw.width / 2
        val h = raw.height / 2
        val planes = List(COLOURS) { Plane(w, h) }
        for (y in 0 until h) {
            for (x in 0 until w) {
                val sums = FloatArray(COLOURS)
                for (dy in 0..1) {
                    for (dx in 0..1) {
                        val px = 2 * x + dx
                        val py = 2 * y + dy
                        sums[raw.layout.colourAt(px, py)] += raw.linear(px, py)
                    }
                }
                planes[CfaLayout.RED][x, y] = sums[CfaLayout.RED]
                planes[CfaLayout.GREEN][x, y] = sums[CfaLayout.GREEN] / 2
                planes[CfaLayout.BLUE][x, y] = sums[CfaLayout.BLUE]
            }
        }
        return Rgb(planes[0], planes[1], planes[2])
    }

    /**
     * Full size, bilinear: at each photosite its own colour as measured, the others averaged from the photosites of
     * that colour in the 3 x 3 neighbourhood (edges clamped). Soft at fine colour edges; a better demosaic can
     * replace it without changing the callers.
     */
    fun bilinear(raw: RawFrame): Rgb {
        val planes = List(COLOURS) { Plane(raw.width, raw.height) }
        for (y in 0 until raw.height) {
            for (x in 0 until raw.width) {
                val sums = FloatArray(COLOURS)
                val counts = IntArray(COLOURS)
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        val px = (x + dx).coerceIn(0, raw.width - 1)
                        val py = (y + dy).coerceIn(0, raw.height - 1)
                        val c = raw.layout.colourAt(px, py)
                        sums[c] += raw.linear(px, py)
                        counts[c]++
                    }
                }
                val own = raw.layout.colourAt(x, y)
                for (c in 0 until COLOURS) {
                    planes[c][x, y] = if (c == own) raw.linear(x, y) else sums[c] / counts[c]
                }
            }
        }
        return Rgb(planes[0], planes[1], planes[2])
    }

    private const val COLOURS = 3
}

/**
 * Camera RGB to a working colour space, as the capture request set it (ADR-0002): white-balance gains per channel
 * (`COLOR_CORRECTION_GAINS`, red, green, blue) then the 3 x 3 colour matrix (`COLOR_CORRECTION_TRANSFORM`, row by
 * row). Linear in and out; tone mapping comes after merging (ADR-0015).
 */
object ColourPipeline {
    private const val CHANNELS = 3
    private const val MATRIX = CHANNELS * CHANNELS

    fun apply(rgb: Rgb, gains: FloatArray, matrix: FloatArray): Rgb {
        require(gains.size == CHANNELS && matrix.size == MATRIX) { "gains ${gains.size}, matrix ${matrix.size}" }
        val input = rgb.channels()
        val out = List(CHANNELS) { Plane(rgb.width, rgb.height) }
        val balanced = FloatArray(CHANNELS)
        for (i in 0 until rgb.width * rgb.height) {
            for (c in 0 until CHANNELS) balanced[c] = input[c].data[i] * gains[c]
            for (row in 0 until CHANNELS) {
                var v = 0f
                for (c in 0 until CHANNELS) v += matrix[row * CHANNELS + c] * balanced[c]
                out[row].data[i] = v
            }
        }
        return Rgb(out[0], out[1], out[2])
    }
}
