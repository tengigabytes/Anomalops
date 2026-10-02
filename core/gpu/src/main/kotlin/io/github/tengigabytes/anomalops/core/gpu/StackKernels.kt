// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.core.gpu

import android.opengl.GLES31

/**
 * A focus stack fed one frame at a time on the GPU, the counterpart of `FocusAccumulator`: [add] every frame of the
 * bracket in order (aligned, no missing pixels), [passes] times with [endPass] between passes, then [finish] once.
 * The planes given to [add] may be reused by the caller afterwards. [finish] hands back new planes the caller
 * closes; [close] frees the rest (also when a bracket is abandoned).
 */
interface GpuFocusAccumulator : AutoCloseable {
    val passes: Int get() = 1

    fun add(luma: GpuPlane, channels: List<GpuPlane>)

    fun endPass() {}

    fun finish(): List<GpuPlane>
}

/** The plane operations FR-33's candidates share ([StackShaders]); every output is FLOAT32. */
internal class StackKernels : AutoCloseable {
    private val boxRows = ComputeProgram(StackShaders.BOX_ROWS)
    private val boxColumns = ComputeProgram(StackShaders.BOX_COLUMNS)
    private val laplacian = ComputeProgram(StackShaders.LAPLACIAN)
    private val add = ComputeProgram(StackShaders.ADD)
    private val band = ComputeProgram(StackShaders.BAND)
    private val scale = ComputeProgram(StackShaders.SCALE)
    val select = ComputeProgram(StackShaders.SELECT)
    val mark = ComputeProgram(StackShaders.MARK)
    val copyWhere = ComputeProgram(StackShaders.COPY_WHERE)
    private val blends = HashMap<Int, ComputeProgram>()

    /** `Filters.box` of [src] read as [mode] (one-hot against [frame]) into [dst], through [rows]. */
    @Suppress("LongParameterList") // The filter's input, its two outputs and how to read the input.
    fun box(
        src: GpuPlane,
        radius: Int,
        rows: GpuPlane,
        dst: GpuPlane,
        mode: Int = StackShaders.READ_PLAIN,
        frame: Int = 0,
    ) {
        src.bindSampler(0)
        rows.bindImage(0, GLES31.GL_WRITE_ONLY)
        boxRows.use().uniform("radius", radius).uniform("mode", mode).uniform("frame", frame.toFloat()).run(dst)
        rows.bindSampler(0)
        dst.bindImage(0, GLES31.GL_WRITE_ONLY)
        boxColumns.use().uniform("radius", radius).run(dst)
    }

    fun laplacian(src: GpuPlane, dst: GpuPlane) {
        src.bindSampler(0)
        dst.bindImage(0, GLES31.GL_WRITE_ONLY)
        laplacian.use().run(dst)
    }

    /** dst += src, or dst = src when [first]. */
    fun add(src: GpuPlane, dst: GpuPlane, first: Boolean) {
        src.bindSampler(0)
        dst.bindImage(0, GLES31.GL_READ_WRITE)
        add.use().uniform("first", if (first) 1 else 0).run(dst)
    }

    /** dst = fine - double(coarse), or with [collapse] fine + double(coarse). */
    fun band(fine: GpuPlane, coarse: GpuPlane, dst: GpuPlane, collapse: Boolean = false) {
        fine.bindSampler(0)
        coarse.bindSampler(1)
        dst.bindImage(0, GLES31.GL_WRITE_ONLY)
        band.use().uniform("collapse", if (collapse) 1 else 0).run(dst)
    }

    fun divide(src: GpuPlane, divisor: Float, dst: GpuPlane) {
        src.bindSampler(0)
        dst.bindImage(0, GLES31.GL_WRITE_ONLY)
        scale.use().uniform("divisor", divisor).run(dst)
    }

    fun blend(channels: Int): ComputeProgram = blends.getOrPut(
        channels,
    ) { ComputeProgram(StackShaders.blend(channels)) }

    override fun close() {
        listOf(boxRows, boxColumns, laplacian, add, band, scale, select, mark, copyWhere).forEach { it.close() }
        blends.values.forEach { it.close() }
    }
}

/** Dispatches over [plane] and waits for its writes before the next kernel reads it. */
internal fun ComputeProgram.run(plane: GpuPlane) {
    dispatch(GpuPlane.groups(plane.width), GpuPlane.groups(plane.height))
    GLES31.glMemoryBarrier(GLES31.GL_TEXTURE_FETCH_BARRIER_BIT or GLES31.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT)
}
