// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.experiment

import android.opengl.GLES20
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.tengigabytes.anomalops.core.gpu.GlesContext
import io.github.tengigabytes.anomalops.core.gpu.GpuPlane
import io.github.tengigabytes.anomalops.core.gpu.MeanSquaredDiffKernel
import io.github.tengigabytes.anomalops.core.gpu.PlaneFormat
import io.github.tengigabytes.anomalops.core.gpu.PlaneKernels
import io.github.tengigabytes.anomalops.core.gpu.PlaneTransfer
import io.github.tengigabytes.anomalops.core.imaging.align.FrameAligner
import io.github.tengigabytes.anomalops.core.imaging.align.Plane
import io.github.tengigabytes.anomalops.core.imaging.align.Region
import io.github.tengigabytes.anomalops.core.imaging.align.Similarity
import io.github.tengigabytes.anomalops.core.imaging.align.meanSquaredDiff
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.sin
import kotlin.random.Random

/**
 * ADR-0017 step 3: the first GPU kernels of `:core:gpu` against their `:core:imaging` CPU functions, timed in one
 * process on 2040 x 1536 planes (the half-size plane of a 12.5 MP RAW). Each step runs once to warm up (shader
 * compile, first allocation), then [RUNS] times; logs `GPUBENCH` lines under [TAG] with the median and the
 * fastest, GPU times after glFinish. Time it as a non-debuggable app, like [ImagingBenchmark]. `-e foreground true`
 * keeps an activity of this process resumed while the GPU runs, as the camera screen would be (the GPU clock
 * itself is measured by [GpuClockExperiment]).
 */
@RunWith(AndroidJUnit4::class)
class GpuBenchmark {
    private val width = 2040
    private val height = 1536

    private val plane = texture(dx = 0f, dy = 0f)
    private val global = Similarity(scale = 1.004f, dx = 3.3f, dy = -2.7f)
    private val refLevel = plane.half().half()
    private val frameLevel = texture(dx = 1.3f, dy = -0.7f).half().half()
    private val region = Region.inner(refLevel.width, refLevel.height, MARGIN)
    private val candidates = (-SEARCH..SEARCH).flatMap { j ->
        (-SEARCH..SEARCH).map {
            Similarity(
                1f,
                it * 1f,
                j * 1f,
            )
        }
    }
    private val full = width to height

    /** GlobalAligner's search around the best shift on level 0: 3 scales x 3 x 3 shifts, every second pixel. */
    private val frame0 = texture(dx = 1.3f, dy = -0.7f)
    private val region0 = Region.inner(width, height, MARGIN)
    private val around = (-1..1).flatMap { s ->
        (-1..1).flatMap { j ->
            (-1..1).map {
                Similarity(
                    1f + s * 0.0025f,
                    1f + it,
                    j - 1f,
                )
            }
        }
    }

    @Test
    fun cpuKernels() {
        val aligner = FrameAligner(plane)
        cpu("half") { plane.half() }
        cpu("warp") { aligner.warp(plane, global) }
        cpu("meanSquaredDiff ${candidates.size} candidates, level $LEVEL") {
            candidates.map { meanSquaredDiff(refLevel, frameLevel, it.atLevel(LEVEL), region, 1, full) }
        }
        cpu("meanSquaredDiff ${around.size} candidates, level 0, step $STEP0") {
            around.map { meanSquaredDiff(plane, frame0, it.atLevel(0), region0, STEP0, full) }
        }
    }

    private val foreground = InstrumentationRegistry.getArguments().getString("foreground") == "true"

    @Test
    fun gpuKernels() {
        if (foreground) ActivityScenario.launch(ComponentActivity::class.java).use { runGpu() } else runGpu()
    }

