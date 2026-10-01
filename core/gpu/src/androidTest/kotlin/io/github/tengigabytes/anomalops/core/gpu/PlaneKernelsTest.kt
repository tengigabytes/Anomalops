// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.core.gpu

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.tengigabytes.anomalops.core.imaging.align.FrameAligner
import io.github.tengigabytes.anomalops.core.imaging.align.Plane
import io.github.tengigabytes.anomalops.core.imaging.align.Region
import io.github.tengigabytes.anomalops.core.imaging.align.Similarity
import io.github.tengigabytes.anomalops.core.imaging.align.meanSquaredDiff
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random

/**
 * ADR-0017 step 3 on the phone: each kernel against its `:core:imaging` CPU function on a synthetic plane in the
 * 12-bit RAW range, for both storage formats. With `precise` (see [ComputeProgram]) the 32-bit kernels match the
 * CPU bit for bit; half storage rounds each stored value toward zero. Errors are logged under [TAG] as `GPU`
 * lines. The planes are 2040 x 1536, the half-size plane of a 12.5 MP RAW. Timing is `experiment/GpuBenchmark`
 * in `:app` (this test APK is debuggable).
 */
@RunWith(AndroidJUnit4::class)
class PlaneKernelsTest {
    private lateinit var context: GlesContext
    private lateinit var transfer: PlaneTransfer
    private lateinit var kernels: PlaneKernels
    private val plane = texture(WIDTH, HEIGHT, seed = 1)

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
    fun halfMatchesTheCpu() {
        val cpu = plane.half()
        PlaneFormat.entries.forEach { format ->
            val input = transfer.upload(plane, format)
            val out = kernels.half(input)
            val error = compare(cpu, transfer.download(out))
            log("half $format: $error")
            assertTrue("half $format: $error", error.maxRel <= maxRel(format) && error.maskMismatches == 0)
            input.close()
            out.close()
        }
    }

    @Test
    fun warpMatchesTheCpu() {
        val global = Similarity(scale = 1.004f, dx = 3.3f, dy = -2.7f)
        val cpu = FrameAligner(plane).warp(plane, global)
        PlaneFormat.entries.forEach { format ->
            val input = transfer.upload(plane, format)
            val out = kernels.warp(input, global)
            val error = compare(cpu, transfer.download(out))
            log("warp $format: $error")
            assertTrue("warp $format: $error", error.maxRel <= maxRel(format) && error.maskMismatches == 0)
            out.close()
            if (format == PlaneFormat.HALF) {
                val filtered = kernels.warpFiltered(input, global)
                log("warp filtered HALF: ${compare(cpu, transfer.download(filtered))}")
                filtered.close()
            }
            input.close()
        }
    }

    @Test
    fun meanSquaredDiffMatchesTheCpu() {
        val other = texture(WIDTH, HEIGHT, seed = 1, dx = 1.3f, dy = -0.7f)
        val level = 2
        val refLevel = plane.half().half()
        val frameLevel = other.half().half()
        val region = Region.inner(refLevel.width, refLevel.height, MARGIN)
        val candidates = (-SEARCH..SEARCH).flatMap { j ->
            (-SEARCH..SEARCH).map { i ->
                Similarity(
                    1f,
                    i * 1f,
                    j * 1f,
                )
            }
        }
        val full = WIDTH to HEIGHT
        val cpu = candidates.map { meanSquaredDiff(refLevel, frameLevel, it.atLevel(level), region, 1, full) }
        PlaneFormat.entries.forEach { format ->
            val ref = transfer.upload(refLevel, format)
            val frame = transfer.upload(frameLevel, format)
            MeanSquaredDiffKernel().use { kernel ->
                val gpu = kernel.evaluate(ref, frame, candidates, level, region, 1, full)
                val worst = cpu.indices.maxOf { k -> relative(cpu[k], gpu[k]) }
                val sameBest = cpu.indices.minBy { cpu[it] } == gpu.indices.minBy { gpu[it] }
                log("meanSquaredDiff $format: worst relative ${"%.3g".format(worst)}, same best candidate $sameBest")
                assertTrue("meanSquaredDiff $format: $worst", worst <= mseRel(format) && sameBest)
            }
            ref.close()
            frame.close()
        }
    }

    private class Error(val maxAbs: Float, val maxRel: Float, val meanSigned: Double, val maskMismatches: Int) {
        override fun toString() = "max abs %.4g, max rel %.3g, mean signed %.4g, NaN mask mismatches %d"
            .format(maxAbs, maxRel, meanSigned, maskMismatches)
    }

    /** Errors where both have data; [Error.maskMismatches] counts pixels where only one is NaN. */
    private fun compare(cpu: Plane, gpu: Plane): Error {
        var maxAbs = 0f
        var maxRel = 0f
        var sum = 0.0
        var n = 0
        var mismatches = 0
        for (i in cpu.data.indices) {
            val a = cpu.data[i]
            val b = gpu.data[i]
            if (a.isNaN() != b.isNaN()) mismatches++
            if (a.isNaN() || b.isNaN()) continue
            maxAbs = max(maxAbs, abs(b - a))
            maxRel = max(maxRel, abs(b - a) / max(abs(a), 1f))
            sum += b - a
            n++
        }
        return Error(maxAbs, maxRel, if (n > 0) sum / n else 0.0, mismatches)
    }

    private fun relative(a: Float, b: Float) = if (a == b) 0f else abs(a - b) / max(abs(a), abs(b))

    private fun log(text: String) {
        Log.i(TAG, "GPU $text")
    }

    private fun maxRel(format: PlaneFormat) = if (format == PlaneFormat.FLOAT32) 0f else HALF_REL

    private fun mseRel(format: PlaneFormat) = if (format == PlaneFormat.FLOAT32) FLOAT_MSE_REL else HALF_MSE_REL

    private companion object {
        const val TAG = "PlaneKernelsTest"
        const val WIDTH = 2040
        const val HEIGHT = 1536
        const val MARGIN = 0.1f
        const val SEARCH = 4

        /**
         * Half storage: the input and the output are each rounded toward zero once, each by less than one half
         * step (2^-10 relative), and bilinear weights do not enlarge a relative error.
         */
        const val HALF_REL = 2f / 1024

        /** Proposed: per-work-group float sums against the CPU's double sum. */
        const val FLOAT_MSE_REL = 1e-5f

        /** Proposed: differences of half values; the best candidate must still be the CPU's. */
        const val HALF_MSE_REL = 1e-2f

        /** A smooth random texture in the 12-bit RAW range, shifted by ([dx], [dy]). */
        fun texture(width: Int, height: Int, seed: Int, dx: Float = 0f, dy: Float = 0f): Plane {
            val r = Random(seed)
            val cw = 400
            val ch = 300
            val coarse = Plane(cw, ch, FloatArray(cw * ch) { r.nextFloat() })
            return Plane(
                width,
                height,
                FloatArray(width * height) { i ->
                    val x = i % width + dx
                    val y = i / width + dy
                    val base = coarse.sample(
                        x * (cw - 1f) / width,
                        y * (ch - 1f) / height,
                    ).let { if (it.isNaN()) 0f else it }
                    RANGE * (0.9f * base + 0.05f * (1 + sin(x * 0.7f) * sin(y * 0.9f)))
                },
            )
        }

        const val RANGE = 4000f
    }
}
