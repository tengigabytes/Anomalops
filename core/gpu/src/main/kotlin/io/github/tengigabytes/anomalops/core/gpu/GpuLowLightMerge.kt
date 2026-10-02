// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.core.gpu

import android.opengl.GLES20
import android.opengl.GLES30
import android.opengl.GLES31
import io.github.tengigabytes.anomalops.core.imaging.align.AlignOptions
import io.github.tengigabytes.anomalops.core.imaging.align.Alignment
import io.github.tengigabytes.anomalops.core.imaging.align.Similarity
import io.github.tengigabytes.anomalops.core.imaging.align.TileField
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * `LowLightMerge` of `:core:imaging` on the GPU (FR-17, ADR-0017 step 4), one frame at a time: each frame is
 * aligned ([GpuGlobalAligner], [GpuTileAligner]), its difference to the reference is weighted, and the weighted
 * channels are added to running sums kept on the GPU. Only the reference, the sums and the frame being added are
 * held. [robustness] as on the CPU. Needs a current [GlesContext].
 */
class GpuLowLightMerge(private val options: AlignOptions = AlignOptions(), private val robustness: Float = 4f) :
    AutoCloseable {
    private val kernels = PlaneKernels()
    private val globalAligner = GpuGlobalAligner(options)
    private val tileAligner = GpuTileAligner(options)
    private val diffProgram = ComputeProgram(MergeShaders.DIFF)
    private val copyProgram = ComputeProgram(MergeShaders.COPY)
    private val divideProgram = ComputeProgram(MergeShaders.DIVIDE)
    private val accumulatePrograms = HashMap<Int, ComputeProgram>()

    /**
     * Starts a burst with [referenceLuma] and its [referenceChannels] (each the luma's size; at most
     * [MAX_CHANNELS]); [noiseSigma] is the reference luma's noise standard deviation (the sensor's noise profile,
     * or `LowLightMerge.estimateNoise`). The planes stay the caller's and must live until [Accumulator.finish].
     */
    fun start(referenceLuma: GpuPlane, referenceChannels: List<GpuPlane>, noiseSigma: Float): Accumulator =
        Accumulator(referenceLuma, referenceChannels, noiseSigma)

    override fun close() {
        listOf(kernels, globalAligner, tileAligner, diffProgram, copyProgram, divideProgram).forEach { it.close() }
        accumulatePrograms.values.forEach { it.close() }
    }

    /** Running weighted sums of one burst on the GPU; the reference counts with weight 1 everywhere. */
    inner class Accumulator internal constructor(
        private val refLuma: GpuPlane,
        referenceChannels: List<GpuPlane>,
        sigma: Float,
    ) : AutoCloseable {
        private val width = refLuma.width
        private val height = refLuma.height
        private val allowance = robustness * sigma * sigma
        private val refPyramid = GpuPyramid(refLuma, kernels)
        private var framePyramid: GpuPyramid? = null
        private val diff = GpuPlane(width, height, PlaneFormat.FLOAT32)
        private val weight = GpuPlane(width, height, PlaneFormat.FLOAT32)
        private val sums = referenceChannels.map { GpuPlane(width, height, PlaneFormat.FLOAT32) }
        private val tileBuffer = IntArray(1).also { GLES20.glGenBuffers(1, it, 0) }[0]
        private val accumulate: ComputeProgram

        init {
            require(referenceChannels.size in 1..MAX_CHANNELS) { "${referenceChannels.size} channels" }
            referenceChannels.forEach { requireSize(it) }
            accumulate = accumulatePrograms.getOrPut(sums.size) { ComputeProgram(MergeShaders.accumulate(sums.size)) }
            referenceChannels.forEachIndexed { c, plane -> copy(plane, sums[c]) }
            copy(refLuma, weight, fill = 1f)
        }

        /** Aligns one frame to the reference and adds it; returns the alignment. */
        fun add(luma: GpuPlane, channels: List<GpuPlane>): Alignment {
            require(channels.size == sums.size) { "${channels.size} channels, the reference has ${sums.size}" }
            requireSize(luma)
            channels.forEach { requireSize(it) }
            val pyramid = framePyramid?.also { it.rebuild(luma) }
                ?: GpuPyramid(luma, kernels, maxLevels = refPyramid.top).also { framePyramid = it }
            val global = globalAligner.align(refPyramid, pyramid)
            val alignment = Alignment(global, tileAligner.align(refPyramid, pyramid, global))
            uploadTiles(alignment.tiles)
            diffProgram.use().position(global, alignment.tiles)
            luma.bindSampler(0)
            refLuma.bindSampler(1)
            diff.bindImage(0, GLES31.GL_WRITE_ONLY)
            diffProgram.dispatch(GpuPlane.groups(width), GpuPlane.groups(height))
            GLES31.glMemoryBarrier(GLES31.GL_TEXTURE_FETCH_BARRIER_BIT)
            accumulate.use().position(global, alignment.tiles).uniform("allowance", allowance)
            diff.bindSampler(0)
            channels.forEachIndexed { c, plane -> plane.bindSampler(c + 1) }
            weight.bindImage(0, GLES31.GL_READ_WRITE)
            sums.forEachIndexed { c, plane -> plane.bindImage(c + 1, GLES31.GL_READ_WRITE) }
            accumulate.dispatch(GpuPlane.groups(width), GpuPlane.groups(height))
            GLES31.glMemoryBarrier(GLES31.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT or GLES31.GL_TEXTURE_FETCH_BARRIER_BIT)
            checkGl("GpuLowLightMerge.add")
            return alignment
        }

        /**
         * The merged channels, divided in place in the sums' planes, which pass to the caller (to close); the
         * accumulator frees the rest and should not be used afterwards.
         */
        fun finish(): List<GpuPlane> {
            weight.bindSampler(0)
            divideProgram.use()
            sums.forEach {
                it.bindImage(0, GLES31.GL_READ_WRITE)
                divideProgram.dispatch(GpuPlane.groups(width), GpuPlane.groups(height))
                GLES31.glMemoryBarrier(GLES31.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT)
            }
            GLES31.glMemoryBarrier(GLES31.GL_TEXTURE_FETCH_BARRIER_BIT)
            release()
            return sums
        }

        /** Frees everything, the sums included (a burst abandoned before [finish]). */
        override fun close() {
            release()
            sums.forEach { it.close() }
        }

        private fun release() {
            listOfNotNull(refPyramid, framePyramid, diff, weight).forEach { it.close() }
            GLES20.glDeleteBuffers(1, intArrayOf(tileBuffer), 0)
        }

        private fun requireSize(plane: GpuPlane) = require(plane.width == width && plane.height == height) {
            "plane ${plane.width}x${plane.height}, the reference is ${width}x$height"
        }

        private fun copy(src: GpuPlane, dst: GpuPlane, fill: Float? = null) {
            src.bindSampler(0)
            dst.bindImage(0, GLES31.GL_WRITE_ONLY)
            copyProgram.use()
                .uniform("fill", if (fill != null) 1 else 0)
                .uniform("value", fill ?: 0f)
                .dispatch(GpuPlane.groups(width), GpuPlane.groups(height))
            GLES31.glMemoryBarrier(GLES31.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT or GLES31.GL_TEXTURE_FETCH_BARRIER_BIT)
        }

        /** The tile residuals as (dx, dy) per tile in buffer 0. */
        private fun uploadTiles(tiles: TileField) {
            val n = maxOf(tiles.cols * tiles.rows, 1)
            val data = ByteBuffer.allocateDirect(n * VEC2_BYTES).order(ByteOrder.nativeOrder())
            for (i in 0 until tiles.cols * tiles.rows) data.putFloat(tiles.dx[i]).putFloat(tiles.dy[i])
            data.position(0)
            GLES20.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, tileBuffer)
            GLES20.glBufferData(GLES31.GL_SHADER_STORAGE_BUFFER, n * VEC2_BYTES, data, GLES20.GL_DYNAMIC_DRAW)
            GLES30.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 0, tileBuffer)
        }

        private fun ComputeProgram.position(global: Similarity, tiles: TileField): ComputeProgram {
            val u = LevelUniforms(global, 0, width, height)
            return uniform("centre", u.centreX, u.centreY)
                .uniform("scale", u.scale)
                .uniform("shift", u.dx, u.dy)
                .uniform("tileSize", tiles.tileSize)
                .uniform("grid", tiles.cols, tiles.rows)
        }
    }

    companion object {
        /** Image units used per frame: the weight and one sum per channel (R, G, B, or a single luma). */
        const val MAX_CHANNELS = 3
        private const val VEC2_BYTES = 8
    }
}
