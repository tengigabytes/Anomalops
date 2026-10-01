// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.core.gpu

import android.opengl.GLES20
import android.opengl.GLES30
import android.opengl.GLES31
import io.github.tengigabytes.anomalops.core.imaging.align.Plane
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Moves planes between Java memory and the GPU. Upload goes through a 32-bit texture, converted on the GPU for
 * [PlaneFormat.HALF] (so the rounding is the shader store's, the same as every other half write); download copies
 * the texels into a storage buffer with texelFetch. Needs a current [GlesContext].
 */
class PlaneTransfer : AutoCloseable {
    private val toHalf = ComputeProgram(
        """
        ${Glsl.LOCAL_2D}
        layout(binding = 0) uniform highp sampler2D src;
        layout(rgba16f, binding = 0) writeonly uniform highp image2D dst;
        void main() {
            ivec2 p = ivec2(gl_GlobalInvocationID.xy);
            if (any(greaterThanEqual(p, imageSize(dst)))) return;
            imageStore(dst, p, vec4(texelFetch(src, p, 0).r));
        }
        """,
    )
    private val readBack = ComputeProgram(
        """
        ${Glsl.LOCAL_2D}
        layout(binding = 0) uniform highp sampler2D src;
        layout(std430, binding = 0) writeonly buffer Out { highp float values[]; };
        void main() {
            ivec2 p = ivec2(gl_GlobalInvocationID.xy);
            ivec2 size = textureSize(src, 0);
            if (any(greaterThanEqual(p, size))) return;
            values[p.y * size.x + p.x] = texelFetch(src, p, 0).r;
        }
        """,
    )
    private var staging: ByteBuffer = ByteBuffer.allocateDirect(0)

    fun upload(plane: Plane, format: PlaneFormat): GpuPlane {
        val float = GpuPlane(plane.width, plane.height, PlaneFormat.FLOAT32)
        val buffer = staging(plane.data.size)
        buffer.asFloatBuffer().put(plane.data)
        float.bindSampler(0)
        GLES20.glTexSubImage2D(
            GLES20.GL_TEXTURE_2D,
            0,
            0,
            0,
            plane.width,
            plane.height,
            GLES30.GL_RED,
            GLES20.GL_FLOAT,
            buffer,
        )
        checkGl("upload")
        if (format == PlaneFormat.FLOAT32) return float
        return GpuPlane(plane.width, plane.height, PlaneFormat.HALF).also { half ->
            float.bindSampler(0)
            half.bindImage(0, GLES31.GL_WRITE_ONLY)
            toHalf.use().dispatch(GpuPlane.groups(plane.width), GpuPlane.groups(plane.height))
            GLES31.glMemoryBarrier(GLES31.GL_TEXTURE_FETCH_BARRIER_BIT or GLES31.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT)
            float.close()
        }
    }

    fun download(plane: GpuPlane): Plane {
        val count = plane.width * plane.height
        val bytes = count * Float.SIZE_BYTES
        val buffer = IntArray(1).also { GLES20.glGenBuffers(1, it, 0) }[0]
        try {
            GLES20.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, buffer)
            GLES20.glBufferData(GLES31.GL_SHADER_STORAGE_BUFFER, bytes, null, GLES30.GL_STREAM_READ)
            GLES30.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 0, buffer)
            plane.bindSampler(0)
            GLES31.glMemoryBarrier(GLES31.GL_TEXTURE_FETCH_BARRIER_BIT)
            readBack.use().dispatch(GpuPlane.groups(plane.width), GpuPlane.groups(plane.height))
            GLES31.glMemoryBarrier(GLES31.GL_BUFFER_UPDATE_BARRIER_BIT)
            val mapped = GLES30.glMapBufferRange(GLES31.GL_SHADER_STORAGE_BUFFER, 0, bytes, GLES30.GL_MAP_READ_BIT)
            checkNotNull(mapped) { "glMapBufferRange failed" }
            val data = FloatArray(count)
            (mapped as ByteBuffer).order(ByteOrder.nativeOrder()).asFloatBuffer().get(data)
            GLES30.glUnmapBuffer(GLES31.GL_SHADER_STORAGE_BUFFER)
            checkGl("download")
            return Plane(plane.width, plane.height, data)
        } finally {
            GLES20.glDeleteBuffers(1, intArrayOf(buffer), 0)
        }
    }

    override fun close() {
        toHalf.close()
        readBack.close()
    }

    private fun staging(floats: Int): ByteBuffer {
        if (staging.capacity() < floats * Float.SIZE_BYTES) {
            staging = ByteBuffer.allocateDirect(floats * Float.SIZE_BYTES).order(ByteOrder.nativeOrder())
        }
        return staging.apply { clear() }
    }
}
