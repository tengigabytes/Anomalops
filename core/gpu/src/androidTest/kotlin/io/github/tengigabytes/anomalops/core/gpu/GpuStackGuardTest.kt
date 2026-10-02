// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.core.gpu

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.tengigabytes.anomalops.core.imaging.align.Plane
import io.github.tengigabytes.anomalops.core.imaging.stack.ContrastSelectStack
import io.github.tengigabytes.anomalops.core.imaging.stack.FocusStack
import io.github.tengigabytes.anomalops.core.imaging.stack.LaplacianPyramidStack
import io.github.tengigabytes.anomalops.core.imaging.stack.StackGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs
import kotlin.math.max

/**
 * ADR-0017 step 5 on the phone: [GpuStackGuard] against `StackGuard`, around candidates A and B, on
 * [BracketScene] frames whose non-reference frames have a NaN border (as alignment leaves), with the luma and a
 * second channel. Two brackets: the usual one, where the merge should be kept, and one with a frame sharp
 * everywhere, where the guard should fall back to it. The verdict and the best frame must agree with the CPU; the
 * planes and scores may differ by float rounding (limits proposed). Logged under [TAG] as `GPU` lines.
 */
@RunWith(AndroidJUnit4::class)
class GpuStackGuardTest {
    @Test
    fun keptMergeMatchesTheCpu() = check("bands", bracket(sharpFrame = -1))

    @Test
    fun fallbackMatchesTheCpu() = check("one sharp frame", bracket(sharpFrame = 3))

    private fun check(label: String, frames: List<Plane>) {
        val channels = frames.map { listOf(it, second(it)) }
        listOf<Pair<FocusStack, (Int, Int, Int) -> GpuFocusAccumulator>>(
            ContrastSelectStack() to { w, h, c -> GpuContrastSelect().start(w, h, c) },
            LaplacianPyramidStack() to { w, h, c -> GpuLaplacianPyramid().start(w, h, c) },
        ).forEach { (stack, gpuStack) ->
            val cpu = StackGuard(stack).merge(frames, channels, REFERENCE)
            val gpu = GlesContext.create().use { run(gpuStack, frames, channels) }
            log(
                "$label, ${stack.name}: CPU kept ${cpu.merged}, best ${cpu.bestFrame}, " +
                    "sharpness %.6g vs %.6g; GPU kept ${gpu.first.merged}, best ${gpu.first.bestFrame}, sharpness %.6g vs %.6g"
                        .format(cpu.sharpness, cpu.bestSingle, gpu.first.sharpness, gpu.first.bestSingle),
            )
            assertEquals("$label ${stack.name} verdict", cpu.merged, gpu.first.merged)
            assertEquals("$label ${stack.name} best frame", cpu.bestFrame, gpu.first.bestFrame)
            assertTrue("sharpness", relative(cpu.sharpness, gpu.first.sharpness) <= SCORE_REL)
            cpu.channels.indices.forEach { c ->
                val over = cpu.channels[c].data.indices.count {
                    val a = cpu.channels[c].data[it]
                    abs(a - gpu.second[c].data[it]) / max(abs(a), 1f) > CLOSE
                }
                log("$label, ${stack.name}, channel $c: $over pixels over $CLOSE")
                assertTrue("$label ${stack.name} channel $c: $over", over <= cpu.channels[c].data.size * MAX_OVER_SHARE)
            }
        }
    }

    /** The guard on the GPU, every frame through every pass (the candidate's own kernels made per run). */
    private fun run(
        stack: (Int, Int, Int) -> GpuFocusAccumulator,
        frames: List<Plane>,
        channels: List<List<Plane>>,
    ): Pair<GpuStackGuard.Result, List<Plane>> = PlaneTransfer().use { transfer ->
        val upload = { p: Plane -> transfer.upload(p, PlaneFormat.FLOAT32) }
        val refLuma = upload(frames[REFERENCE])
        val refChannels = channels[REFERENCE].map(upload)
        val planes = List(3) { GpuPlane(BracketScene.WIDTH, BracketScene.HEIGHT, PlaneFormat.FLOAT32) }
        GpuStackGuard(stack).use { guard ->
            val session = guard.start(refLuma, refChannels)
            repeat(session.passes) { pass ->
                frames.indices.forEach { k ->
                    transfer.upload(frames[k], PlaneFormat.FLOAT32, into = planes[0])
                    channels[k].forEachIndexed { c, p -> transfer.upload(p, PlaneFormat.FLOAT32, into = planes[c + 1]) }
                    session.add(planes[0], planes.drop(1))
                }
                if (pass < session.passes - 1) session.endPass()
            }
            val result = session.finish()
            val downloaded = result.channels.map { transfer.download(it) }
            (result.channels + planes + refChannels + refLuma).forEach { it.close() }
            result to downloaded
        }
    }

    /** [BracketScene]'s frames (frame [sharpFrame] sharp everywhere), NaN in a border of every non-reference frame. */
    private fun bracket(sharpFrame: Int): List<Plane> = List(BracketScene.FRAMES) { k ->
        val p = BracketScene.frame(k, sharpEverywhere = k == sharpFrame)
        if (k != REFERENCE) {
            for (y in 0 until p.height) for (x in 0 until GAP + k) p[p.width - 1 - x, y] = Float.NaN
        }
        p
    }

    private fun second(p: Plane) = Plane(p.width, p.height, FloatArray(p.data.size) { SECOND * p.data[it] + OFFSET })

    private fun relative(a: Double, b: Double) = if (a == b) 0.0 else abs(a - b) / max(abs(a), abs(b))

    private fun log(text: String) {
        Log.i(TAG, "GPU $text")
    }

    private companion object {
        const val TAG = "GpuStackGuardTest"
        const val REFERENCE = 2
        const val GAP = 6
        const val SECOND = 0.6f
        const val OFFSET = 100f
        const val CLOSE = 1e-3f

        /** Proposed, as in GpuFocusStackTest. */
        const val MAX_OVER_SHARE = 0.005

        /** Proposed: float sums per work group against double sums. */
        const val SCORE_REL = 1e-4
    }
}
