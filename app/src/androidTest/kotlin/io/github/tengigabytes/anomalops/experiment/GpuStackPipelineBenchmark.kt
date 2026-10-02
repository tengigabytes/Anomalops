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
import io.github.tengigabytes.anomalops.core.gpu.GpuDevelop
import io.github.tengigabytes.anomalops.core.gpu.GpuGlobalAligner
import io.github.tengigabytes.anomalops.core.gpu.GpuLaplacianPyramid
import io.github.tengigabytes.anomalops.core.gpu.GpuPlane
import io.github.tengigabytes.anomalops.core.gpu.GpuPyramid
import io.github.tengigabytes.anomalops.core.gpu.GpuStackGuard
import io.github.tengigabytes.anomalops.core.gpu.PlaneFormat
import io.github.tengigabytes.anomalops.core.gpu.PlaneKernels
import io.github.tengigabytes.anomalops.core.imaging.align.Similarity
import org.junit.Test
import org.junit.runner.RunWith

/**
 * ADR-0017 step 5: FR-33 from RAW to sRGB on the GPU, as `StackTool` runs it on the CPU: [FRAMES] 12.5 MP RAWs
 * ([BenchScene.raw], small scale changes like focus breathing) decoded to half size, each aligned to the middle
 * frame (whole frame only), its four planes resampled, then [GpuStackGuard] around candidate A (two passes: the
 * second decodes and resamples every frame again with the first pass's transforms) or B, then the result rendered
 * to ARGB and read back. The RAWs are made before timing. With an activity in the foreground; each candidate runs
 * [RUNS] times, the first to warm up. Logs `FR33BENCH` lines under [TAG]. Time it as a non-debuggable app.
 */
@RunWith(AndroidJUnit4::class)
class GpuStackPipelineBenchmark {
    private val views = List(
        FRAMES,
    ) { k -> Similarity(scale = 1f + (k - REFERENCE) * BREATHING, dx = 0.7f * k, dy = -0.4f * k) }
    private val raws = views.mapIndexed { k, view -> BenchScene.raw(view, NOISE, noiseSeed = 2 + k) }
    private val gains = floatArrayOf(2.0f, 1.0f, 1.6f)
    private val matrix = floatArrayOf(1.6f, -0.4f, -0.2f, -0.2f, 1.5f, -0.3f, 0.0f, -0.5f, 1.5f)

    @Test
    fun rawToArgb() = ActivityScenario.launch(ComponentActivity::class.java).use {
        GlesContext.create().use {
            val tools = Tools()
            GpuContrastSelect().use { a ->
                GpuStackGuard { w, h, c ->
                    a.start(
                        w,
                        h,
                        c,
                    )
                }.use { guard -> repeat(RUNS) { burst("A", it, tools, guard) } }
            }
            GpuLaplacianPyramid().use { b ->
                GpuStackGuard { w, h, c ->
                    b.start(
                        w,
                        h,
                        c,
                    )
                }.use { guard -> repeat(RUNS) { burst("B", it, tools, guard) } }
            }
            tools.close()
        }
    }

    /** What every burst reuses: the kernels and the planes for the reference, a decoded frame and its resampling. */
    private class Tools : AutoCloseable {
        val develop = GpuDevelop()
        val kernels = PlaneKernels()
        val aligner = GpuGlobalAligner()
        private fun planes() = List(PLANES) { GpuPlane(BenchScene.WIDTH, BenchScene.HEIGHT, PlaneFormat.FLOAT32) }
        val reference = planes()
        val decoded = planes()
        val warped = planes()

        override fun close() {
            (reference + decoded + warped).forEach { it.close() }
            listOf(develop, kernels, aligner).forEach { it.close() }
        }
    }

    private fun burst(name: String, index: Int, t: Tools, guard: GpuStackGuard) {
        GLES20.glFinish()
        val t0 = System.nanoTime()
        t.develop.halfSize(raws[REFERENCE], into = t.reference)
        val refPyramid = GpuPyramid(t.reference[LUMA], t.kernels)
        val framePyramid = GpuPyramid(t.decoded[LUMA], t.kernels, maxLevels = refPyramid.top)
        val globals = arrayOfNulls<Similarity>(FRAMES)
        val session = guard.start(t.reference[LUMA], t.reference.take(LUMA))
        repeat(session.passes) { pass ->
            for (k in 0 until FRAMES) {
                if (k == REFERENCE) {
                    session.add(t.reference[LUMA], t.reference.take(LUMA))
                    continue
                }
                t.develop.halfSize(raws[k], into = t.decoded)
                val global = globals[k] ?: run {
                    framePyramid.rebuild(t.decoded[LUMA])
                    t.aligner.align(refPyramid, framePyramid).also { globals[k] = it }
                }
                t.decoded.forEachIndexed { i, p -> t.kernels.warp(p, global, into = t.warped[i]) }
                session.add(t.warped[LUMA], t.warped.take(LUMA))
            }
            if (pass < session.passes - 1) session.endPass()
        }
        val result = session.finish()
        val argb = t.develop.toArgb(
            result.channels,
            gains,
            matrix,
            rawWidth = raws[0].width,
            rawHeight = raws[0].height,
        )
        GLES20.glFinish()
        val ms = (System.nanoTime() - t0) / NS_PER_MS
        result.channels.forEach { it.close() }
        listOf(framePyramid, refPyramid).forEach { it.close() }
        val verdict = if (result.merged) "kept" else "fell back to frame ${result.bestFrame}"
        val time = "%.1f ms".format(ms)
        val what = "$name run $index ($FRAMES RAWs, ${session.passes} passes)"
        Log.i(TAG, "FR33BENCH $what: $time, $verdict, ${argb.size} px")
    }

    private companion object {
        const val TAG = "GpuStackPipeline"
        const val FRAMES = 6
        const val REFERENCE = 2
        const val RUNS = 3
        const val PLANES = 4
        const val LUMA = 3
        const val NOISE = 40f
        const val NS_PER_MS = 1e6
        const val BREATHING = 0.002f
    }
}
