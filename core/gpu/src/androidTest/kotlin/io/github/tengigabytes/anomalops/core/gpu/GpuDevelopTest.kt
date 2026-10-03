// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.core.gpu

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.tengigabytes.anomalops.core.imaging.align.Similarity
import io.github.tengigabytes.anomalops.core.imaging.develop.CfaLayout
import io.github.tengigabytes.anomalops.core.imaging.develop.ChromaDenoise
import io.github.tengigabytes.anomalops.core.imaging.develop.Demosaic
import io.github.tengigabytes.anomalops.core.imaging.develop.RawFrame
import io.github.tengigabytes.anomalops.core.imaging.develop.Render
import io.github.tengigabytes.anomalops.core.imaging.develop.RenderOptions
import io.github.tengigabytes.anomalops.core.imaging.develop.ShadingMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * ADR-0017 step 4 on the phone: [GpuDevelop] against `Demosaic.halfSize`, `Rgb.luma` and `Render.toArgb`, on a
 * synthetic GBRG RAW (4080 x 3072, padded rows, a black level per cell position, some photosites clipped) and a
 * shading map like the phone's 33 x 25. Decoding must match bit for bit; rendering may differ where the GPU's
 * division rounds differently (proposed limit); `Render.finish` of that rendering must match bit for bit again.
 * Results are logged under [TAG] as `GPU` lines.
 */
@RunWith(AndroidJUnit4::class)
class GpuDevelopTest {
    private val raw = RawScene.frame(Similarity(), noiseSeed = 2)
    private val gains = floatArrayOf(2.0f, 1.0f, 1.6f)
    private val matrix = floatArrayOf(1.6f, -0.4f, -0.2f, -0.2f, 1.5f, -0.3f, 0.0f, -0.5f, 1.5f)
    private val options = RenderOptions(exposure = 1.5f)
    private val shading = shadingMap()

    @Test
    fun developMatchesTheCpu() {
        val cpu = Demosaic.halfSize(raw)
        val cpuPlanes = cpu.channels() + cpu.luma()
        val cpuArgb = Render.toArgb(cpu, gains, matrix, options, shading, raw.width, raw.height)
        val (gpuPlanes, gpuArgb) = GlesContext.create().use {
            PlaneTransfer().use { transfer ->
                GpuDevelop().use { develop ->
                    val planes = develop.halfSize(raw)
                    val argb = develop.toArgb(planes, gains, matrix, options, shading, raw.width, raw.height)
                    val downloaded = planes.map { transfer.download(it) }
                    planes.forEach { it.close() }
                    downloaded to argb
                }
            }
        }
        val decodeMismatches = listOf("red", "green", "blue", "luma").mapIndexed { i, name ->
            val a = cpuPlanes[i].data
            val b = gpuPlanes[i].data
            val mismatches = a.indices.count { a[it] != b[it] }
            val maxRel = a.indices.maxOf { abs(a[it] - b[it]) / max(abs(a[it]), Float.MIN_VALUE) }
            log("decode $name: $mismatches values differ from the CPU, max relative difference $maxRel")
            mismatches
        }
        assertEquals("decode mismatches $decodeMismatches", 0, decodeMismatches.sum())
        var differ = 0
        var worst = 0
        for (i in cpuArgb.indices) {
            if (cpuArgb[i] == gpuArgb[i]) continue
            differ++
            for (shift in intArrayOf(RED_SHIFT, GREEN_SHIFT, 0)) {
                worst = max(worst, abs((cpuArgb[i] shr shift and CODE) - (gpuArgb[i] shr shift and CODE)))
            }
        }
        log("render: $differ of ${cpuArgb.size} pixels differ, largest code difference $worst")
        assertTrue("render: $differ pixels, $worst", differ <= cpuArgb.size * MAX_DIFFER_SHARE && worst <= 1)
    }

