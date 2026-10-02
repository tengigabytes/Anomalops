// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.experiment

import android.content.Intent
import android.content.IntentFilter
import android.opengl.GLES20
import android.os.BatteryManager
import android.os.PerformanceHintManager
import android.os.PowerManager
import android.os.Process
import android.os.WorkDuration
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.tengigabytes.anomalops.core.gpu.GlesContext
import io.github.tengigabytes.anomalops.core.gpu.GpuDevelop
import io.github.tengigabytes.anomalops.core.gpu.GpuLowLightMerge
import io.github.tengigabytes.anomalops.core.gpu.GpuPlane
import io.github.tengigabytes.anomalops.core.gpu.PlaneFormat
import io.github.tengigabytes.anomalops.core.imaging.align.Similarity
import io.github.tengigabytes.anomalops.core.imaging.develop.Demosaic
import io.github.tengigabytes.anomalops.core.imaging.merge.LowLightMerge
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Why repeated FR-17 bursts get slower (docs/test/m9-gpu-fr17.md, sections 5 and 6). Runs the burst of
 * [GpuRawPipelineBenchmark] (5 RAWs to merged planes; no render) [BURSTS] times back to back, then [BURSTS] times
 * with [PAUSE_MS] of idle before each, then [BURSTS] times with an ADPF hint session that reports each step, and
 * logs `REPEAT` lines under [TAG] per burst: decode and add times, the
 * GPU's and each CPU cluster's mean frequency while the burst ran (devfreq `trans_stat`, cpufreq `time_in_state`),
 * the thermal headroom and the battery temperature. If a pause brings the time back, the slowdown follows heat or
 * clocks; if not, something builds up in the process. An experiment, not product code; the sysfs paths are this
 * phone's.
 */
@RunWith(AndroidJUnit4::class)
class GpuBurstRepeatExperiment {
    private val views = listOf(
        Similarity(),
        Similarity(scale = 1.006f, dx = -13.3f, dy = 7.7f),
        Similarity(scale = 0.997f, dx = 5.2f, dy = -3.9f),
        Similarity(scale = 1.002f, dx = 21.7f, dy = 11.4f),
        Similarity(scale = 0.994f, dx = -8.1f, dy = -17.6f),
    )
    private val raws = views.mapIndexed { k, view -> BenchScene.raw(view, NOISE, noiseSeed = 2 + k) }
    private val sigma = LowLightMerge.estimateNoise(Demosaic.halfSize(raws[0]).luma())
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val hints = context.getSystemService(PerformanceHintManager::class.java)

    @Test
    fun repeatedBursts() = ActivityScenario.launch(ComponentActivity::class.java).use {
        GlesContext.create().use {
            val develop = GpuDevelop()
            val merge = GpuLowLightMerge()
            val w = raws[0].width / 2
            val h = raws[0].height / 2
            val ref = List(PLANES) { GpuPlane(w, h, PlaneFormat.FLOAT32) }
            val frame = List(PLANES) { GpuPlane(w, h, PlaneFormat.FLOAT32) }
            repeat(BURSTS) { burst("back to back $it", develop, merge, ref, frame) }
            repeat(BURSTS) {
                Thread.sleep(PAUSE_MS)
                burst("after a pause $it", develop, merge, ref, frame)
            }
            repeat(BURSTS) { burst("with an ADPF hint $it", develop, merge, ref, frame, hinted = true) }
            (ref + frame).forEach { it.close() }
            listOf(merge, develop).forEach { it.close() }
        }
    }

