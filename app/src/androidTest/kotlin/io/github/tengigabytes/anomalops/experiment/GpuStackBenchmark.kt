// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.experiment

import android.opengl.GLES20
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.tengigabytes.anomalops.core.gpu.GlesContext
import io.github.tengigabytes.anomalops.core.gpu.GpuContrastSelect
import io.github.tengigabytes.anomalops.core.gpu.GpuFocusAccumulator
import io.github.tengigabytes.anomalops.core.gpu.GpuLaplacianPyramid
import io.github.tengigabytes.anomalops.core.gpu.GpuPlane
import io.github.tengigabytes.anomalops.core.gpu.PlaneFormat
import io.github.tengigabytes.anomalops.core.gpu.PlaneTransfer
import io.github.tengigabytes.anomalops.core.imaging.align.Plane
import io.github.tengigabytes.anomalops.core.imaging.align.Similarity
import org.junit.Test
import org.junit.runner.RunWith

/**
 * ADR-0017 step 5: FR-33's candidates A and B on the GPU ([GpuContrastSelect], [GpuLaplacianPyramid]) for a
 * bracket of [FRAMES] [BenchScene] frames (2040 x 1536, the half-size planes of 12.5 MP RAWs) with the luma and
 * three colour planes (derived from the luma). Each frame is uploaded into the same four planes and added, A in its
 * two passes (each pass uploads every frame again, as a second read of the RAW buffer); no alignment and no
 * `StackGuard`. With an activity in the foreground; each candidate runs [RUNS] times, the first to warm up. Logs
 * `STACKBENCH` lines under [TAG]. Time it as a non-debuggable app, like [ImagingBenchmark].
 */
@RunWith(AndroidJUnit4::class)
class GpuStackBenchmark {
    private val lumas = List(FRAMES) { k -> BenchScene.render(Similarity(), NOISE, noiseSeed = 2 + k) }

    @Test
    fun candidates() = ActivityScenario.launch(ComponentActivity::class.java).use {
        GlesContext.create().use {
            val transfer = PlaneTransfer()
            val planes = List(PLANES) { GpuPlane(BenchScene.WIDTH, BenchScene.HEIGHT, PlaneFormat.FLOAT32) }
            val w = BenchScene.WIDTH
            val h = BenchScene.HEIGHT
            GpuContrastSelect().use { a -> repeat(RUNS) { run("A", it, a.start(w, h, 3), transfer, planes) } }
            GpuLaplacianPyramid().use { b -> repeat(RUNS) { run("B", it, b.start(w, h, 3), transfer, planes) } }
            planes.forEach { it.close() }
            transfer.close()
        }
    }

    private fun run(
        name: String,
        index: Int,
        accumulator: GpuFocusAccumulator,
        transfer: PlaneTransfer,
        planes: List<GpuPlane>,
    ) {
        var upload = 0.0
        var add = 0.0
        var total = 0.0
        accumulator.use {
            repeat(it.passes) { pass ->
                for (k in 0 until FRAMES) {
                    val colours = COLOUR_GAINS.map { g ->
                        Plane(
                            lumas[k].width,
                            lumas[k].height,
                            FloatArray(lumas[k].data.size) { i -> g * lumas[k].data[i] },
                        )
                    }
                    val frame = listOf(lumas[k]) + colours
                    upload += timed {
                        frame.forEachIndexed { i, p -> transfer.upload(p, PlaneFormat.FLOAT32, into = planes[i]) }
                    }
                    add += timed { it.add(planes[0], planes.drop(1)) }
                }
                if (pass < it.passes - 1) total += timed { it.endPass() }
            }
            val finish = timed { it.finish().forEach { p -> p.close() } }
            total += upload + add + finish
            val times = "upload %.1f ms, add %.1f ms, finish %.1f ms, total %.1f ms".format(upload, add, finish, total)
            Log.i(TAG, "STACKBENCH $name run $index ($FRAMES frames, ${it.passes} passes): $times")
        }
    }

    private fun timed(block: () -> Unit): Double {
        GLES20.glFinish()
        val t0 = System.nanoTime()
        block()
        GLES20.glFinish()
        return (System.nanoTime() - t0) / NS_PER_MS
    }

    private companion object {
        const val TAG = "GpuStackBenchmark"
        const val FRAMES = 6
        const val RUNS = 3
        const val PLANES = 4
        const val NOISE = 40f
        const val NS_PER_MS = 1e6
        val COLOUR_GAINS = floatArrayOf(0.5f, 1f, 0.3f)
    }
}
