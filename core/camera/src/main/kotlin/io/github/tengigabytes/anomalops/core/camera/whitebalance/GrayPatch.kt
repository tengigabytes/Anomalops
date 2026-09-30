// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.core.camera.whitebalance

/**
 * `SENSOR_INFO_COLOR_FILTER_ARRANGEMENT`: the colours of the 2 × 2 Bayer cell, read left to right, top to bottom.
 */
enum class Cfa(internal val cell: String) {
    RGGB("RGGB"),
    GRBG("GRBG"),
    GBRG("GBRG"),
    BGGR("BGGR"),
}

/** A rectangle of the RAW frame, in pixels, left and top inclusive, right and bottom exclusive. */
data class PatchRect(val left: Int, val top: Int, val right: Int, val bottom: Int)

/** Black-level-subtracted means of the four Bayer channels over a patch, and the share of clipped photosites. */
data class ChannelMeans(val r: Double, val gEven: Double, val gOdd: Double, val b: Double, val clippedShare: Double)

/**
 * FR-22: averages a gray-card patch of a `RAW_SENSOR` frame. RAW is linear and untouched by the current gains and
 * colour matrix, so the channel ratios are the sensor's own response to the light at the card (ADR-0002).
 */
object GrayPatch {
    /**
     * [raw] holds one 16-bit value per photosite, [rowStride] values per row (the plane's row stride in bytes / 2).
     * [blackLevels] is `SENSOR_DYNAMIC_BLACK_LEVEL` (or the static pattern) in the same R, G even, G odd, B order as
     * the result; values at or above [whiteLevel] count as clipped.
     */
    fun means(
        raw: ShortArray,
        rowStride: Int,
        cfa: Cfa,
        blackLevels: DoubleArray,
        whiteLevel: Int,
        rect: PatchRect,
    ): ChannelMeans {
        require(blackLevels.size == CHANNELS) { "expected $CHANNELS black levels" }
        require(rect.right > rect.left + 1 && rect.bottom > rect.top + 1) { "patch must cover a full Bayer cell" }
        val sums = DoubleArray(CHANNELS)
        val counts = IntArray(CHANNELS)
        var clipped = 0
        for (y in rect.top until rect.bottom) {
            for (x in rect.left until rect.right) {
                val value = raw[y * rowStride + x].toInt() and UNSIGNED_16
                val channel = channelAt(cfa, x, y)
                if (value >= whiteLevel) clipped++
                sums[channel] += value - blackLevels[channel]
                counts[channel]++
            }
        }
        val mean = { c: Int -> sums[c] / counts[c] }
        val total = counts.sum().toDouble()
        return ChannelMeans(mean(R), mean(G_EVEN), mean(G_ODD), mean(B), clipped / total)
    }

    /**
     * R = 0, G even = 1, G odd = 2, B = 3, as `COLOR_CORRECTION_GAINS` (`RggbChannelVector`: green on even rows,
     * green on odd rows). FR-22 gives both greens the same gain, so a mix-up between the two would not matter.
     */
    internal fun channelAt(cfa: Cfa, x: Int, y: Int): Int {
        val colour = cfa.cell[(y % 2) * 2 + x % 2]
        return when (colour) {
            'R' -> R
            'B' -> B
            else -> if (y % 2 == 0) G_EVEN else G_ODD
        }
    }

    private const val CHANNELS = 4
    private const val UNSIGNED_16 = 0xFFFF
    private const val R = 0
    private const val G_EVEN = 1
    private const val G_ODD = 2
    private const val B = 3
}
