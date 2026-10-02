// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.core.gpu

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.tengigabytes.anomalops.core.imaging.align.GaussNewton
import io.github.tengigabytes.anomalops.core.imaging.align.GlobalAligner
import io.github.tengigabytes.anomalops.core.imaging.align.GradientSums
import io.github.tengigabytes.anomalops.core.imaging.align.Pyramid
import io.github.tengigabytes.anomalops.core.imaging.align.Region
import io.github.tengigabytes.anomalops.core.imaging.align.Similarity
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs
import kotlin.math.max

/**
 * ADR-0017 step 4 on the phone: [GaussNewtonKernel] against `GradientSums`, and [GpuGlobalAligner] against
 * `GlobalAligner` and the truth, on a noisy [AlignScene] seen through a known scale and shift. The GPU sums in
 * float per work group and the CPU in double per term, so they agree closely but not bit for bit; the limits are
 * proposals. Results are logged under [TAG] as `GPU` lines; times are from this debuggable APK and only
 * indicative (timing is `experiment/GpuAlignBenchmark` in `:app`).
 */
@RunWith(AndroidJUnit4::class)
class GpuGlobalAlignerTest {
    private lateinit var context: GlesContext
    private lateinit var transfer: PlaneTransfer
    private lateinit var kernels: PlaneKernels
    private val truth = Similarity(scale = 1.006f, dx = 13.4f, dy = -7.7f)
    private val reference = AlignScene.render(Similarity(), NOISE, noiseSeed = 2)
    private val frame = AlignScene.render(truth, NOISE, noiseSeed = 3)

    @Before
    fun setUp() {
        context = GlesContext.create()
        transfer = PlaneTransfer()
        kernels = PlaneKernels()
    }

    @After
    fun tearDown() {
        kernels.close()
        transfer.close()
        context.close()
    }

    @Test
    fun normalEquationsMatchTheCpu() {
        val region = Region.inner(WIDTH, HEIGHT, MARGIN)
        val start = Similarity(truth.scale - 0.001f, truth.dx + 0.4f, truth.dy - 0.3f)
        val cpu = GradientSums(reference, region, STEP).at(frame, start)
        val ref = transfer.upload(reference, PlaneFormat.FLOAT32)
        val fr = transfer.upload(frame, PlaneFormat.FLOAT32)
        val gpu = GaussNewtonKernel().use { it.equations(ref, fr, start, region, STEP) }
        ref.close()
        fr.close()
        val worstA = cpu.a.indices.maxOf { relative(cpu.a[it], gpu.a[it]) }
        val worstB = cpu.b.indices.maxOf { relative(cpu.b[it], gpu.b[it]) }
        val cpuStep = GaussNewton.solve3(cpu.a, cpu.b)
        val gpuStep = GaussNewton.solve3(gpu.a, gpu.b)
        assertNotNull(cpuStep)
        assertNotNull(gpuStep)
        val scaleDiff = abs(cpuStep!![0] - gpuStep!![0])
        val shiftDiff = max(abs(cpuStep[1] - gpuStep[1]), abs(cpuStep[2] - gpuStep[2]))
        log(
            "equations: worst relative a %.3g, b %.3g; step scale diff %.3g, shift diff %.3g px"
                .format(worstA, worstB, scaleDiff, shiftDiff),
        )
        assertTrue("a $worstA, b $worstB", worstA <= EQUATIONS_REL && worstB <= EQUATIONS_REL)
        assertTrue("step $scaleDiff, $shiftDiff", scaleDiff <= SCALE_DIFF && shiftDiff <= SHIFT_DIFF)
    }

    @Test
    fun alignmentMatchesTheCpu() {
        val t0 = System.nanoTime()
        val cpu = GlobalAligner().align(Pyramid(reference), Pyramid(frame))
        val t1 = System.nanoTime()
        val ref = transfer.upload(reference, PlaneFormat.FLOAT32)
        val fr = transfer.upload(frame, PlaneFormat.FLOAT32)
        val t2 = System.nanoTime()
        val refPyramid = GpuPyramid(ref, kernels)
        val frPyramid = GpuPyramid(fr, kernels, maxLevels = refPyramid.top)
        val gpu = GpuGlobalAligner().use { it.align(refPyramid, frPyramid) }
        val t3 = System.nanoTime()
        listOf<AutoCloseable>(refPyramid, frPyramid, ref, fr).forEach { it.close() }
        val scaleDiff = abs(cpu.scale - gpu.scale)
        val shiftDiff = max(abs(cpu.dx - gpu.dx), abs(cpu.dy - gpu.dy))
        log("align truth $truth")
        log("align CPU $cpu, %.0f ms".format((t1 - t0) / NS_PER_MS))
        log("align GPU $gpu, %.0f ms (pyramids and alignment, after upload)".format((t3 - t2) / NS_PER_MS))
        val scaleError = abs(gpu.scale - truth.scale)
        val shiftError = max(abs(gpu.dx - truth.dx), abs(gpu.dy - truth.dy))
        log("align GPU against CPU: scale diff %.3g, shift diff %.3g px".format(scaleDiff, shiftDiff))
        log("align GPU against truth: scale error %.3g, shift error %.3g px".format(scaleError, shiftError))
        assertTrue("align $cpu against $gpu", scaleDiff <= SCALE_DIFF && shiftDiff <= SHIFT_DIFF)
        assertTrue("align $gpu against $truth", scaleError <= SCALE_DIFF && shiftError <= SHIFT_DIFF)
    }

    private fun relative(a: Double, b: Double) = if (a == b) 0.0 else abs(a - b) / max(abs(a), abs(b))

    private fun log(text: String) {
        Log.i(TAG, "GPU $text")
    }

    private companion object {
        const val TAG = "GpuGlobalAlignerTest"
        const val WIDTH = AlignScene.WIDTH
        const val HEIGHT = AlignScene.HEIGHT
        const val MARGIN = 0.1f
        const val STEP = 2
        const val NS_PER_MS = 1e6

        /** Uniform noise of ± this many RAW units, so the frames differ as two exposures do. */
        const val NOISE = 40f

        /** Proposed: float sums per work group against double sums per term. */
        const val EQUATIONS_REL = 1e-4

        /** Proposed: well below what the merge can see (the tile search works in whole pixels and a parabola). */
        const val SCALE_DIFF = 1e-5
        const val SHIFT_DIFF = 1e-2
    }
}
