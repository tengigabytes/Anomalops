// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.experiment

import android.opengl.GLES20
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.tengigabytes.anomalops.core.gpu.GlesContext
import io.github.tengigabytes.anomalops.core.gpu.GpuDevelop
import io.github.tengigabytes.anomalops.core.gpu.GpuLowLightMerge
import io.github.tengigabytes.anomalops.core.gpu.GpuPlane
import io.github.tengigabytes.anomalops.core.gpu.PlaneFormat
import io.github.tengigabytes.anomalops.core.imaging.align.Similarity
import io.github.tengigabytes.anomalops.core.imaging.develop.Demosaic
import io.github.tengigabytes.anomalops.core.imaging.develop.ShadingMap
import io.github.tengigabytes.anomalops.core.imaging.merge.LowLightMerge
import org.junit.Test
import org.junit.runner.RunWith

/**
 * ADR-0017 step 4: FR-17 from RAW to sRGB on the GPU for a 5-frame burst of 12.5 MP RAW_SENSOR frames
 * ([BenchScene.raw], 4080 x 3072): each RAW uploaded and decoded to half-size R, G, B and luma ([GpuDevelop]),
 * aligned and merged ([GpuLowLightMerge]), then the merge rendered to ARGB with white balance, colour matrix and a
 * shading map, and read back. The RAWs are made before timing, as a burst waiting in the ring buffer (FR-62);
 * the noise sigma comes from the CPU, as the sensor's noise profile would. With an activity in the foreground;
 * the burst runs [BURSTS] times, the first to warm up. Logs `RAWBENCH` lines under [TAG]. Time it as a
 * non-debuggable app, like [ImagingBenchmark].
 */
@RunWith(AndroidJUnit4::class)
class GpuRawPipelineBenchmark {
    private val views = listOf(
        Similarity(),
        Similarity(scale = 1.006f, dx = -13.3f, dy = 7.7f),
        Similarity(scale = 0.997f, dx = 5.2f, dy = -3.9f),
        Similarity(scale = 1.002f, dx = 21.7f, dy = 11.4f),
        Similarity(scale = 0.994f, dx = -8.1f, dy = -17.6f),
    )
    private val raws = views.mapIndexed { k, view -> BenchScene.raw(view, NOISE, noiseSeed = 2 + k) }
    private val sigma = LowLightMerge.estimateNoise(Demosaic.halfSize(raws[0]).luma())
    private val gains = floatArrayOf(2.0f, 1.0f, 1.6f)
    private val matrix = floatArrayOf(1.6f, -0.4f, -0.2f, -0.2f, 1.5f, -0.3f, 0.0f, -0.5f, 1.5f)
    private val shading = ShadingMap(
        SHADING_COLUMNS,
        SHADING_ROWS,
        FloatArray(SHADING_COLUMNS * SHADING_ROWS * 4) { 1.2f },
    )

    @Test
    fun rawToArgb() = ActivityScenario.launch(ComponentActivity::class.java).use {
        GlesContext.create().use {
            val develop = GpuDevelop()
            val merge = GpuLowLightMerge()
            val w = raws[0].width / 2
            val h = raws[0].height / 2
            val reference = List(PLANES) { GpuPlane(w, h, PlaneFormat.FLOAT32) }
            val frame = List(PLANES) { GpuPlane(w, h, PlaneFormat.FLOAT32) }
            repeat(BURSTS) { burst(it, develop, merge, reference, frame) }
            (reference + frame).forEach { it.close() }
            listOf(merge, develop).forEach { it.close() }
        }
    }

    private fun burst(
        index: Int,
        develop: GpuDevelop,
        merge: GpuLowLightMerge,
        ref: List<GpuPlane>,
        frame: List<GpuPlane>,
    ) {
        var total = 0.0
        fun step(label: String, block: () -> Unit) {
            GLES20.glFinish()
            val t0 = System.nanoTime()
            block()
            GLES20.glFinish()
            val ms = (System.nanoTime() - t0) / NS_PER_MS
            total += ms
            Log.i(TAG, "RAWBENCH burst $index $label: %.1f ms".format(ms))
        }
        step("frame 0 upload and decode") { develop.halfSize(raws[0], into = ref) }
        lateinit var accumulator: GpuLowLightMerge.Accumulator
        step("start") { accumulator = merge.start(ref[LUMA], ref.take(LUMA), sigma) }
        for (k in 1 until raws.size) {
            step("frame $k upload and decode") { develop.halfSize(raws[k], into = frame) }
            step("frame $k add") { accumulator.add(frame[LUMA], frame.take(LUMA)) }
        }
        lateinit var merged: List<GpuPlane>
        step("finish") { merged = accumulator.finish() }
        step("render and read back") {
            develop.toArgb(
                merged,
                gains,
                matrix,
                shading = shading,
                rawWidth = raws[0].width,
                rawHeight = raws[0].height,
            )
        }
        merged.forEach { it.close() }
        Log.i(TAG, "RAWBENCH burst $index total (5 RAW frames to ARGB): %.1f ms".format(total))
    }

    private companion object {
        const val TAG = "GpuRawPipelineBenchmark"
        const val BURSTS = 3
        const val PLANES = 4
        const val LUMA = 3
        const val NOISE = 40f
        const val NS_PER_MS = 1e6
        const val SHADING_COLUMNS = 33
        const val SHADING_ROWS = 25
    }
}
