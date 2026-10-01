// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.core.imaging.tool

import io.github.tengigabytes.anomalops.core.imaging.align.SyntheticScene
import io.github.tengigabytes.anomalops.core.imaging.develop.CfaLayout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.imageio.ImageIO

class DngReaderTest {
    @get:Rule
    val temp = TemporaryFolder()

    /** A little-endian DNG laid out as `DngCreator` writes one: one IFD, the CFA strip, OpcodeList2 gain maps. */
    private fun dng(width: Int, height: Int, gainMaps: Boolean, sample: (Int, Int) -> Int): ByteArray {
        val strip = ByteBuffer.allocate(width * height * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (y in 0 until height) for (x in 0 until width) strip.putShort(sample(x, y).toShort())
        val entries = sortedMapOf<Int, Pair<Int, ByteArray>>(
            254 to (4 to le(4) { putInt(0) }),
            256 to (4 to le(4) { putInt(width) }),
            257 to (4 to le(4) { putInt(height) }),
            258 to (3 to le(2) { putShort(16.toShort()) }),
            259 to (3 to le(2) { putShort(1.toShort()) }),
            262 to (3 to le(2) { putShort(32803.toShort()) }),
            273 to (4 to ByteArray(4)),
            279 to (4 to le(4) { putInt(width * height * 2) }),
            33422 to (1 to byteArrayOf(1, 2, 0, 1)),
            50714 to (4 to le(16) { listOf(64, 65, 66, 67).forEach { putInt(it) } }),
            50717 to (4 to le(4) { putInt(1023) }),
            50728 to (5 to le(24) { listOf(500, 1000, 1000, 1000, 800, 1000).forEach { putInt(it) } }),
            50778 to (3 to le(2) { putShort(17.toShort()) }),
            50779 to (3 to le(2) { putShort(21.toShort()) }),
            50964 to (10 to rationals(List(9) { if (it % 4 == 0) 0.5f else 0f })),
            50965 to (10 to rationals(List(9) { if (it % 4 == 0) 1f else 0f })),
        )
        if (gainMaps) entries[51009] = 7 to opcodes()
        return tiff(entries, strip.array())
    }

    private fun le(size: Int, fill: ByteBuffer.() -> Unit) =
        ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN).apply(fill).array()

    private fun rationals(values: List<Float>) = le(values.size * 8) {
        values.forEach {
            putInt((it * 1000).toInt())
            putInt(1000)
        }
    }

    /** Four 2 x 3 GainMaps (rows x columns) for GBRG; each gain is 1 + 0.1 x (Bayer cell) + column + 10 x row. */
    private fun opcodes(): ByteArray {
        val out = ByteBuffer.allocate(4 + 4 * (16 + 76 + 6 * 4)).order(ByteOrder.BIG_ENDIAN)
        out.putInt(4)
        for (cell in 0 until 4) {
            out.putInt(9).putInt(0x01030000).putInt(0).putInt(76 + 6 * 4)
            out.putInt(cell / 2).putInt(cell % 2).putInt(10).putInt(12).putInt(0).putInt(1).putInt(2).putInt(2)
            out.putInt(2).putInt(3).putDouble(1.0).putDouble(0.5).putDouble(0.0).putDouble(0.0).putInt(1)
            for (r in 0 until 2) for (c in 0 until 3) out.putFloat(1f + 0.1f * cell + c + 10 * r)
        }
        return out.array()
    }

    private fun tiff(entries: Map<Int, Pair<Int, ByteArray>>, strip: ByteArray): ByteArray {
        val ifdSize = 2 + entries.size * 12 + 4
        var data = 8 + ifdSize
        val blobs = ByteArrayOutputStream()
        val ifd = ByteBuffer.allocate(ifdSize).order(ByteOrder.LITTLE_ENDIAN).putShort(entries.size.toShort())
        val stripAt = data + entries.values.sumOf { (_, v) -> if (v.size > 4) v.size else 0 }
        entries.forEach { (tag, typed) ->
            val (type, value) = typed
            val bytes = if (tag == 273) le(4) { putInt(stripAt) } else value
            val unit = mapOf(1 to 1, 3 to 2, 4 to 4, 5 to 8, 7 to 1, 10 to 8).getValue(type)
            ifd.putShort(tag.toShort()).putShort(type.toShort()).putInt(bytes.size / unit)
            if (bytes.size <= 4) {
                ifd.put(bytes.copyOf(4))
            } else {
                ifd.putInt(data)
                blobs.write(bytes)
                data += bytes.size
            }
        }
        ifd.putInt(0)
        val header = le(8) {
            put('I'.code.toByte()).put('I'.code.toByte()).putShort(42.toShort()).putInt(8)
        }
        return header + ifd.array() + blobs.toByteArray() + strip
    }

