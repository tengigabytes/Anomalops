// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.experiment

import android.os.Debug
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.tengigabytes.anomalops.core.imaging.align.FrameAligner
import io.github.tengigabytes.anomalops.core.imaging.align.GlobalAligner
import io.github.tengigabytes.anomalops.core.imaging.align.Plane
import io.github.tengigabytes.anomalops.core.imaging.align.Pyramid
import io.github.tengigabytes.anomalops.core.imaging.align.TileAligner
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
 * of the aligner. Time it as a non-debuggable app: a debuggable one ran the aligner about ten times slower
 * (docs/test/m9-imaging-phone.md). `-e frames N` sets the focus-stack frames, `-e mergeFrames N` FR-17's.
 * FR-17 and candidates A and B are fed one frame at a time (ADR-0017; A in two passes): each frame is made just
 * before it is added and dropped after, as camera frames would arrive, and only the time spent adding is counted.
 */
@RunWith(AndroidJUnit4::class)
class ImagingBenchmark {
    private val width = 2040
    private val height = 1536
    private val base = texture()

    /** `-e frames N`: frames per focus stack (default 6; A and C keep every frame and do not fit the heap). */
    private val stackFrames = InstrumentationRegistry.getArguments().getString("frames")?.toIntOrNull() ?: STACK

    /** `-e mergeFrames N`: frames per FR-17 burst (default 5). */
    private val mergeFrames = InstrumentationRegistry.getArguments().getString("mergeFrames")?.toIntOrNull() ?: MERGE

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
        val reference = frames(1).single()
        streamed("FR-17 low-light merge $mergeFrames frames, luma only, per frame") { add ->
            lateinit var merge: LowLightMerge.Accumulator
            add { merge = LowLightMerge().start(reference) }
            for (k in 1 until mergeFrames) {
                val frame = frameAt(k)
                add { merge.add(frame, listOf(frame)) }
            }
            add { merge.finish() }
        }
    }

    @Test
    fun c_stackA() = streamedStack("A", ContrastSelectStack())

    @Test
    fun d_stackB() = streamedStack("B", LaplacianPyramidStack())

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

    /** The aligner's parts, to find where the phone spends its time. */
    @Test
    fun h_alignParts() {
        val luma = frames(2)
        val reference = Pyramid(luma[0])
        val frame = timed("pyramid") { Pyramid(luma[1], maxLevels = reference.top) }
        repeat(2) { round ->
            val global = timed("global aligner round $round") { GlobalAligner().align(reference, frame) }
            timed("tile aligner round $round") { TileAligner().align(reference, frame, global) }
        }
        timed("sample 3 M bilinear") {
            var sum = 0f
            for (y in 0 until height step 1) for (x in 0 until width step 1) sum += luma[1].sample(x + 0.3f, y + 0.6f)
            sum
        }
    }

    /** A plain loop of 50 M multiply-adds: tells whether this process runs compiled code at all. */
    @Test
    fun g_plainLoop() {
        val data = FloatArray(LOOP_SIZE) { it * 1e-6f }
        repeat(LOOP_ROUNDS) { round ->
            timed("plain loop round $round") {
                var sum = 0f
                repeat(LOOP_PASSES) { for (i in data.indices) sum += data[i] * 1.0001f }
                sum
            }
        }
    }

    /** Every frame made again for each pass, as a second pass would read the RAW buffer or the DNGs again. */
    private fun streamedStack(key: String, stack: FocusStack) {
        val reference = frames(1).single()
        streamed("FR-33 stack $key $stackFrames frames, luma only, per frame") { add ->
            lateinit var session: StackGuard.Session
            add { session = StackGuard(stack).start(reference, listOf(reference)) }
            repeat(session.passes) { pass ->
                add { session.add(reference, listOf(reference)) }
                for (k in 1 until stackFrames) {
                    val frame = frameAt(k)
                    add { session.add(frame, listOf(frame)) }
                }
                if (pass < session.passes - 1) add { session.endPass() }
            }
            add { session.finish() }
        }
    }

    private fun stack(key: String, stack: FocusStack) {
        val luma = frames(stackFrames)
        timed("FR-33 stack $key $stackFrames frames, luma only") {
            StackGuard(stack).merge(luma, luma.map { listOf(it) })
        }
    }

    @Suppress("ExplicitGarbageCollectionCall") // So a step's time does not include collecting the last one's.
    private fun frames(count: Int): List<Plane> {
        System.gc()
        return List(count) { frameAt(it) }.also { memory("${it.size} frames") }
    }

    private fun frameAt(k: Int) = frame(dx = 1.3f * k, dy = -0.7f * k)

    /**
     * Runs [body], which wraps each per-frame step in the function it is given; logs the sum of those steps' times
     * (frame making excluded) with the collector's work during them, the largest live heap after a step (an
     * explicit collection after each step, outside the timing: what the merge holds, not its garbage), and the
     * memory at the end.
     */
    @Suppress("ExplicitGarbageCollectionCall")
    private fun streamed(label: String, body: (add: (() -> Unit) -> Unit) -> Unit) {
        var ns = 0L
        var gcRuns = 0L
        var gcMs = 0L
        var peak = 0L
        val runtime = Runtime.getRuntime()
        body { step ->
            val runs = gcStat("art.gc.gc-count")
            val time = gcStat("art.gc.gc-time")
            val t0 = System.nanoTime()
            step()
            ns += System.nanoTime() - t0
            gcRuns += gcStat("art.gc.gc-count") - runs
            gcMs += gcStat("art.gc.gc-time") - time
            System.gc()
            peak = maxOf(peak, runtime.totalMemory() - runtime.freeMemory())
        }
        Log.i(TAG, "BENCH $label: ${ns / NS_PER_MS} ms (GC $gcRuns runs, $gcMs ms), peak live heap ${peak / MB} MB")
        memory("after $label")
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
        const val LOOP_SIZE = 1_000_000
        const val LOOP_PASSES = 50
        const val LOOP_ROUNDS = 3
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
