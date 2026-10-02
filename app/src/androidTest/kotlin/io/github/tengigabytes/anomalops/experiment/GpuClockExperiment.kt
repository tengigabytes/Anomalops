// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.experiment

import android.opengl.GLES20
import android.os.PerformanceHintManager
import android.os.Process
import android.os.WorkDuration
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
import io.github.tengigabytes.anomalops.core.imaging.align.Plane
import io.github.tengigabytes.anomalops.core.imaging.align.Region
import io.github.tengigabytes.anomalops.core.imaging.align.Similarity
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.sin
import kotlin.random.Random

/**
 * Why the GPU clock stays low during merge work (docs/test/m9-gpu-fr17.md). Each scenario runs a few seconds of
 * kernels with an activity in the foreground and logs `CLOCK` lines under [TAG]: time per call and, when the app
 * may read it, how long the GPU spent at each frequency (the devfreq `trans_stat`, before against after).
 * Scenarios: the warp into a new plane per call or into one reused plane, waiting for the GPU after every call or
 * only at the end, and each with an ADPF hint session that reports the work as slower than its target. The devfreq path is this phone's; an experiment, not product code.
 */
@RunWith(AndroidJUnit4::class)
class GpuClockExperiment {
    private val width = 2040
    private val height = 1536
    private val plane = texture(0f, 0f)
    private val other = texture(1.3f, -0.7f)
    private val global = Similarity(scale = 1.004f, dx = 3.3f, dy = -2.7f)
    private val region = Region.inner(width, height, MARGIN)
    private val around = (-1..1).flatMap { s ->
        (-1..1).flatMap { j -> (-1..1).map { Similarity(1f + s * SCALE_STEP, 1f + it, j - 1f) } }
    }
    private val hints = InstrumentationRegistry.getInstrumentation().targetContext
        .getSystemService(PerformanceHintManager::class.java)

    @Test
    fun scenarios() = ActivityScenario.launch(ComponentActivity::class.java).use {
        GlesContext.create().use {
            val transfer = PlaneTransfer()
            val kernels = PlaneKernels()
            val mse = MeanSquaredDiffKernel()
            val ref = transfer.upload(plane, PlaneFormat.FLOAT32)
            val frame = transfer.upload(other, PlaneFormat.FLOAT32)
            val out = GpuPlane(width, height, PlaneFormat.FLOAT32)
            for (hinted in listOf(false, true)) {
                val label = if (hinted) "ADPF" else "no hint"
                warpScenarios(kernels, ref, out, hinted, label)
                scenario("meanSquaredDiff 27 candidates (reads back), $label", MSE_CALLS, hinted, syncEach = true) {
                    mse.evaluate(ref, frame, around, 0, region, 2, width to height)
                }
            }
            listOf<AutoCloseable>(ref, frame, out, mse, kernels, transfer).forEach { it.close() }
        }
    }

    /** The warp into a new plane per call and into [out], each waiting after every call and only at the end. */
    private fun warpScenarios(kernels: PlaneKernels, ref: GpuPlane, out: GpuPlane, hinted: Boolean, label: String) {
        for (syncEach in listOf(true, false)) {
            val wait = if (syncEach) "wait every call" else "wait at the end"
            scenario("warp new plane, $wait, $label", WARPS, hinted, syncEach) {
                kernels.warp(ref, global).close()
            }
            scenario("warp reused plane, $wait, $label", WARPS, hinted, syncEach) {
                kernels.warp(ref, global, into = out)
            }
        }
    }

    /** [calls] calls of [work]; with [syncEach] glFinish after each, otherwise a flush and one finish at the end. */
    private fun scenario(label: String, calls: Int, hinted: Boolean, syncEach: Boolean, work: () -> Unit) {
        val session = if (hinted) hints?.createHintSession(intArrayOf(Process.myTid()), TARGET_NS) else null
        Thread.sleep(IDLE_MS)
        val before = residency()
        val t0 = System.nanoTime()
        repeat(calls) {
            val start = System.nanoTime()
            work()
            if (syncEach) GLES20.glFinish() else GLES20.glFlush()
            val took = System.nanoTime() - start
            session?.reportActualWorkDuration(
                WorkDuration().apply {
                    setWorkPeriodStartTimestampNanos(start)
                    setActualTotalDurationNanos(took)
                    setActualCpuDurationNanos(took)
                    setActualGpuDurationNanos(took)
                },
            )
        }
        GLES20.glFinish()
        val ms = (System.nanoTime() - t0) / NS_PER_MS
        val after = residency()
        session?.close()
        val spent = if (before != null && after != null) {
            after.keys.associateWith { (after.getValue(it) - (before[it] ?: 0L)) }.filterValues { it > 0 }
                .entries.joinToString { "${it.key / MHZ} MHz ${it.value} ms" }
        } else {
            "trans_stat not readable"
        }
        Log.i(TAG, "CLOCK $label: $calls calls, %.1f ms per call, %.0f ms total; %s".format(ms / calls, ms, spent))
    }

    /** Milliseconds spent at each GPU frequency since boot, or null if the app may not read them. */
    private fun residency(): Map<Long, Long>? = runCatching {
        File(TRANS_STAT).readLines().mapNotNull { line ->
            val parts = line.trim().removePrefix("*").trim().split(Regex("\\s+"))
            val freq = parts.firstOrNull()?.removeSuffix(":")?.toLongOrNull() ?: return@mapNotNull null
            freq to parts.last().toLong()
        }.toMap()
    }.onFailure { Log.i(TAG, "CLOCK trans_stat: $it") }.getOrNull()

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
        const val TAG = "GpuClock"
        const val TRANS_STAT = "/sys/class/devfreq/34f00000.gpu0/trans_stat"
        const val WARPS = 300
        const val MSE_CALLS = 60
        const val TARGET_NS = 16_000_000L
        const val IDLE_MS = 1500L
        const val NS_PER_MS = 1e6
        const val MHZ = 1_000_000L
        const val MARGIN = 0.1f
        const val SCALE_STEP = 0.0025f
        const val COARSE_W = 400
        const val COARSE_H = 300
        const val RANGE = 4000f
    }
}
