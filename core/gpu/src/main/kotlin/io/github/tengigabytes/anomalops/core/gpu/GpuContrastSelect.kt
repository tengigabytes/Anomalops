// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.core.gpu

import android.opengl.GLES31

/**
 * FR-33's candidate A (`ContrastSelectStack`) on the GPU (ADR-0017 step 5), in its two passes: the first keeps the
 * best focus measure and its frame per pixel, the second adds each frame's channels with its box-smoothed one-hot
 * weight. Box sums are float where the CPU's are double, so a pixel whose two best frames nearly tie may pick the
 * other. Needs a current [GlesContext].
 */
class GpuContrastSelect(private val focusRadius: Int = 4, private val blendRadius: Int = 6) : AutoCloseable {
    private val kernels = StackKernels()

    /** A bracket of [width] x [height] frames with [channels] planes each (at most [MAX_CHANNELS]). */
    fun start(width: Int, height: Int, channels: Int): GpuFocusAccumulator {
        require(channels in 1..MAX_CHANNELS) { "$channels channels" }
        return Accumulator(width, height, channels)
    }

    override fun close() = kernels.close()

    private inner class Accumulator(private val width: Int, private val height: Int, channels: Int) :
        GpuFocusAccumulator {
        override val passes = 2
        private fun plane() = GpuPlane(width, height, PlaneFormat.FLOAT32)
        private val rows = plane()
        private val scratch = plane()
        private val measure = plane()
        private val best = plane()
        private val choice = plane()
        private val total = plane()
        private val sums = List(channels) { plane() }
        private var pass = 0
        private var frames = 0
        private var added = 0

        override fun add(luma: GpuPlane, channels: List<GpuPlane>) {
            require(luma.width == width && luma.height == height && channels.size == sums.size) { "frame shape" }
            if (pass == 0) score(luma) else blend(channels)
        }

        /** First pass: `focusMeasures`, then the best so far and its frame. */
        private fun score(luma: GpuPlane) {
            kernels.box(luma, 1, rows, scratch)
            kernels.laplacian(scratch, measure)
            kernels.box(measure, focusRadius, rows, scratch, StackShaders.READ_SQUARE)
            scratch.bindSampler(0)
            best.bindImage(0, GLES31.GL_READ_WRITE)
            choice.bindImage(1, GLES31.GL_READ_WRITE)
            kernels.select.use().uniform("first", if (frames == 0) 1 else 0).uniform("frame", frames.toFloat())
                .run(best)
            frames++
        }

        /** The weights' per-pixel total, in frame order. */
        override fun endPass() {
            check(pass == 0 && frames > 0) { "endPass after pass $pass with $frames frames" }
            for (k in 0 until frames) {
                weight(k)
                kernels.add(measure, total, first = k == 0)
            }
            pass = 1
        }

        /** Second pass: each frame's channels times its normalised weight, in frame order. */
        private fun blend(channels: List<GpuPlane>) {
            check(added < frames) { "more frames in the second pass than the first ($frames)" }
            weight(added)
            measure.bindSampler(0)
            total.bindSampler(1)
            channels.forEachIndexed { c, plane -> plane.bindSampler(c + 2) }
            sums.forEachIndexed { c, plane -> plane.bindImage(c, GLES31.GL_READ_WRITE) }
            kernels.blend(sums.size).use()
                .uniform("first", if (added == 0) 1 else 0)
                .uniform("frames", frames.toFloat())
                .run(sums[0])
            added++
        }

        /** Frame [k]'s weight, the box-smoothed one-hot map of its choice, into [measure]. */
        private fun weight(k: Int) = kernels.box(choice, blendRadius, rows, measure, StackShaders.READ_ONE_HOT, k)

        override fun finish(): List<GpuPlane> {
            check(pass == 1 && added == frames) { "second pass had $added of $frames frames" }
            listOf(rows, scratch, measure, best, choice, total).forEach { it.close() }
            pass = 2
            return sums
        }

        override fun close() {
            if (pass < 2) (listOf(rows, scratch, measure, best, choice, total) + sums).forEach { it.close() }
        }
    }

    companion object {
        /** Image units used by the blend: one sum per channel (R, G, B and the luma riding along for the guard). */
        const val MAX_CHANNELS = 4
    }
}
