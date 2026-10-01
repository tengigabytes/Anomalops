// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.core.imaging.tool

import io.github.tengigabytes.anomalops.core.imaging.develop.CfaLayout
import io.github.tengigabytes.anomalops.core.imaging.develop.RawFrame
import io.github.tengigabytes.anomalops.core.imaging.develop.ShadingMap
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The lens shading map as `DngCreator` stores it: in OpcodeList2 (always big-endian), one GainMap opcode per Bayer
 * position (row and column pitch 2, top and left 0 or 1), all on one grid spanning the image. Read back into a
 * [ShadingMap] (red, green on even rows, green on odd rows, blue). Null when the list has no such four maps.
 */
object GainMaps {
    private const val GAIN_MAP = 9
    private const val HEADER = 16
    private const val PITCH = 2
    private const val CHANNELS = 4
    private const val RED = 0
    private const val GREEN_EVEN = 1
    private const val GREEN_ODD = 2
    private const val BLUE = 3

    private class Grid(val channel: Int, val rows: Int, val columns: Int, val gains: FloatArray)

    fun read(list: ByteArray, raw: RawFrame): ShadingMap? {
        val buffer = ByteBuffer.wrap(list).order(ByteOrder.BIG_ENDIAN)
        val maps = mutableListOf<Grid>()
        var p = Int.SIZE_BYTES
        repeat(buffer.getInt(0)) {
            val id = buffer.getInt(p)
            val size = buffer.getInt(p + HEADER - Int.SIZE_BYTES)
            if (id == GAIN_MAP) gainMap(buffer, p + HEADER, raw.layout)?.let { maps += it }
            p += HEADER + size
        }
        val first = maps.firstOrNull()
        val sameGrid = first != null && maps.all { it.rows == first.rows && it.columns == first.columns }
        if (first == null || maps.map { it.channel }.toSet().size != CHANNELS || !sameGrid) return null
        val gains = FloatArray(CHANNELS * first.rows * first.columns)
        maps.forEach { m -> m.gains.forEachIndexed { i, g -> gains[i * CHANNELS + m.channel] = g } }
        return ShadingMap(first.columns, first.rows, gains)
    }

    /**
     * One GainMap's parameters (DNG 1.4, section 6): bounds, plane, pitch, grid, spacing, origin, then gains. The
     * spacing and origin are taken to span the image edge to edge, as `DngCreator` writes them.
     */
    private fun gainMap(b: ByteBuffer, at: Int, layout: CfaLayout): Grid? {
        val top = b.getInt(at)
        val left = b.getInt(at + 4)
        val rowPitch = b.getInt(at + 24)
        val colPitch = b.getInt(at + 28)
        val rows = b.getInt(at + 32)
        val columns = b.getInt(at + 36)
        val planes = b.getInt(at + 72)
        if (rowPitch != PITCH || colPitch != PITCH || planes != 1) return null
        val channel = when (layout.colourAt(left, top)) {
            CfaLayout.RED -> RED
            CfaLayout.BLUE -> BLUE
            else -> if (top % 2 == 0) GREEN_EVEN else GREEN_ODD
        }
        return Grid(channel, rows, columns, FloatArray(rows * columns) { b.getFloat(at + 76 + it * Float.SIZE_BYTES) })
    }
}