    private fun burst(
        label: String,
        develop: GpuDevelop,
        merge: GpuLowLightMerge,
        ref: List<GpuPlane>,
        frame: List<GpuPlane>,
        hinted: Boolean = false,
    ) {
        val session = if (hinted) hints.createHintSession(intArrayOf(Process.myTid()), TARGET_NS) else null
        val timed = { block: () -> Unit -> timed(block).also { session?.report(it) } }
        val gpuBefore = residency(GPU_TRANS_STAT)
        val cpuBefore = CPU_POLICIES.map { residency(timeInState(it)) }
        GLES20.glFinish()
        val t0 = System.nanoTime()
        var decodeMs = 0.0
        var addMs = 0.0
        decodeMs += timed { develop.halfSize(raws[0], into = ref) }
        val accumulator = merge.start(ref[LUMA], ref.take(LUMA), sigma)
        for (k in 1 until raws.size) {
            decodeMs += timed { develop.halfSize(raws[k], into = frame) }
            addMs += timed { accumulator.add(frame[LUMA], frame.take(LUMA)) }
        }
        accumulator.finish().forEach { it.close() }
        GLES20.glFinish()
        val totalMs = (System.nanoTime() - t0) / NS_PER_MS
        session?.close()
        val gpu = meanFrequency(gpuBefore, residency(GPU_TRANS_STAT), HZ_PER_MHZ)
        val cpu = CPU_POLICIES.indices.joinToString { i ->
            "cpu${CPU_POLICIES[i]} ${meanFrequency(cpuBefore[i], residency(timeInState(CPU_POLICIES[i])), KHZ_PER_MHZ)}"
        }
        val times = "total %.1f ms, decode %.1f, add %.1f".format(totalMs, decodeMs, addMs)
        val heat = "headroom %.3f, battery %.1f C".format(headroom(), batteryCelsius())
        Log.i(TAG, "REPEAT $label: $times; GPU $gpu; $cpu; $heat")
    }

    /** Reports one step of [ms] to the hint session, as CPU and GPU time alike (both wait for each other here). */
    private fun PerformanceHintManager.Session.report(ms: Double) {
        val ns = (ms * NS_PER_MS).toLong()
        reportActualWorkDuration(
            WorkDuration().apply {
                setWorkPeriodStartTimestampNanos(System.nanoTime() - ns)
                setActualTotalDurationNanos(ns)
                setActualCpuDurationNanos(ns)
                setActualGpuDurationNanos(ns)
            },
        )
    }

    private fun timed(block: () -> Unit): Double {
        GLES20.glFinish()
        val t0 = System.nanoTime()
        block()
        GLES20.glFinish()
        return (System.nanoTime() - t0) / NS_PER_MS
    }

    /** Time at each frequency since boot (lines "freq time"), or null if the app may not read the file. */
    private fun residency(path: String): Map<Long, Long>? = runCatching {
        File(path).readLines().mapNotNull { line ->
            val parts = line.trim().removePrefix("*").trim().split(Regex("\\s+"))
            val freq = parts.firstOrNull()?.removeSuffix(":")?.toLongOrNull() ?: return@mapNotNull null
            freq to parts.last().toLong()
        }.toMap()
    }.getOrNull()

    /** The time-weighted mean frequency in MHz between two readings, and the share at the top step. */
    private fun meanFrequency(before: Map<Long, Long>?, after: Map<Long, Long>?, perMhz: Long): String {
        if (before == null || after == null) return "not readable"
        val spent = after.mapValues { (f, t) -> t - (before[f] ?: 0L) }.filterValues { it > 0 }
        val total = spent.values.sum().toDouble()
        val mean = spent.entries.sumOf { (f, t) -> f.toDouble() / perMhz * t } / total
        val top = (spent[after.keys.max()] ?: 0L) / total
        return if (total == 0.0) "idle" else "%.0f MHz (top %.0f%%)".format(mean, top * PERCENT)
    }

    private fun headroom(): Float =
        context.getSystemService(PowerManager::class.java).getThermalHeadroom(HEADROOM_FORECAST_S)

    private fun batteryCelsius(): Float {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        return (battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / TENTHS
    }

    private fun timeInState(policy: Int) = "/sys/devices/system/cpu/cpufreq/policy$policy/stats/time_in_state"

    private companion object {
        const val TAG = "GpuBurstRepeat"
        const val BURSTS = 6
        const val PAUSE_MS = 10_000L

        /** ADPF target per step (one decode or one add), below what they take, so the hint asks for more speed. */
        const val TARGET_NS = 50_000_000L
        const val PLANES = 4
        const val LUMA = 3
        const val NOISE = 40f
        const val NS_PER_MS = 1e6
        const val GPU_TRANS_STAT = "/sys/class/devfreq/34f00000.gpu0/trans_stat"
        val CPU_POLICIES = intArrayOf(0, 2, 5, 7)
        const val HZ_PER_MHZ = 1_000_000L
        const val KHZ_PER_MHZ = 1_000L
        const val PERCENT = 100
        const val HEADROOM_FORECAST_S = 0
        const val TENTHS = 10f
    }
}
