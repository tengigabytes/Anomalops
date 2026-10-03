// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.core.imaging.tool

import io.github.tengigabytes.anomalops.core.imaging.develop.CfaLayout
import io.github.tengigabytes.anomalops.core.imaging.develop.NoiseProfile
import io.github.tengigabytes.anomalops.core.imaging.develop.RawFrame
import io.github.tengigabytes.anomalops.core.imaging.develop.ShadingMap
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.pow

/**
 * What [StackTool] needs from one DNG: the RAW frame, the white balance it was shot with ([asShotNeutral], camera
 * RGB of a neutral, green 1), the forward matrix for D65 when there is one (white-balanced camera RGB to XYZ D50),
 * the lens shading gain maps of OpcodeList2 when the capture carried a shading map, and [postRawGain]: the gain
 * the camera applied after the RAW (`CONTROL_POST_RAW_SENSITIVITY_BOOST` / 100), which `DngCreator` stores as
 * BaselineExposure in stops (seen 2026-10-03: a boost of 711 gave 2.82); 1 without the tag.
 */
class DngImage(
    val raw: RawFrame,
    val asShotNeutral: FloatArray?,
    val forwardMatrix: FloatArray?,
    val shading: ShadingMap?,
    val postRawGain: Float = 1f,
    val noise: NoiseProfile? = null,
)

/**
 * Reads the uncompressed, strip-based Bayer DNGs that Android's `DngCreator` writes (`DngStore`, ADR-0005): the
 * CFA image (in IFD 0 or a SubIFD), its black and white levels, AsShotNeutral, ForwardMatrix and the GainMap
 * opcodes. Not a general DNG reader: compressed or tiled images are refused.
 */
object DngReader {
    private const val NEW_SUBFILE_TYPE = 254
    private const val WIDTH = 256
    private const val HEIGHT = 257
    private const val BITS = 258
    private const val COMPRESSION = 259
    private const val PHOTOMETRIC = 262
    private const val STRIP_OFFSETS = 273
    private const val STRIP_COUNTS = 279
    private const val CFA_PATTERN = 33422
    private const val BLACK_LEVEL = 50714
    private const val WHITE_LEVEL = 50717
    private const val AS_SHOT_NEUTRAL = 50728
    private const val BASELINE_EXPOSURE = 50730
    private const val ILLUMINANT_1 = 50778
    private const val ILLUMINANT_2 = 50779
    private const val FORWARD_1 = 50964
    private const val FORWARD_2 = 50965
    private const val OPCODE_LIST_2 = 51009
    private const val NOISE_PROFILE = 51041
    private const val NOISE_VALUES = 6
    private const val CFA = 32803
    private const val D65 = 21
    private const val BITS_16 = 16
    private const val CELL = 4

    fun read(bytes: ByteArray): DngImage {
        val tiff = Tiff(bytes)
        val ifds = tiff.ifds()
        val main = ifds.firstOrNull { it.int(PHOTOMETRIC) == CFA && it.int(NEW_SUBFILE_TYPE, 0) == 0 }
            ?: error("no full-size CFA image")
        val root = ifds.first()
        require(main.int(COMPRESSION) == 1 && main.int(BITS) == BITS_16) { "only uncompressed 16-bit RAW" }
        val width = main.int(WIDTH)!!
        val height = main.int(HEIGHT)!!
        val samples = tiff.samples(main.numbers(STRIP_OFFSETS)!!, main.numbers(STRIP_COUNTS)!!, width * height)
        val pattern = main.numbers(CFA_PATTERN)!!.joinToString("") { "RGB"[it.toInt()].toString() }
        val black = main.numbers(BLACK_LEVEL)?.map { it.toFloat() } ?: listOf(0f)
        val raw = RawFrame(
            samples,
            width,
            height,
            width,
            CfaLayout.valueOf(pattern),
            FloatArray(CELL) { black[it % black.size] },
            main.numbers(WHITE_LEVEL)!!.first().toFloat(),
        )
        val shading = (main.bytes(OPCODE_LIST_2) ?: root.bytes(OPCODE_LIST_2))?.let { GainMaps.read(it, raw) }
        val gain = root.floats(BASELINE_EXPOSURE)?.first()?.let { 2f.pow(it) } ?: 1f
        // NoiseProfile as `DngCreator` writes it: scale and offset for red, green and blue in turn.
        val noise = root.floats(NOISE_PROFILE)?.takeIf { it.size == NOISE_VALUES }?.let { n ->
            NoiseProfile(FloatArray(3) { n[2 * it] }, FloatArray(3) { n[2 * it + 1] })
        }
        return DngImage(raw, root.floats(AS_SHOT_NEUTRAL), forwardMatrix(root), shading, gain, noise)
    }

