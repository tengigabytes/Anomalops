// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.core.gpu

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.tengigabytes.anomalops.core.imaging.align.Pyramid
import io.github.tengigabytes.anomalops.core.imaging.align.Region
import io.github.tengigabytes.anomalops.core.imaging.align.Similarity
import io.github.tengigabytes.anomalops.core.imaging.align.TileAligner
import io.github.tengigabytes.anomalops.core.imaging.align.TileField
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs
import kotlin.math.max

/**
 * ADR-0017 step 4 on the phone: [GpuTileAligner] against `TileAligner`, given the same whole-frame transform, on a
 * noisy [AlignScene] seen through a known scale and shift, with one region that moved further on its own. The GPU
 * sums each cost in float and the CPU in double, so a near tie can pick another whole-pixel shift; the test counts
 * such tiles and compares the rest. Limits are proposals. Results are logged under [TAG] as `GPU` lines; times are
 * from this debuggable APK and only indicative.
 */
@RunWith(AndroidJUnit4::class)
class GpuTileAlignerTest {
    private val truth = Similarity(scale = 1.006f, dx = 13.4f, dy = -7.7f)
    private val moved = Region(MOVED_LEFT, MOVED_TOP, MOVED_RIGHT, MOVED_BOTTOM)
    private val reference = AlignScene.render(Similarity(), NOISE, noiseSeed = 2)
    private val frame = AlignScene.render(truth, NOISE, noiseSeed = 3, local = moved to (LOCAL_DX to LOCAL_DY))

    @Test
    fun tilesMatchTheCpu() {
        val t0 = System.nanoTime()
        val cpu = TileAligner().align(Pyramid(reference), Pyramid(frame), truth)
        val t1 = System.nanoTime()
        val (gpu, gpuMs) = GlesContext.create().use { gpuField() }
        val diff = Comparison(cpu, gpu)
        log("tiles: CPU %.0f ms, GPU %.1f ms (one frame, texture once before)".format((t1 - t0) / NS_PER_MS, gpuMs))
        log("tiles: $diff")
        log("tiles in the moved region, mean residual: CPU ${movedMean(cpu)}, GPU ${movedMean(gpu)}")
        val tiles = cpu.cols * cpu.rows
        assertTrue("other whole-pixel shift: $diff", diff.otherShift <= tiles * OTHER_SHIFT_SHARE)
        assertTrue("sub-pixel: $diff", diff.maxShiftDiff <= SHIFT_DIFF)
        assertTrue("trust: $diff", diff.trustMismatches <= tiles * OTHER_SHIFT_SHARE)
    }

    /** The GPU field and the time of one [GpuTileAligner.align] after a first call that measured the texture. */
    private fun gpuField(): Pair<TileField, Double> {
        val transfer = PlaneTransfer()
        val kernels = PlaneKernels()
        val ref = transfer.upload(reference, PlaneFormat.FLOAT32)
        val fr = transfer.upload(frame, PlaneFormat.FLOAT32)
        val refPyramid = GpuPyramid(ref, kernels)
        val frPyramid = GpuPyramid(fr, kernels, maxLevels = refPyramid.top)
        val aligner = GpuTileAligner()
        aligner.align(refPyramid, frPyramid, truth)
        val t0 = System.nanoTime()
        val field = aligner.align(refPyramid, frPyramid, truth)
        val ms = (System.nanoTime() - t0) / NS_PER_MS
        listOf<AutoCloseable>(aligner, frPyramid, refPyramid, fr, ref, kernels, transfer).forEach { it.close() }
        return field to ms
    }

    /** Tiles where the two pick another whole-pixel shift, and the largest difference among the others. */
    private class Comparison(cpu: TileField, gpu: TileField) {
        var otherShift = 0
        var maxShiftDiff = 0f
        var maxCostRel = 0f
        var maxTextureRel = 0f
        var trustMismatches = 0

        init {
            for (i in cpu.dx.indices) {
                if (cpu.trusted[i] != gpu.trusted[i]) trustMismatches++
                maxTextureRel = max(maxTextureRel, relative(cpu.texture[i], gpu.texture[i]))
                val d = max(abs(cpu.dx[i] - gpu.dx[i]), abs(cpu.dy[i] - gpu.dy[i]))
                if (d >= OTHER_SHIFT) {
                    otherShift++
                } else {
                    maxShiftDiff = max(maxShiftDiff, d)
                    maxCostRel = max(maxCostRel, relative(cpu.cost[i], gpu.cost[i]))
                }
            }
        }

        override fun toString(): String {
            val shifts = "%d tiles with another shift, else max shift diff %.3g px, max cost rel %.3g"
                .format(otherShift, maxShiftDiff, maxCostRel)
            return "$shifts; texture max rel %.3g; %d trust mismatches".format(maxTextureRel, trustMismatches)
        }
    }

    private fun movedMean(field: TileField): String {
        var sx = 0.0
        var sy = 0.0
        var n = 0
        for (row in 0 until field.rows) {
            for (col in 0 until field.cols) {
                val x = col * field.tileSize
                val y = row * field.tileSize
                val inside = x >= moved.left && x + field.tileSize <= moved.right &&
                    y >= moved.top && y + field.tileSize <= moved.bottom
                if (inside) {
                    sx += field.dx[field.index(col, row)]
                    sy += field.dy[field.index(col, row)]
                    n++
                }
            }
        }
        return "(%.3f, %.3f) over %d tiles".format(sx / n, sy / n, n)
    }

    private fun log(text: String) {
        Log.i(TAG, "GPU $text")
    }

    private companion object {
        const val TAG = "GpuTileAlignerTest"
        const val NS_PER_MS = 1e6
        const val NOISE = 40f
        const val MOVED_LEFT = 800
        const val MOVED_TOP = 608
        const val MOVED_RIGHT = 1120
        const val MOVED_BOTTOM = 800
        const val LOCAL_DX = 3.4f
        const val LOCAL_DY = -2.2f

        /** A difference this large means another whole-pixel shift was picked, not a rounding difference. */
        const val OTHER_SHIFT = 0.5f

        /** Proposed: at most 1 % of the tiles may pick another shift (a near tie) or another trust decision. */
        const val OTHER_SHIFT_SHARE = 0.01

        /** Proposed: the same shift's parabola may differ by float rounding only. */
        const val SHIFT_DIFF = 1e-3f

        fun relative(a: Float, b: Float) = if (a == b) 0f else abs(a - b) / max(abs(a), abs(b))
    }
}