    /** GlobalAligner's level-0 search repeated for a few seconds, to see the GPU's sustained speed and clock. */
    @Test
    fun sustainedMeanSquaredDiff() {
        val body = {
            GlesContext.create().use {
                PlaneTransfer().use { transfer ->
                    MeanSquaredDiffKernel().use { mse ->
                        val ref = transfer.upload(plane, PlaneFormat.FLOAT32)
                        val frame = transfer.upload(frame0, PlaneFormat.FLOAT32)
                        repeat(SUSTAINED_ROUNDS) { round ->
                            gpu("sustained round $round, ${around.size} candidates, level 0") {
                                mse.evaluate(ref, frame, around, 0, region0, STEP0, full)
                            }
                        }
                        ref.close()
                        frame.close()
                    }
                }
            }
        }
        if (foreground) ActivityScenario.launch(ComponentActivity::class.java).use { body() } else body()
    }

    private fun runGpu() {
        GlesContext.create().use {
            val transfer = PlaneTransfer()
            val kernels = PlaneKernels()
            val mse = MeanSquaredDiffKernel()
            PlaneFormat.entries.forEach { format -> gpuSteps(format, transfer, kernels, mse) }
            listOf(transfer, kernels, mse).forEach { it.close() }
        }
    }

    private fun gpuSteps(
        format: PlaneFormat,
        transfer: PlaneTransfer,
        kernels: PlaneKernels,
        mse: MeanSquaredDiffKernel,
    ) {
        val input = gpu("upload $format") { transfer.upload(plane, format) }
        gpu("download $format") { transfer.download(input) }
        gpu("half $format") { kernels.half(input) }.close()
        gpu("warp $format") { kernels.warp(input, global) }.close()
        if (format == PlaneFormat.HALF) gpu("warp filtered HALF") { kernels.warpFiltered(input, global) }.close()
        val ref = transfer.upload(refLevel, format)
        val frame = transfer.upload(frameLevel, format)
        gpu("meanSquaredDiff ${candidates.size} candidates, level $LEVEL, $format") {
            mse.evaluate(ref, frame, candidates, LEVEL, region, 1, full)
        }
        val frameGpu = transfer.upload(frame0, format)
        gpu("meanSquaredDiff ${around.size} candidates, level 0, step $STEP0, $format") {
            mse.evaluate(input, frameGpu, around, 0, region0, STEP0, full)
        }
        listOf(input, ref, frame, frameGpu).forEach { it.close() }
    }

    private fun <T> cpu(label: String, block: () -> T) = measure("CPU $label", gpu = false, block)

    private fun <T> gpu(label: String, block: () -> T) = measure("GPU $label", gpu = true, block)

    /** Warm-up, then [RUNS] timed runs; GPU planes made by all but the last run are released. */
    private fun <T> measure(label: String, gpu: Boolean, block: () -> T): T {
        var result = block()
        val times = DoubleArray(RUNS) {
            (result as? GpuPlane)?.close()
            if (gpu) GLES20.glFinish()
            val t0 = System.nanoTime()
            result = block()
            if (gpu) GLES20.glFinish()
            (System.nanoTime() - t0) / NS_PER_MS
        }
        times.sort()
        Log.i(TAG, "GPUBENCH $label: median %.1f ms, fastest %.1f ms".format(times[RUNS / 2], times[0]))
        return result
    }

    /** The same smooth random texture as the kernel tests, in the 12-bit RAW range. */
    private fun texture(dx: Float, dy: Float): Plane {
        val r = Random(1)
        val coarse = Plane(COARSE_W, COARSE_H, FloatArray(COARSE_W * COARSE_H) { r.nextFloat() })
        return Plane(
            width,
            height,
            FloatArray(width * height) { i ->
                val x = i % width + dx
                val y = i / width + dy
                val base = coarse.sample(x * (COARSE_W - 1f) / width, y * (COARSE_H - 1f) / height)
                RANGE * (0.9f * (if (base.isNaN()) 0f else base) + 0.05f * (1 + sin(x * 0.7f) * sin(y * 0.9f)))
            },
        )
    }

    private companion object {
        const val TAG = "GpuBenchmark"
        const val RUNS = 5
        const val MARGIN = 0.1f
        const val SEARCH = 4
        const val LEVEL = 2
        const val STEP0 = 2
        const val SUSTAINED_ROUNDS = 8
        const val COARSE_W = 400
        const val COARSE_H = 300
        const val RANGE = 4000f
        const val NS_PER_MS = 1e6
    }
}
