// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.core.imaging.merge

import io.github.tengigabytes.anomalops.core.imaging.align.Plane
import io.github.tengigabytes.anomalops.core.imaging.align.Region
import io.github.tengigabytes.anomalops.core.imaging.align.Similarity
import io.github.tengigabytes.anomalops.core.imaging.align.SyntheticScene
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.sqrt

/** FR-17 on a synthetic burst: hand shake between frames, sensor noise, and one subject that moves. */
class LowLightMergeTest {
    private val width = 320
    private val height = 240
    private val scene = SyntheticScene(width, height, blobs = 300, seed = 5)
    private val random = Random(21)
    private val shakes = listOf(
        Similarity(),
        Similarity(dx = 2.6f, dy = -1.4f),
        Similarity(dx = -3.3f, dy = 0.8f),
        Similarity(dx = 1.2f, dy = 3.1f),
        Similarity(dx = -0.7f, dy = -2.5f),
    )

    private fun noisy(p: Plane): Plane =
        Plane(p.width, p.height, FloatArray(p.data.size) { p.data[it] + NOISE * random.nextGaussian().toFloat() })

    private fun rmse(a: Plane, b: Plane, region: Region): Double {
        var sum = 0.0
        var n = 0
        for (y in region.top until region.bottom) {
            for (x in region.left until region.right) {
                val d = a[x, y] - b[x, y]
                sum += d * d
                n++
            }
        }
        return sqrt(sum / n)
    }

    private val inner = Region(16, 16, width - 16, height - 16)

    @Test
    fun fr17_fiveFramesHalveTheNoise() {
        val truth = scene.render(width, height)
        val burst = shakes.map { noisy(scene.render(width, height, it)) }
        val merged = LowLightMerge().merge(burst, noiseSigma = NOISE).channels.single()
        val single = rmse(burst[0], truth, inner)
        val error = rmse(merged, truth, inner)
        println("FR-17 noise: single %.2f, merged %.2f".format(single, error))
        // FR-17: noise standard deviation down by at least 50 %.
        assertTrue("merged $error against single $single", error <= 0.5 * single)
    }

    @Test
    fun fr17_movingFishLeavesNoGhost() {
        // A patch moves 6 px further in every frame after the first, as a fish swimming through.
        val fish = Region(150, 100, 210, 150)
        val burst = shakes.mapIndexed { k, shake ->
            val local = if (k == 0) null else fish to (6f * k to 0f)
            noisy(scene.render(width, height, shake, local = local))
        }
        val merged = LowLightMerge().merge(burst, noiseSigma = NOISE).channels.single()
        // Inside the fish the merge must look like the reference frame, not a blend of five positions.
        val core = Region(fish.left + 8, fish.top + 8, fish.right - 8, fish.bottom - 8)
        val toReference = rmse(merged, burst[0], core)
        val blend = Plane(
            width,
            height,
            FloatArray(width * height) { i -> burst.map { it.data[i] }.average().toFloat() },
        )
        val toBlend = rmse(merged, blend, core)
        println("FR-17 ghost: to reference %.2f, to a plain average %.2f".format(toReference, toBlend))
        assertTrue("ghost: $toReference from the reference, $toBlend from a blend", toReference < 0.5 * toBlend)
        assertTrue("ghost: $toReference from the reference frame", toReference < 2 * NOISE)
    }

    @Test
    fun noiseEstimateFindsTheAddedNoise() {
        val flat = Plane(200, 200, FloatArray(200 * 200) { 100f })
        assertEquals(NOISE, LowLightMerge.estimateNoise(noisy(flat)), 0.15f * NOISE)
    }

    @Test
    fun colourChannelsAreMergedWithTheLumaWeights() {
        val burst = shakes.map { noisy(scene.render(width, height, it)) }
        val channels = burst.map {
            listOf(
                it,
                Plane(width, height, FloatArray(width * height) { i -> 3 * it.data[i] }),
            )
        }
        val (luma, tripled) = LowLightMerge().merge(burst, channels, noiseSigma = NOISE).channels
        val worst = luma.data.indices.maxOf { i -> kotlin.math.abs(tripled.data[i] - 3 * luma.data[i]) }
        assertTrue("channel drifted by $worst", worst < 1e-2f)
    }

    private companion object {
        const val NOISE = 6f
    }
}
