// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.experiment

import android.opengl.GLES20
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.tengigabytes.anomalops.core.gpu.GlesContext
import io.github.tengigabytes.anomalops.core.gpu.GpuLowLightMerge
import io.github.tengigabytes.anomalops.core.gpu.PlaneFormat
import io.github.tengigabytes.anomalops.core.gpu.PlaneTransfer
import io.github.tengigabytes.anomalops.core.imaging.align.Plane
import io.github.tengigabytes.anomalops.core.imaging.align.Similarity
import io.github.tengigabytes.anomalops.core.imaging.merge.LowLightMerge
import org.junit.Test
import org.junit.runner.RunWith

/**
 * ADR-0017 step 4: FR-17's merge of a 5-frame burst on the GPU ([GpuLowLightMerge]): per frame the luma aligns
 * and weights, and three colour planes (R, G, B, here derived from the luma) are merged, all [BenchScene] planes.
 * Frames arrive one at a time: each is uploaded (luma and three colours), then added. With an activity in the
 * foreground; the burst runs [BURSTS] times, the first to warm up (shader compile). Logs `MERGEBENCH` lines under
 * [TAG]: start, each frame's upload and add (after glFinish), finish, and the burst's total. Colour conversion,
 * RAW decoding and output are not in it yet. Time it as a non-debuggable app, like [ImagingBenchmark].
 */
@RunWith(AndroidJUnit4::class)
class GpuMergeBenchmark {
    private val views = listOf(
        Similarity(),
        Similarity(scale = 1.006f, dx = -13.3f, dy = 7.7f),
        Similarity(scale = 0.997f, dx = 5.2f, dy = -3.9f),
        Similarity(scale = 1.002f, dx = 21.7f, dy = 11.4f),
        Similarity(scale = 0.994f, dx = -8.1f, dy = -17.6f),
    )
    private val lumas = views.mapIndexed { k, view -> BenchScene.render(view, NOISE, noiseSeed = 2 + k) }
    private val sigma = LowLightMerge.estimateNoise(lumas[0])

    @Test
    fun lowLightMerge() = ActivityScenario.launch(ComponentActivity::class.java).use {
        GlesContext.create().use {
            val transfer = PlaneTransfer()
            val merge = GpuLowLightMerge()
            repeat(BURSTS) { burst(it, transfer, merge) }
            listOf(merge, transfer).forEach { it.close() }
        }
    }

    private fun burst(index: Int, transfer: PlaneTransfer, merge: GpuLowLightMerge) {
        var total = 0.0
        val upload = { k: Int ->
            val planes = colours(lumas[k])
            val (gpu, ms) = timed { (planes + lumas[k]).map { transfer.upload(it, PlaneFormat.FLOAT32) } }
            log(index, "frame $k upload 4 planes", ms)
            total += ms
            gpu
        }
        val reference = upload(0)
        val (accumulator, startMs) = timed { merge.start(reference.last(), reference.dropLast(1), sigma) }
        log(index, "start", startMs)
        total += startMs
        for (k in 1 until views.size) {
            val frame = upload(k)
            val (alignment, ms) = timed { accumulator.add(frame.last(), frame.dropLast(1)) }
            log(index, "frame $k add (aligned ${alignment.global})", ms)
            total += ms
            frame.forEach { it.close() }
        }
        val (merged, finishMs) = timed { accumulator.finish() }
        log(index, "finish", finishMs)
        total += finishMs
        log(index, "burst total (5 frames, 3 colours)", total)
        (merged + reference).forEach { it.close() }
    }

    /** R, G and B derived from the luma, as three planes the same size that follow its geometry. */
    private fun colours(luma: Plane): List<Plane> = COLOUR_GAINS.map { gain ->
        Plane(luma.width, luma.height, FloatArray(luma.data.size) { gain * luma.data[it] })
    }

    private fun <T> timed(block: () -> T): Pair<T, Double> {
        GLES20.glFinish()
        val t0 = System.nanoTime()
        val result = block()
        GLES20.glFinish()
        return result to (System.nanoTime() - t0) / NS_PER_MS
    }

    private fun log(burst: Int, label: String, ms: Double) {
        Log.i(TAG, "MERGEBENCH burst $burst $label: %.1f ms".format(ms))
    }

    private companion object {
        const val TAG = "GpuMergeBenchmark"
        const val BURSTS = 3
        const val NOISE = 40f
        const val NS_PER_MS = 1e6
        val COLOUR_GAINS = floatArrayOf(0.5f, 1f, 0.3f)
    }
}
