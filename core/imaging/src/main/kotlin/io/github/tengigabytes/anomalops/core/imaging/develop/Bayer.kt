// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.core.imaging.develop

import io.github.tengigabytes.anomalops.core.imaging.align.Plane

/**
 * `SENSOR_INFO_COLOR_FILTER_ARRANGEMENT`, read at run time (NFR-9): the colours of the 2 x 2 cell, row by row.
 * On the Pixel 10 Pro the main lens and the tele are GBRG, the ultra-wide RGGB (probe report of 2026-09-28).
 */
enum class CfaLayout(private val cell: String) {
    RGGB("RGGB"),
    GRBG("GRBG"),
    GBRG("GBRG"),
    BGGR("BGGR"),
    ;

    /** Colour index (0 red, 1 green, 2 blue) of the photosite at ([x], [y]). */
    fun colourAt(x: Int, y: Int): Int = when (cell[(y and 1) * 2 + (x and 1)]) {
        'R' -> RED
        'G' -> GREEN
        else -> BLUE
    }

    companion object {
        const val RED = 0
        const val GREEN = 1
        const val BLUE = 2

        /** From the Camera2 constant (0 RGGB, 1 GRBG, 2 GBRG, 3 BGGR); null for RGB or monochrome sensors. */
        fun fromCamera2(value: Int): CfaLayout? = entries.getOrNull(value)
    }
}

/**
 * One RAW_SENSOR frame as Camera2 gives it: 16-bit samples ([rowStride] in samples), the colour layout, the
 * black level of each of the four cell positions (`SENSOR_BLACK_LEVEL_PATTERN` or the per-frame
 * `SENSOR_DYNAMIC_BLACK_LEVEL`, row by row) and the white level.
 */
class RawFrame(
    val samples: ShortArray,
    val width: Int,
    val height: Int,
    val rowStride: Int,
    val layout: CfaLayout,
    val blackLevels: FloatArray,
    val whiteLevel: Float,
) {
    init {
        require(width >= 2 && height >= 2 && width % 2 == 0 && height % 2 == 0) { "RAW ${width}x$height" }
        require(blackLevels.size == CELL) { "four black levels, got ${blackLevels.size}" }
    }

    /** The sample at ([x], [y]) with its black level removed and scaled so that white is 1. Not clipped. */
    fun linear(x: Int, y: Int): Float {
        val black = blackLevels[(y and 1) * 2 + (x and 1)]
        return ((samples[y * rowStride + x].toInt() and U16) - black) / (whiteLevel - black)
    }

    /**
     * `Demosaic.halfSize` of the 2 x 2 cell whose top-left photosite is ([x], [y]), both even: its red, the mean of
     * its greens and its blue into [out].
     */
    fun cell(x: Int, y: Int, out: FloatArray) {
        out.fill(0f)
        for (dy in 0..1) {
            for (dx in 0..1) out[layout.colourAt(x + dx, y + dy)] += linear(x + dx, y + dy)
        }
        out[CfaLayout.GREEN] /= 2
    }

    private companion object {
        const val CELL = 4
        const val U16 = 0xFFFF
    }
}

/** Linear R, G, B planes of one frame, in camera colour until [ColourPipeline] converts them. */
class Rgb(val red: Plane, val green: Plane, val blue: Plane) {
    val width: Int get() = red.width
    val height: Int get() = red.height

    fun channels(): List<Plane> = listOf(red, green, blue)

    /** Rec. 709 luminance weights; what alignment and FR-33 use to decide (ADR-0015). */
    fun luma(): Plane {
        val out = Plane(width, height)
        for (i in out.data.indices) {
            out.data[i] = LUMA_R * red.data[i] + LUMA_G * green.data[i] + LUMA_B * blue.data[i]
        }
        return out
    }

    private companion object {
        const val LUMA_R = 0.2126f
        const val LUMA_G = 0.7152f
        const val LUMA_B = 0.0722f
    }
}