    /** The whole-picture kernels are integer arithmetic: on the GPU's own rendering they must give the CPU's codes. */
    @Test
    fun finishMatchesTheCpu() {
        val cases = listOf(
            "sharpen" to options.copy(sharpen = SHARPEN),
            "chroma" to options.copy(chromaPasses = ChromaDenoise.MAX_PASSES),
            "chroma and sharpen" to options.copy(chromaPasses = 2, sharpen = SHARPEN),
            "saturation" to options.copy(saturation = SATURATION),
            "chroma, tight tolerance" to options.copy(chromaPasses = 3, chromaTolerance = TIGHT_TOLERANCE),
            "all three" to options.copy(chromaPasses = 3, saturation = SATURATION, sharpen = SHARPEN),
        )
        val (plain, finished) = GlesContext.create().use {
            GpuDevelop().use { develop ->
                val planes = develop.halfSize(raw)
                val a = develop.toArgb(planes, gains, matrix, options, shading, raw.width, raw.height)
                val b = cases.map { develop.toArgb(planes, gains, matrix, it.second, shading, raw.width, raw.height) }
                planes.forEach { it.close() }
                a to b
            }
        }
        cases.forEachIndexed { k, (name, case) ->
            val expected = Render.finish(plain, raw.width / 2, raw.height / 2, case)
            val differ = expected.indices.count { expected[it] != finished[k][it] }
            val changed = plain.indices.count { plain[it] != finished[k][it] }
            log("$name: $differ of ${expected.size} pixels differ from the CPU; $changed changed by it")
            assertEquals("$name mismatches", 0, differ)
            // The scene is mostly smooth and grey; no change at all would mean the kernel did not run.
            assertTrue("$name changed $changed pixels", changed > expected.size / MIN_CHANGED_SHARE)
        }
    }

    /** Gains 1 in the centre rising towards the corners, a little differently per channel, as a lens has them. */
    private fun shadingMap(): ShadingMap {
        val gains = FloatArray(SHADING_COLUMNS * SHADING_ROWS * 4)
        for (y in 0 until SHADING_ROWS) {
            for (x in 0 until SHADING_COLUMNS) {
                val dx = x / (SHADING_COLUMNS - 1f) - 0.5f
                val dy = y / (SHADING_ROWS - 1f) - 0.5f
                val r2 = (dx * dx + dy * dy) * 2
                for (c in 0 until 4) gains[(y * SHADING_COLUMNS + x) * 4 + c] = 1f + r2 * (1.2f + 0.2f * c)
            }
        }
        return ShadingMap(SHADING_COLUMNS, SHADING_ROWS, gains)
    }

    private fun log(text: String) {
        Log.i(TAG, "GPU $text")
    }

    private companion object {
        const val TAG = "GpuDevelopTest"
        const val SHADING_COLUMNS = 33
        const val SHADING_ROWS = 25
        const val RED_SHIFT = 16
        const val GREEN_SHIFT = 8
        const val CODE = 0xFF

        /** Proposed: at most 0.1 % of the pixels one code apart. */
        const val MAX_DIFFER_SHARE = 0.001
        const val SHARPEN = 0.5f
        const val SATURATION = 1.25f
        const val TIGHT_TOLERANCE = 4f
        const val MIN_CHANGED_SHARE = 100
    }
}

/**
 * Synthetic RAW_SENSOR frames of [AlignScene]: GBRG, 4080 x 3072 with rows padded to [ROW_STRIDE] samples, black
 * levels per cell position, white 4095, colour gains per photosite colour so the planes differ, and the brightest
 * part clipped at white.
 */
internal object RawScene {
    const val ROW_STRIDE = 4096
    private val black = floatArrayOf(64f, 66f, 63f, 65f)
    private const val WHITE = 4095f
    private val colourGains = floatArrayOf(0.55f, 1.0f, 0.7f)
    private const val BRIGHTNESS = 1.15f

    /** The scene seen through [view] at half size, each photosite from its 2 x 2 cell's value plus noise. */
    fun frame(view: Similarity, noiseSeed: Int, noise: Float = 20f): RawFrame {
        val plane = AlignScene.render(view, noise / sqrt(2f), noiseSeed)
        val width = 2 * plane.width
        val height = 2 * plane.height
        val layout = CfaLayout.GBRG
        val samples = ShortArray(ROW_STRIDE * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val k = (y and 1) * 2 + (x and 1)
                val scene = plane[x / 2, y / 2] * BRIGHTNESS * colourGains[layout.colourAt(x, y)]
                val v = (black[k] + scene * (WHITE - black[k]) / WHITE).coerceIn(0f, WHITE)
                samples[y * ROW_STRIDE + x] = v.toInt().toShort()
            }
        }
        return RawFrame(samples, width, height, ROW_STRIDE, layout, black.copyOf(), WHITE)
    }
}
