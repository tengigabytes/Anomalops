// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.core.gpu

import io.github.tengigabytes.anomalops.core.imaging.align.Pyramid

/**
 * `Pyramid` on the GPU, with the same levels: level 0 is the base plane, each level above halves it with
 * [PlaneKernels.half] until the next would be smaller than [minSize] on its short side or [maxLevels] is reached.
 * [rebuild] reuses the upper levels' planes for the next frame of the same size, since allocating a texture per call
 * is slow (docs/test/m9-gpu-fr17.md). The base plane stays the caller's; [close] frees the levels above it. Needs a
 * current [GlesContext].
 */
class GpuPyramid(
    base: GpuPlane,
    private val kernels: PlaneKernels,
    minSize: Int = Pyramid.DEFAULT_MIN_SIZE,
    maxLevels: Int = Pyramid.DEFAULT_MAX_LEVELS,
) : AutoCloseable {
    private var base = base
    private val upper = ArrayList<GpuPlane>()

    init {
        var last = base
        while (upper.size + 1 <= maxLevels && minOf(last.width, last.height) / 2 >= minSize) {
            last = kernels.half(last)
            upper += last
        }
    }

    val top: Int get() = upper.size

    operator fun get(level: Int): GpuPlane = if (level == 0) base else upper[level - 1]

    /** The pyramid of [plane] (the size and format of the first base) in the same planes. */
    fun rebuild(plane: GpuPlane) {
        require(plane.width == base.width && plane.height == base.height && plane.format == base.format) {
            "base ${plane.width}x${plane.height} ${plane.format}, pyramid of ${base.width}x${base.height} ${base.format}"
        }
        base = plane
        var last = plane
        upper.forEach {
            kernels.half(last, into = it)
            last = it
        }
    }

    override fun close() = upper.forEach { it.close() }
}
