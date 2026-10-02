// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.core.gpu

import android.opengl.GLES31

/**
 * FR-33's candidate B (`LaplacianPyramidStack`) on the GPU (ADR-0017 step 5), one pass: per band level the best
 * luma score so far and each channel's coefficient from that frame; the coarsest level sums every frame; [finish]
 * takes the mean there and collapses the pyramid. Box sums and the coarsest sum are float where the CPU's are
 * double, so a near tie may pick the other frame. Needs a current [GlesContext].
 */
class GpuLaplacianPyramid(
    private val maxLevels: Int = 7,
    private val minSize: Int = 8,
    private val selectRadius: Int = 1,
) : AutoCloseable {
    private val kernels = PlaneKernels()
    private val stack = StackKernels()

    /** A bracket of [width] x [height] frames with [channels] planes each. */
    fun start(width: Int, height: Int, channels: Int): GpuFocusAccumulator {
        require(channels >= 1) { "$channels channels" }
        return Accumulator(levelSizes(width, height), channels)
    }

    override fun close() {
        kernels.close()
        stack.close()
    }

    /** The Gaussian levels' sizes, as `LaplacianPyramidStack.bands` halves them. */
    private fun levelSizes(width: Int, height: Int): List<Pair<Int, Int>> = buildList {
        add(width to height)
        while (size < maxLevels && minOf(last().first, last().second) / 2 >= minSize) {
            add(last().first / 2 to last().second / 2)
        }
    }

    private inner class Accumulator(private val sizes: List<Pair<Int, Int>>, channels: Int) : GpuFocusAccumulator {
        private val bandLevels = sizes.size - 1
        private fun planes() = sizes.map { (w, h) -> GpuPlane(w, h, PlaneFormat.FLOAT32) }
        private val gaussian = planes()
        private val band = planes()
        private val rows = planes()
        private val score = planes()
        private val best = planes()
        private val mask = planes()
        private val picked = List(channels) { planes() }
        private var frames = 0
        private var finished = false

        override fun add(luma: GpuPlane, channels: List<GpuPlane>) {
            require(channels.size == picked.size && luma.width == sizes[0].first) { "frame shape" }
            val first = frames == 0
            gaussianOf(luma)
            for (l in 0 until bandLevels) {
                stack.band(gaussian[l], gaussian[l + 1], band[l])
                stack.box(band[l], selectRadius, rows[l], score[l], StackShaders.READ_ABS)
                score[l].bindSampler(0)
                best[l].bindImage(0, GLES31.GL_READ_WRITE)
                mask[l].bindImage(1, GLES31.GL_WRITE_ONLY)
                stack.mark.use().uniform("first", if (first) 1 else 0).run(best[l])
            }
            channels.forEachIndexed { c, channel ->
                gaussianOf(channel)
                for (l in 0 until bandLevels) {
                    stack.band(gaussian[l], gaussian[l + 1], band[l])
                    mask[l].bindSampler(0)
                    band[l].bindSampler(1)
                    picked[c][l].bindImage(0, GLES31.GL_WRITE_ONLY)
                    stack.copyWhere.use().run(picked[c][l])
                }
                stack.add(gaussian[bandLevels], picked[c][bandLevels], first)
            }
            frames++
        }

        /** Each channel's mean coarsest level, then the picked bands collapsed onto it, into new planes. */
        override fun finish(): List<GpuPlane> {
            check(frames > 0 && !finished) { "finish with $frames frames" }
            val out = picked.map { pyramid ->
                val top = if (bandLevels == 0) fullSize() else gaussian[bandLevels]
                stack.divide(pyramid[bandLevels], frames.toFloat(), top)
                var image = top
                for (l in bandLevels - 1 downTo 0) {
                    val target = if (l == 0) fullSize() else gaussian[l]
                    stack.band(pyramid[l], image, target, collapse = true)
                    image = target
                }
                image
            }
            close()
            return out
        }

        private fun fullSize() = GpuPlane(sizes[0].first, sizes[0].second, PlaneFormat.FLOAT32)

        /** [p]'s Gaussian pyramid into [gaussian]; level 0 is a copy so the caller may reuse [p]. */
        private fun gaussianOf(p: GpuPlane) {
            stack.add(p, gaussian[0], first = true)
            for (l in 1 until sizes.size) kernels.half(gaussian[l - 1], into = gaussian[l])
        }

        override fun close() {
            if (finished) return
            finished = true
            (gaussian + band + rows + score + best + mask + picked.flatten()).forEach { it.close() }
        }
    }
}
