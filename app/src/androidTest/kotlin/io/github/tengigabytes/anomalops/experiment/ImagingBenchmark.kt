// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.experiment

import android.os.Debug
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.tengigabytes.anomalops.core.imaging.align.FrameAligner
import io.github.tengigabytes.anomalops.core.imaging.align.Plane
import io.github.tengigabytes.anomalops.core.imaging.develop.CfaLayout
import io.github.tengigabytes.anomalops.core.imaging.develop.Demosaic
import io.github.tengigabytes.anomalops.core.imaging.develop.RawFrame
import io.github.tengigabytes.anomalops.core.imaging.develop.Render
import io.github.tengigabytes.anomalops.core.imaging.develop.Rgb
import io.github.tengigabytes.anomalops.core.imaging.develop.ShadingMap
import io.github.tengigabytes.anomalops.core.imaging.merge.LowLightMerge
import io.github.tengigabytes.anomalops.core.imaging.stack.ContrastSelectStack
import io.github.tengigabytes.anomalops.core.imaging.stack.FocusStack
import io.github.tengigabytes.anomalops.core.imaging.stack.GuidedWeightStack
import io.github.tengigabytes.anomalops.core.imaging.stack.LaplacianPyramidStack
import io.github.tengigabytes.anomalops.core.imaging.stack.StackGuard
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.sin
import kotlin.random.Random

/**
 * `:core:imaging`'s CPU code timed on the phone with synthetic frames, no camera (FR-17: 12.5 MP x 5 frames in
 * <= 3 s; T12's proposed 6 frames in <= 10 s; T11's memory, without the camera's buffers). Frames are the half-size
 * planes of a 4080 x 3072 RAW (2040 x 1536), as `StackTool` uses them, luma only (see the test). Logs `BENCH` lines
 * under [TAG] with the time of each step and the Java heap and PSS after it; each step runs once after one warm-up
 * of the aligner.
 */
@RunWith(AndroidJUnit4::class)
class ImagingBenchmark {
    private val width = 2040
    private val height = 1536
    private val base = texture()

    // One step per test, so each starts with the previous one's planes collectable. Luma only: six frames of four
    // planes (R, G, B, luma) are 300 MB, past this process's 256 MB heap (2026-10-01, OutOfMemoryError).

    @Test
    fun a_demosaicAndAlign() {
        timed("demosaic halfSize 4080x3072") { Demosaic.halfSize(rawFrame()) }
        val luma = frames(3)
        timed("align warm-up") { FrameAligner(luma[0]).align(luma[1]) }
        val aligner = timed("aligner reference pyramid") { FrameAligner(luma[0]) }
        timed("align one frame") { aligner.align(luma[2]) }
    }

    @Test
    fun b_lowLightMerge() {
        val luma = frames(MERGE)
        timed("FR-17 low-light merge $MERGE frames, luma only") { LowLightMerge().merge(luma) }
    }

    @Test
    fun c_stackA() = stack("A", ContrastSelectStack())

    @Test
    fun d_stackB() = stack("B", LaplacianPyramidStack())

    @Test
    fun e_stackC() = stack("C", GuidedWeightStack())

    @Test
    fun f_render() {
        val luma = frames(3)
        val shading = ShadingMap(SHADING_COLS, SHADING_ROWS, FloatArray(4 * SHADING_COLS * SHADING_ROWS) { 1.5f })
        timed("render to ARGB with shading") {
            Render.toArgb(
                Rgb(luma[0], luma[1], luma[2]),
                floatArrayOf(1.5f, 1f, 2.2f),
                IDENTITY,
                shading = shading,
                rawWidth = 2 * width,
                rawHeight = 2 * height,
            )
        }
    }

    private fun stack(key: String, stack: FocusStack) {
        val luma = frames(STACK)
        timed("FR-33 stack $key $STACK frames, luma only") {
            StackGuard(stack).merge(luma, luma.map { listOf(it) })
        }
    }

    @Suppress("ExplicitGarbageCollectionCall") // So a step's time does not include collecting the last one's.
    private fun frames(count: Int): List<Plane> {
        System.gc()
        return List(count) { k -> frame(dx = 1.3f * k, dy = -0.7f * k) }.also { memory("${it.size} frames") }
    }

    /** A smooth random texture, twice the frame size so shifted frames stay inside it. */
    private fun texture(): Plane {
        val r = Random(1)
        val coarse = Plane(COARSE_W, COARSE_H, FloatArray(COARSE_W * COARSE_H) { r.nextFloat() })
        val w = width + 2 * PAD
        val h = height + 2 * PAD
        val data = FloatArray(w * h) { i ->
            val x = i % w
            val y = i / w
            coarse.sample(x * (COARSE_W - 1f) / w, y * (COARSE_H - 1f) / h) + 0.05f * sin(x * 0.7f) * sin(y * 0.9f)
        }
        return Plane(w, h, data)
    }

    /** One luma frame: the texture shifted by ([dx], [dy]) with a little noise. */
    private fun frame(dx: Float, dy: Float): Plane {
        val r = Random((dx * 100).toInt())
        val data = FloatArray(width * height) { i ->
            base.sample(i % width + PAD + dx, i / width + PAD + dy) + 0.01f * (r.nextFloat() - 0.5f)
        }
        return Plane(width, height, data)
    }

    private fun rawFrame(): RawFrame {
        val w = 2 * width
        val h = 2 * height
        val samples = ShortArray(w * h) { i -> (BLACK + (i * 2654435761L % 900).toInt()).toShort() }
        return RawFrame(samples, w, h, w, CfaLayout.GBRG, FloatArray(4) { BLACK.toFloat() }, WHITE)
    }

    private fun <T> timed(label: String, block: () -> T): T {
        val gcCount = gcStat("art.gc.gc-count")
        val gcTime = gcStat("art.gc.gc-time")
        val t0 = System.nanoTime()
        val out = block()
        val ms = (System.nanoTime() - t0) / NS_PER_MS
        val gc = "GC ${gcStat("art.gc.gc-count") - gcCount} runs, ${gcStat("art.gc.gc-time") - gcTime} ms"
        Log.i(TAG, "BENCH $label: $ms ms ($gc)")
        memory("after $label")
        return out
    }

    private fun gcStat(name: String): Long = Debug.getRuntimeStat(name)?.toLongOrNull() ?: 0L

    private fun memory(label: String) {
        val runtime = Runtime.getRuntime()
        val usedMb = (runtime.totalMemory() - runtime.freeMemory()) / MB
        Log.i(TAG, "BENCH memory $label: heap $usedMb of ${runtime.maxMemory() / MB} MB, PSS ${Debug.getPss() / KB} MB")
    }

    private companion object {
        const val TAG = "ImagingBenchmark"
        const val STACK = 6
        const val MERGE = 5
        const val PAD = 16
        const val COARSE_W = 400
        const val COARSE_H = 300
        const val SHADING_COLS = 33
        const val SHADING_ROWS = 25
        const val BLACK = 64
        const val WHITE = 1023f
        const val NS_PER_MS = 1_000_000
        const val MB = 1024 * 1024
        const val KB = 1024
        val IDENTITY = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
    }
}
