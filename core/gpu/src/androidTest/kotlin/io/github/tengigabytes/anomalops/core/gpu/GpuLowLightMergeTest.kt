// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.core.gpu

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.tengigabytes.anomalops.core.imaging.align.Plane
import io.github.tengigabytes.anomalops.core.imaging.align.Region
import io.github.tengigabytes.anomalops.core.imaging.align.Similarity
import io.github.tengigabytes.anomalops.core.imaging.merge.LowLightMerge
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs
import kotlin.math.max

/**
 * ADR-0017 step 4 on the phone: [GpuLowLightMerge] against `LowLightMerge`, frame by frame, on noisy [AlignScene]
 * frames seen through different scales and shifts, one with a region that moved on its own (which the weights
 * should keep out). Two channels: the luma and a second plane derived from it. The GPU's `exp` and float sums
 * differ from the CPU's in the last bits, so the merged planes agree closely but not bit for bit; limits are
 * proposals. Results are logged under [TAG] as `GPU` lines.
 */
@RunWith(AndroidJUnit4::class)
class GpuLowLightMergeTest {
    private val views = listOf(
        Similarity(scale = 1.006f, dx = 13.4f, dy = -7.7f),
        Similarity(scale = 0.997f, dx = -5.2f, dy = 3.9f),
    )
    private val moved = Region(MOVED_LEFT, MOVED_TOP, MOVED_RIGHT, MOVED_BOTTOM)

    @Test
    fun mergeMatchesTheCpu() {
        val refLuma = AlignScene.render(Similarity(), NOISE, noiseSeed = 2)
        val sigma = LowLightMerge.estimateNoise(refLuma)
        val cpu = LowLightMerge().start(refLuma, channels(refLuma), sigma)
        val (gpu, noise) = GlesContext.create().use {
            val transfer = PlaneTransfer()
            val upload = { p: Plane -> transfer.upload(p, PlaneFormat.FLOAT32) }
            val refChannels = channels(refLuma).map(upload)
            val refGpu = upload(refLuma)
            val merge = GpuLowLightMerge()
            val accumulator = merge.start(refGpu, refChannels, sigma)
            views.forEachIndexed { k, view ->
                val local = if (k == 0) moved to (LOCAL_DX to LOCAL_DY) else null
                val luma = AlignScene.render(view, NOISE, noiseSeed = 3 + k, local = local)
                val cpuAlignment = cpu.add(luma, channels(luma))
                val frame = upload(luma)
                val frameChannels = channels(luma).map(upload)
                val gpuAlignment = accumulator.add(frame, frameChannels)
                log("frame $k: CPU ${cpuAlignment.global}, GPU ${gpuAlignment.global}")
                (frameChannels + frame).forEach { it.close() }
            }
            val merged = accumulator.finish()
            val result = merged.map { transfer.download(it) }
            (merged + refChannels + refGpu).forEach { it.close() }
            listOf(merge, transfer).forEach { it.close() }
            result to LowLightMerge.estimateNoise(result[0])
        }
        val expected = cpu.finish()
        log("noise sigma: reference %.2f, GPU merge %.2f".format(sigma, noise))
        expected.indices.forEach { c ->
            val diff = Diff(expected[c], gpu[c])
            log("channel $c: $diff")
            assertTrue("channel $c: $diff", diff.maxRel <= MAX_REL && diff.nanMismatches == 0)
        }
    }

    /** The luma and a second channel derived from it, as R, G and B would follow the same geometry. */
    private fun channels(luma: Plane): List<Plane> =
        listOf(luma, Plane(luma.width, luma.height, FloatArray(luma.data.size) { SECOND * luma.data[it] + OFFSET }))

    private class Diff(cpu: Plane, gpu: Plane) {
        var maxRel = 0f
        var overTiny = 0
        var nanMismatches = 0

        init {
            for (i in cpu.data.indices) {
                val a = cpu.data[i]
                val b = gpu.data[i]
                if (a.isNaN() != b.isNaN()) nanMismatches++
                if (a.isNaN() || b.isNaN()) continue
                val rel = abs(a - b) / max(abs(a), 1f)
                maxRel = max(maxRel, rel)
                if (rel > TINY) overTiny++
            }
        }

        override fun toString() = "max rel %.3g, %d pixels over %.0e, %d NaN mismatches"
            .format(maxRel, overTiny, TINY, nanMismatches)
    }

    private fun log(text: String) {
        Log.i(TAG, "GPU $text")
    }

    private companion object {
        const val TAG = "GpuLowLightMergeTest"
        const val NOISE = 40f
        const val SECOND = 0.6f
        const val OFFSET = 100f
        const val MOVED_LEFT = 800
        const val MOVED_TOP = 608
        const val MOVED_RIGHT = 1120
        const val MOVED_BOTTOM = 800
        const val LOCAL_DX = 3.4f
        const val LOCAL_DY = -2.2f

        /** Proposed: the GPU's exp and float rounding, not a different weight or position. */
        const val MAX_REL = 1e-3f
        const val TINY = 1e-6f
    }
}
