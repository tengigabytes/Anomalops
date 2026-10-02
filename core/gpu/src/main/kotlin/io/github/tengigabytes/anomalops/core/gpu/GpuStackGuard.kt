// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.core.gpu

import android.opengl.GLES20
import android.opengl.GLES30
import android.opengl.GLES31
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * `StackGuard` of `:core:imaging` on the GPU, around a candidate made by [stack] for (width, height, channels):
 * missing pixels (NaN from alignment) are filled from the reference, the luma rides along as one more channel,
 * and the merge is kept only when it is at least as sharp as the sharpest single frame. Sharpness sums are float
 * per work group and double on the CPU, so a near tie between the merge and the best frame can go the other way.
 * Needs a current [GlesContext].
 */
class GpuStackGuard(private val stack: (Int, Int, Int) -> GpuFocusAccumulator) : AutoCloseable {
    private val fill = ComputeProgram(GuardShaders.FILL)
    private val sums = ComputeProgram(GuardShaders.SHARPNESS)
    private val kernels = StackKernels()
    private val partials = IntArray(1).also { GLES20.glGenBuffers(1, it, 0) }[0]
    private var partialBytes = 0

    /** [merged] when the merge was kept; [bestFrame] the sharpest single frame, whose planes are used when not. */
    class Result(
        val merged: Boolean,
        val bestFrame: Int,
        val sharpness: Double,
        val bestSingle: Double,
        val channels: List<GpuPlane>,
    )

    /**
     * A bracket whose reference is [referenceLuma] and [referenceChannels] (the caller's, kept until [Session.finish]);
     * the reference is also added at its place in the bracket like any other frame.
     */
    fun start(referenceLuma: GpuPlane, referenceChannels: List<GpuPlane>): Session =
        Session(referenceLuma, referenceChannels)

    /** `StackGuard.sharpness`: the variance of the Laplacian without a 5 % border. */
    fun sharpness(p: GpuPlane): Double {
        val mx = (p.width * MARGIN).toInt()
        val my = (p.height * MARGIN).toInt()
        val rows = p.height - 2 * my
        val bytes = rows * VEC2_BYTES
        GLES20.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, partials)
        if (bytes > partialBytes) {
            GLES20.glBufferData(GLES31.GL_SHADER_STORAGE_BUFFER, bytes, null, GLES30.GL_STREAM_READ)
            partialBytes = bytes
        }
        GLES30.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 0, partials)
        p.bindSampler(0)
        sums.use().uniform("region", mx, my, p.width - mx, p.height - my).dispatch(rows)
        GLES31.glMemoryBarrier(GLES31.GL_BUFFER_UPDATE_BARRIER_BIT)
        val mapped = GLES30.glMapBufferRange(GLES31.GL_SHADER_STORAGE_BUFFER, 0, bytes, GLES30.GL_MAP_READ_BIT)
        checkNotNull(mapped) { "glMapBufferRange failed" }
        val values = (mapped as ByteBuffer).order(ByteOrder.nativeOrder()).asFloatBuffer()
        var sum = 0.0
        var squares = 0.0
        for (r in 0 until rows) {
            sum += values.get(2 * r)
            squares += values.get(2 * r + 1)
        }
        GLES30.glUnmapBuffer(GLES31.GL_SHADER_STORAGE_BUFFER)
        checkGl("sharpness")
        val n = rows.toDouble() * (p.width - 2 * mx)
        val mean = sum / n
        return squares / n - mean * mean
    }

    override fun close() {
        listOf(fill, sums, kernels).forEach { it.close() }
        GLES20.glDeleteBuffers(1, intArrayOf(partials), 0)
    }

    inner class Session internal constructor(
        private val referenceLuma: GpuPlane,
        private val referenceChannels: List<GpuPlane>,
    ) : AutoCloseable {
        private val width = referenceLuma.width
        private val height = referenceLuma.height
        private val accumulator = stack(width, height, referenceChannels.size + 1)
        private fun plane() = GpuPlane(width, height, PlaneFormat.FLOAT32)
        private val filledLuma = plane()
        private val filledChannels = referenceChannels.map { plane() }
        private val bestChannels = referenceChannels.map { plane() }
        private var frames = 0
        private var bestFrame = -1
        private var bestSingle = Double.NaN
        private var pass = 0
        private var open = true

        /** How many times the bracket has to be added (the candidate's [GpuFocusAccumulator.passes]). */
        val passes: Int get() = accumulator.passes

        fun add(luma: GpuPlane, channels: List<GpuPlane>) {
            require(channels.size == filledChannels.size) { "${channels.size} channels" }
            fill(luma, referenceLuma, filledLuma)
            channels.forEachIndexed { c, p -> fill(p, referenceChannels[c], filledChannels[c]) }
            if (pass == 0) {
                val single = sharpness(filledLuma)
                // Strictly better, so ties keep the earlier frame.
                if (bestFrame < 0 || bestSingle < single) {
                    bestFrame = frames
                    bestSingle = single
                    filledChannels.forEachIndexed { c, p -> kernels.add(p, bestChannels[c], first = true) }
                }
                frames++
            }
            accumulator.add(filledLuma, filledChannels + filledLuma)
        }

        fun endPass() {
            accumulator.endPass()
            pass++
        }

        /** The kept merge or the best frame's planes, which pass to the caller (to close). */
        fun finish(): Result {
            val merged = accumulator.finish()
            val score = sharpness(merged.last())
            val kept = score >= bestSingle
            val result = if (kept) merged.dropLast(1) else bestChannels
            (if (kept) bestChannels + merged.last() else merged).forEach { it.close() }
            open = false
            (filledChannels + filledLuma).forEach { it.close() }
            accumulator.close()
            return Result(kept, bestFrame, score, bestSingle, result)
        }

        override fun close() {
            if (!open) return
            open = false
            (filledChannels + filledLuma + bestChannels).forEach { it.close() }
            accumulator.close()
        }

        private fun fill(frame: GpuPlane, reference: GpuPlane, dst: GpuPlane) {
            frame.bindSampler(0)
            reference.bindSampler(1)
            dst.bindImage(0, GLES31.GL_WRITE_ONLY)
            fill.use().run(dst)
        }
    }

    private companion object {
        const val MARGIN = 0.05f
        const val VEC2_BYTES = 8
    }
}