    @Test
    fun readsTheFrameAndItsColourMetadata() {
        val image = DngReader.read(dng(4, 2, gainMaps = false) { x, y -> 100 + 10 * y + x })
        val raw = image.raw
        assertEquals(CfaLayout.GBRG, raw.layout)
        assertEquals(4, raw.width)
        assertEquals(1023f, raw.whiteLevel, 0f)
        assertArrayEquals(floatArrayOf(64f, 65f, 66f, 67f), raw.blackLevels, 0f)
        // Sample at (3, 1) is 113; that cell position's black is 67.
        assertEquals((113f - 67f) / (1023f - 67f), raw.linear(3, 1), 1e-6f)
        assertArrayEquals(floatArrayOf(0.5f, 1f, 0.8f), image.asShotNeutral, 1e-6f)
        // The D65 forward matrix (illuminant 2 is 21), not the first one.
        assertEquals(1f, image.forwardMatrix!![0], 1e-6f)
        assertNull(image.shading)
    }

    @Test
    fun readsGainMapsIntoAShadingMap() {
        val shading = DngReader.read(dng(4, 2, gainMaps = true) { _, _ -> 500 }).shading
        assertNotNull(shading)
        assertEquals(3, shading!!.columns)
        assertEquals(2, shading.rows)
        // GBRG: cell 2 (row 1, column 0) is red, cell 1 blue, cell 0 green on even rows, cell 3 green on odd rows.
        val point = (1 * 3 + 2) * 4
        assertEquals(1.2f + 2 + 10, shading.gains[point], 1e-5f)
        assertEquals(1.0f + 2 + 10, shading.gains[point + 1], 1e-5f)
        assertEquals(1.3f + 2 + 10, shading.gains[point + 2], 1e-5f)
        assertEquals(1.1f + 2 + 10, shading.gains[point + 3], 1e-5f)
    }

    @Test
    fun forwardMatrixMakesSrgbWhiteFromD50White() {
        // A forward matrix that sends camera white (1, 1, 1) to D50 white must come out as sRGB white.
        val d50 = floatArrayOf(0.9642f, 1f, 0.8249f)
        val m = Colour.cameraToSrgb(FloatArray(9) { if (it % 4 == 0) d50[it / 4] else 0f })
        for (row in 0 until 3) assertEquals(1f, m[row * 3] + m[row * 3 + 1] + m[row * 3 + 2], 2e-3f)
    }

    @Test
    fun stackToolWritesEveryCandidate() {
        val scene = SyntheticScene(256, 192)
        val sharp = scene.render(256, 192)
        val blurred = scene.render(256, 192, blur = 2f)
        val files = listOf(blurred, sharp, blurred).mapIndexed { k, plane ->
            // Grey RAW at twice the size: every photosite of a 2 x 2 cell shows the same scene pixel. The scene runs
            // from about -45 to 195; mapped into 0..1 of the 10-bit range so no sample wraps past 16 bits.
            val bytes = dng(512, 384, gainMaps = true) { x, y ->
                64 + ((plane[x / 2, y / 2] + 50f) / 250f * 959).toInt().coerceIn(0, 959)
            }
            File(temp.root, "f$k.dng").apply { writeBytes(bytes) }.path
        }
        val out = temp.newFolder("out")
        main(arrayOf(out.path) + files)
        listOf("single-sharpest", "stack-A", "stack-B", "stack-C").forEach {
            val png = ImageIO.read(File(out, "$it.png"))
            assertEquals(it, 256, png.width)
            assertEquals(it, 192, png.height)
        }
        assertTrue(File(out, "stack-A.png").length() > 0)
    }
}