    /** ForwardMatrix for D65 if one of the two is calibrated for it, else the first; null without one. */
    private fun forwardMatrix(ifd: Tiff.Ifd): FloatArray? =
        if (ifd.int(ILLUMINANT_2) == D65 && ifd.has(FORWARD_2)) ifd.floats(FORWARD_2) else ifd.floats(FORWARD_1)
}

/** Minimal TIFF structure reading: IFDs (with SubIFDs) and their numeric or raw-byte entries. */
internal class Tiff(private val bytes: ByteArray) {
    private val order = if (bytes[0] == 'I'.code.toByte()) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN
    private val buffer = ByteBuffer.wrap(bytes).order(order)

    class Entry(val type: Int, val count: Int, val offset: Int)

    inner class Ifd(private val entries: Map<Int, Entry>) {
        fun has(tag: Int) = tag in entries

        fun int(tag: Int, default: Int? = null): Int? = numbers(tag)?.first()?.toInt() ?: default

        fun floats(tag: Int): FloatArray? = numbers(tag)?.map { it.toFloat() }?.toFloatArray()

        fun numbers(tag: Int): List<Double>? = entries[tag]?.let { e -> List(e.count) { value(e, it) } }

        fun bytes(tag: Int): ByteArray? = entries[tag]?.let { bytes.copyOfRange(it.offset, it.offset + it.count) }
    }

    fun ifds(): List<Ifd> {
        val out = mutableListOf<Ifd>()
        var next = buffer.getInt(FIRST_IFD)
        while (next != 0) {
            val (ifd, following) = readIfd(next)
            out += ifd
            ifd.numbers(SUB_IFD)?.forEach { out += readIfd(it.toInt()).first }
            next = following
        }
        return out
    }

    /** 16-bit samples from the strips at [offsets] ([counts] bytes each), in the file's byte order. */
    fun samples(offsets: List<Double>, counts: List<Double>, size: Int): ShortArray {
        val out = ShortArray(size)
        var i = 0
        offsets.zip(counts).forEach { (offset, count) ->
            val view = ByteBuffer.wrap(bytes, offset.toInt(), count.toInt()).order(order).asShortBuffer()
            val n = minOf(view.remaining(), size - i)
            view.get(out, i, n)
            i += n
        }
        require(i == size) { "strips hold $i of $size samples" }
        return out
    }

    private fun readIfd(at: Int): Pair<Ifd, Int> {
        val count = buffer.getShort(at).toInt() and U16
        val entries = (0 until count).associate { k ->
            val p = at + 2 + k * ENTRY
            val type = buffer.getShort(p + 2).toInt() and U16
            val n = buffer.getInt(p + 4)
            val inline = n * SIZES.getValue(type) <= INLINE
            (buffer.getShort(p).toInt() and U16) to Entry(type, n, if (inline) p + VALUE else buffer.getInt(p + VALUE))
        }
        return Ifd(entries) to buffer.getInt(at + 2 + count * ENTRY)
    }

    private fun value(e: Entry, i: Int): Double {
        val p = e.offset + i * SIZES.getValue(e.type)
        return when (e.type) {
            BYTE, UNDEFINED -> (bytes[p].toInt() and U8).toDouble()
            SHORT -> (buffer.getShort(p).toInt() and U16).toDouble()
            LONG -> (buffer.getInt(p).toLong() and U32).toDouble()
            SLONG -> buffer.getInt(p).toDouble()
            RATIONAL -> (buffer.getInt(p).toLong() and U32).toDouble() / (buffer.getInt(p + 4).toLong() and U32)
            SRATIONAL -> buffer.getInt(p).toDouble() / buffer.getInt(p + 4)
            FLOAT -> buffer.getFloat(p).toDouble()
            DOUBLE -> buffer.getDouble(p)
            else -> error("TIFF type ${e.type}")
        }
    }

    private companion object {
        const val FIRST_IFD = 4
        const val SUB_IFD = 330
        const val ENTRY = 12
        const val VALUE = 8
        const val INLINE = 4
        const val U8 = 0xFF
        const val U16 = 0xFFFF
        const val U32 = 0xFFFFFFFFL
        const val BYTE = 1
        const val SHORT = 3
        const val LONG = 4
        const val RATIONAL = 5
        const val UNDEFINED = 7
        const val SLONG = 9
        const val SRATIONAL = 10
        const val FLOAT = 11
        const val DOUBLE = 12
        val SIZES = mapOf(
            1 to 1, 2 to 1, 3 to 2, 4 to 4, 5 to 8, 6 to 1,
            7 to 1, 8 to 2, 9 to 4, 10 to 8, 11 to 4, 12 to 8,
        )
    }
}
