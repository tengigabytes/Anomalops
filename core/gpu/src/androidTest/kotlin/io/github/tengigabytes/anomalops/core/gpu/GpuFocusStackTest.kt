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
import kotlin.random.Random

/**
 * ADR-0017 step 5 on the phone: [GpuContrastSelect] against `ContrastSelectStack` (candidate A) and
 * [GpuLaplacianPyramid] against `LaplacianPyramidStack` (candidate B), on a synthetic focus bracket: five noisy
 * frames of one multi-scale scene, each sharp in its own horizontal band and blurred elsewhere, with the luma and a
 * second channel. The GPU's box sums are float where the CPU's are double, so a near tie between two frames can
 * pick the other; the test counts the pixels that differ and how much (limits proposed). Logged under [TAG] as
 * `GPU` lines, with each result's mean error against the sharp scene.
 */
@RunWith(AndroidJUnit4::class)
class GpuFocusStackTest {
    private val sharp = scene()
    private val luma = List(FRAMES) { k -> bracketFrame(k) }
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

    /** Frame [k]: the scene sharp in the k-th horizontal band, blurred elsewhere, plus its own noise. */
    private fun bracketFrame(k: Int): Plane {
        val blurred = blur(sharp, BLUR)
        val r = Random(NOISE_SEED + k)
        val top = k * HEIGHT / FRAMES
        val bottom = (k + 1) * HEIGHT / FRAMES
        return Plane(
            WIDTH,
            HEIGHT,
            FloatArray(WIDTH * HEIGHT) { i ->
                val y = i / WIDTH
                (if (y in top until bottom) sharp.data[i] else blurred.data[i]) + NOISE * (2 * r.nextFloat() - 1)
            },
        )
    }

    private fun second(p: Plane) = Plane(p.width, p.height, FloatArray(p.data.size) { SECOND * p.data[it] + OFFSET })

    private fun log(text: String) {
        Log.i(TAG, "GPU $text")
    }

    private companion object {
        const val TAG = "GpuFocusStackTest"
        const val WIDTH = 1024
        const val HEIGHT = 768
        const val FRAMES = 5
        const val BLUR = 3
        const val NOISE = 15f
        const val NOISE_SEED = 20
        const val SECOND = 0.6f
        const val OFFSET = 100f

        /** A pixel closer than this (relative) counts as the same result. */
        const val CLOSE = 1e-3f

        /** Proposed: near ties may pick another frame in at most 0.5 % of the pixels. */
        const val MAX_OVER_SHARE = 0.005

        /** Random grids of 256, 64, 16 and 4 pixel cells in equal parts, 12-bit range. */
        fun scene(): Plane {
            val cells = intArrayOf(256, 64, 16, 4)
            val grids = cells.mapIndexed { k, c ->
                val r = Random(10 + k)
                val gw = WIDTH / c + 2
                val gh = HEIGHT / c + 2
                Plane(gw, gh, FloatArray(gw * gh) { r.nextFloat() })
            }
            return Plane(
                WIDTH,
                HEIGHT,
                FloatArray(WIDTH * HEIGHT) { i ->
                    var v = 0f
                    cells.forEachIndexed { k, c ->
                        v += grids[k].sample(
                            (i % WIDTH).toFloat() / c,
                            (i / WIDTH).toFloat() / c,
                        )
                    }
                    4000f * v / cells.size
                },
            )
        }

        /** A separable box blur of [radius], edges clamped. */
        fun blur(p: Plane, radius: Int): Plane {
            fun pass(src: Plane, horizontal: Boolean) = Plane(
                src.width,
                src.height,
                FloatArray(src.data.size) { i ->
                    val x = i % src.width
                    val y = i / src.width
                    var s = 0f
                    for (d in -radius..radius) {
                        s += if (horizontal) {
                            src[(x + d).coerceIn(0, src.width - 1), y]
                        } else {
                            src[x, (y + d).coerceIn(0, src.height - 1)]
                        }
                    }
                    s / (2 * radius + 1)
                },
            )
            return pass(pass(p, true), false)
        }
    }
}
