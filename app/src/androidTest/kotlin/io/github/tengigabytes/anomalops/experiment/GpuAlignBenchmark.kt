// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.experiment

import android.opengl.GLES20
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.tengigabytes.anomalops.core.gpu.GlesContext
import io.github.tengigabytes.anomalops.core.gpu.GpuGlobalAligner
import io.github.tengigabytes.anomalops.core.gpu.GpuPlane
import io.github.tengigabytes.anomalops.core.gpu.GpuPyramid
import io.github.tengigabytes.anomalops.core.gpu.GpuTileAligner
import io.github.tengigabytes.anomalops.core.gpu.PlaneFormat
import io.github.tengigabytes.anomalops.core.gpu.PlaneKernels
import io.github.tengigabytes.anomalops.core.gpu.PlaneTransfer
import io.github.tengigabytes.anomalops.core.imaging.align.GlobalAligner
import io.github.tengigabytes.anomalops.core.imaging.align.Pyramid
import io.github.tengigabytes.anomalops.core.imaging.align.Similarity
import io.github.tengigabytes.anomalops.core.imaging.align.TileAligner
import io.github.tengigabytes.anomalops.core.imaging.align.TileField
import org.junit.Test
import org.junit.runner.RunWith

/**
 * ADR-0017 step 4: the alignment of one frame on the CPU against the GPU: whole-frame (`GlobalAligner`: coarse
 * search on the pyramid, then Gauss-Newton on level 0, against `GpuGlobalAligner`) and per tile (`TileAligner`
 * against `GpuTileAligner`), on [BenchScene] planes, with an activity in the
 * foreground. The reference pyramid is built once, as per burst; each whole-frame run builds the frame's pyramid
 * (on the GPU into the same planes) and aligns; the tile runs reuse that pyramid and the whole-frame result. Each step runs once to warm up, then
 * [RUNS] times; logs `ALIGNBENCH` lines under [TAG] with the median and the fastest. Time it as a non-debuggable
 * app, like [ImagingBenchmark].
 */
@RunWith(AndroidJUnit4::class)
class GpuAlignBenchmark {
    private val truth = Similarity(scale = 1.006f, dx = -13.3f, dy = 7.7f)
    private val reference = BenchScene.render(Similarity(), NOISE, noiseSeed = 2)
    private val frame = BenchScene.render(truth, NOISE, noiseSeed = 3)

    @Test
    fun globalAlignment() = ActivityScenario.launch(ComponentActivity::class.java).use {
        val refPyramid = Pyramid(reference)
        val cpu = measure("CPU frame pyramid and global alignment", gpu = false) {
            GlobalAligner().align(refPyramid, Pyramid(frame, maxLevels = refPyramid.top))
        }
        Log.i(TAG, "ALIGNBENCH truth $truth")
        Log.i(TAG, "ALIGNBENCH CPU result $cpu")
        val framePyramid = Pyramid(frame, maxLevels = refPyramid.top)
        val cpuTiles = measure("CPU tile alignment", gpu = false) { TileAligner().align(refPyramid, framePyramid, cpu) }
        Log.i(TAG, "ALIGNBENCH CPU tiles ${summary(cpuTiles)}")
        GlesContext.create().use {
            val transfer = PlaneTransfer()
            val kernels = PlaneKernels()
            val aligner = GpuGlobalAligner()
            val tileAligner = GpuTileAligner()
            val ref = transfer.upload(reference, PlaneFormat.FLOAT32)
            val gpuRefPyramid = GpuPyramid(ref, kernels)
            val fr = measure("GPU upload frame", gpu = true) { transfer.upload(frame, PlaneFormat.FLOAT32) }
            val frPyramid = GpuPyramid(fr, kernels, maxLevels = gpuRefPyramid.top)
            measure("GPU frame pyramid (reused planes)", gpu = true) { frPyramid.rebuild(fr) }
            val gpu = measure("GPU global alignment", gpu = true) { aligner.align(gpuRefPyramid, frPyramid) }
            Log.i(TAG, "ALIGNBENCH GPU result $gpu")
            val gpuTiles = measure("GPU tile alignment (texture measured in the warm-up)", gpu = true) {
                tileAligner.align(gpuRefPyramid, frPyramid, gpu)
            }
            Log.i(TAG, "ALIGNBENCH GPU tiles ${summary(gpuTiles)}")
            listOf(frPyramid, gpuRefPyramid, fr, ref, tileAligner, aligner, kernels, transfer).forEach { it.close() }
        }
    }

    /** Mean residual and trusted count of a tile field, to see that both sides found the same. */
    private fun summary(field: TileField): String = "mean residual (%.4f, %.4f), %d of %d trusted".format(
        field.dx.average(),
        field.dy.average(),
        field.trusted.count { it },
        field.trusted.size,
    )

    /** Warm-up, then [RUNS] timed runs (GPU after glFinish); GPU planes made by all but the last run are released. */
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
        Log.i(TAG, "ALIGNBENCH $label: median %.1f ms, fastest %.1f ms".format(times[RUNS / 2], times[0]))
        return result
    }

    private companion object {
        const val TAG = "GpuAlignBenchmark"
        const val RUNS = 5
        const val NOISE = 40f
        const val NS_PER_MS = 1e6
    }
}
