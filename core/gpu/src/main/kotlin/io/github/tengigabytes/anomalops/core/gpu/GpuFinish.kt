// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.core.gpu

import android.opengl.GLES20
import android.opengl.GLES30
import android.opengl.GLES31
import io.github.tengigabytes.anomalops.core.imaging.develop.ChromaDenoise
import io.github.tengigabytes.anomalops.core.imaging.develop.RenderOptions
import io.github.tengigabytes.anomalops.core.imaging.develop.Sharpen
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * `Render.finish` on the GPU: the rendered picture, packed ARGB in a storage buffer, through `ChromaDenoise`'s
 * passes and `Sharpen`, each kernel reading one buffer and writing the other. Needs a current [GlesContext].
 */
internal class GpuFinish : AutoCloseable {
    private val chroma = ComputeProgram(FinishShaders.CHROMA)
    private val sharpen = ComputeProgram(FinishShaders.SHARPEN)
    private val buffers = IntArray(BUFFERS).also { GLES20.glGenBuffers(BUFFERS, it, 0) }

    init {
        val weights = ChromaDenoise.rangeWeights
        val data = ByteBuffer.allocateDirect(weights.size * Int.SIZE_BYTES).order(ByteOrder.nativeOrder())
        data.asIntBuffer().put(weights)
        GLES20.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, buffers[RANGE])
        GLES20.glBufferData(GLES31.GL_SHADER_STORAGE_BUFFER, data.capacity(), data, GLES20.GL_STATIC_DRAW)
    }

    /**
     * Runs what [options] ask for on the [width] x [height] picture in buffer [picture] and returns the buffer
     * that holds the result: [picture] itself when nothing was asked, else it or this object's work buffer.
     * Storage bindings 0 to 2 are left bound to this object's buffers.
     */
    fun run(picture: Int, width: Int, height: Int, options: RenderOptions): Int {
        val amount = Sharpen.quantise(options.sharpen)
        if (options.chromaPasses == 0 && amount == 0) return picture
        GLES20.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, buffers[WORK])
        val bytes = width * height * Int.SIZE_BYTES
        GLES20.glBufferData(GLES31.GL_SHADER_STORAGE_BUFFER, bytes, null, GLES30.GL_STREAM_READ)
        GLES30.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, RANGE_BINDING, buffers[RANGE])
        var source = picture
        var target = buffers[WORK]
        fun step(program: ComputeProgram, name: String, value: Int) {
            GLES31.glMemoryBarrier(GLES31.GL_SHADER_STORAGE_BARRIER_BIT)
            GLES30.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 0, source)
            GLES30.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 1, target)
            program.use()
                .uniform("size", width, height)
                .uniform(name, value)
                .dispatch(GpuPlane.groups(width), GpuPlane.groups(height))
            source = target.also { target = source }
        }
        for (pass in 0 until options.chromaPasses) step(chroma, "spacing", ChromaDenoise.spacing(pass))
        if (amount > 0) step(sharpen, "amount", amount)
        checkGl("GpuFinish.run")
        return source
    }

    override fun close() {
        chroma.close()
        sharpen.close()
        GLES20.glDeleteBuffers(BUFFERS, buffers, 0)
    }

    private companion object {
        const val WORK = 0
        const val RANGE = 1
        const val BUFFERS = 2
        const val RANGE_BINDING = 2
    }
}
