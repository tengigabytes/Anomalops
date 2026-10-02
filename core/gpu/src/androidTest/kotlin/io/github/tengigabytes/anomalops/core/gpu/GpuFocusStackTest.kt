// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.core.gpu

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.tengigabytes.anomalops.core.imaging.align.Plane
import io.github.tengigabytes.anomalops.core.imaging.stack.ContrastSelectStack
import io.github.tengigabytes.anomalops.core.imaging.stack.FocusStack
import io.github.tengigabytes.anomalops.core.imaging.stack.LaplacianPyramidStack
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs
import kotlin.math.max

/**
 * ADR-0017 step 5 on the phone: [GpuContrastSelect] against `ContrastSelectStack` (candidate A) and
 * [GpuLaplacianPyramid] against `LaplacianPyramidStack` (candidate B), on the synthetic focus bracket of
 * [BracketScene] with the luma and a second channel. The GPU's box sums are float where the CPU's are double,
 * so a near tie between two frames can pick the other; the test counts the pixels that differ and how much (limits proposed). Logged under [TAG] as
 * `GPU` lines, with each result's mean error against the sharp scene.
 */
@RunWith(AndroidJUnit4::class)
class GpuFocusStackTest {
    private val sharp = BracketScene.sharp
    private val luma = List(FRAMES) { k -> BracketScene.frame(k) }
    private val channels = luma.map { listOf(it, second(it)) }

    @Test
    fun candidateAMatchesTheCpu() {
        val cpu = ContrastSelectStack().merge(luma, channels)
        val gpu = GlesContext.create().use { GpuContrastSelect().use { run(it.start(WIDTH, HEIGHT, 2)) } }
        compare("A", ContrastSelectStack(), cpu, gpu)
    }

    @Test
    fun candidateBMatchesTheCpu() {
        val cpu = LaplacianPyramidStack().merge(luma, channels)
        val gpu = GlesContext.create().use { GpuLaplacianPyramid().use { run(it.start(WIDTH, HEIGHT, 2)) } }
        compare("B", LaplacianPyramidStack(), cpu, gpu)
    }

    /** Every frame through every pass, uploaded into the same planes each time, then the result downloaded. */
    private fun run(accumulator: GpuFocusAccumulator): List<Plane> = PlaneTransfer().use { transfer ->
        val planes = List(3) { GpuPlane(WIDTH, HEIGHT, PlaneFormat.FLOAT32) }
        accumulator.use {
            repeat(it.passes) { pass ->
                for (k in 0 until FRAMES) {
                    transfer.upload(luma[k], PlaneFormat.FLOAT32, into = planes[0])
                    channels[k].forEachIndexed { c, p -> transfer.upload(p, PlaneFormat.FLOAT32, into = planes[c + 1]) }
                    it.add(planes[0], planes.drop(1))
                }
                if (pass < it.passes - 1) it.endPass()
            }
            val merged = it.finish()
            merged.map { p -> transfer.download(p) }.also { merged.forEach { p -> p.close() } }
        }.also { planes.forEach { it.close() } }
    }

    private fun compare(label: String, stack: FocusStack, cpu: List<Plane>, gpu: List<Plane>) {
        log("$label (${stack.name}): mean error against the sharp scene, CPU ${error(cpu[0])}, GPU ${error(gpu[0])}")
        cpu.indices.forEach { c ->
            var maxRel = 0f
            var over = 0
            var nan = 0
            for (i in cpu[c].data.indices) {
                val a = cpu[c].data[i]
                val b = gpu[c].data[i]
                if (a.isNaN() || b.isNaN()) {
                    if (a.isNaN() != b.isNaN()) nan++
                    continue
                }
                val rel = abs(a - b) / max(abs(a), 1f)
                maxRel = max(maxRel, rel)
                if (rel > CLOSE) over++
            }
            log(
                "$label channel $c: max rel %.3g, %d of %d pixels over %.0e, %d NaN mismatches".format(
                    maxRel,
                    over,
                    cpu[c].data.size,
                    CLOSE,
                    nan,
                ),
            )
            assertTrue("$label channel $c: $over over", over <= cpu[c].data.size * MAX_OVER_SHARE && nan == 0)
        }
    }

    private fun error(p: Plane): String = "%.2f".format(
        p.data.indices.sumOf { abs(p.data[it] - sharp.data[it]).toDouble() } / p.data.size,
    )

    private fun second(p: Plane) = Plane(p.width, p.height, FloatArray(p.data.size) { SECOND * p.data[it] + OFFSET })

    private fun log(text: String) {
        Log.i(TAG, "GPU $text")
    }

    private companion object {
        const val TAG = "GpuFocusStackTest"
        const val WIDTH = BracketScene.WIDTH
        const val HEIGHT = BracketScene.HEIGHT
        const val FRAMES = BracketScene.FRAMES
        const val SECOND = 0.6f
        const val OFFSET = 100f

        /** A pixel closer than this (relative) counts as the same result. */
        const val CLOSE = 1e-3f

        /** Proposed: near ties may pick another frame in at most 0.5 % of the pixels. */
        const val MAX_OVER_SHARE = 0.005
    }
}
