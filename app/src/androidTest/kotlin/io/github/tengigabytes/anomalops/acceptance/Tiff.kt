// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.acceptance

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Reads the first IFD of a TIFF/DNG file; enough to check the raw image size (FR-64). */
internal object Tiff {
    private const val TAG_WIDTH = 256
    private const val TAG_HEIGHT = 257
    private const val TYPE_SHORT = 3
    private const val ENTRY_BYTES = 12
    private const val VALUE_AT = 8
    private const val UNSIGNED_SHORT = 0xFFFF
    private const val IFD_OFFSET_AT = 4
    private const val LITTLE_ENDIAN_MARK = 'I'.code.toByte()

    /** The entry's value, stored in place for the SHORT and LONG sizes used by the dimension tags. */
    private fun value(buffer: ByteBuffer, entry: Int): Int = if (buffer.getShort(entry + 2).toInt() == TYPE_SHORT) {
        buffer.getShort(entry + VALUE_AT).toInt() and UNSIGNED_SHORT
    } else {
        buffer.getInt(entry + VALUE_AT)
    }

    /** "WxH" of IFD0, the full-size raw image in a DNG from DngCreator. */
    fun imageSize(file: ByteArray): String {
        val order = if (file[0] == LITTLE_ENDIAN_MARK) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN
        val buffer = ByteBuffer.wrap(file).order(order)
        val ifd = buffer.getInt(IFD_OFFSET_AT)
        val tags = (0 until buffer.getShort(ifd).toInt()).associate { index ->
            val entry = ifd + 2 + index * ENTRY_BYTES
            (buffer.getShort(entry).toInt() and UNSIGNED_SHORT) to value(buffer, entry)
        }
        return "${tags[TAG_WIDTH]}x${tags[TAG_HEIGHT]}"
    }
}
