// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.probe

import android.opengl.GLES20
import android.opengl.GLES30
import android.opengl.GLES31
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Small compute-shader helpers for [GlesFormatProbe]: every test works on one SIZE x SIZE texture whose texel i
 * holds `i * 15.9921875 + 0.3` (0.3 … 4078.3, the 12-bit RAW range). The product is exact in 32-bit float, so
 * the GPU (with or without fused multiply-add) and the CPU round the same way, and the fractions land at varied
 * offsets from the half-precision grid. Needs a current [GlContext].
 */
internal object GlCompute {
    const val SIZE = 16
    const val COUNT = SIZE * SIZE
    private const val LOCAL = 8
    private const val STEP = 15.9921875f
    private const val OFFSET = 0.3f
    private const val BYTES = COUNT * Float.SIZE_BYTES

    /** GLSL for the test value of a texel; [expected] is the same value on the CPU. */
    val VALUE_GLSL = "float value(ivec2 p) { return float(p.x + p.y * $SIZE) * $STEP + $OFFSET; }"
    private val HEADER = "#version 310 es\nlayout(local_size_x = $LOCAL, local_size_y = $LOCAL) in;\n"

    fun expected(index: Int): Float = index * STEP + OFFSET

    class CompileException(log: String) : Exception(log)

    fun program(body: String): Int {
        val shader = GLES20.glCreateShader(GLES31.GL_COMPUTE_SHADER)
        GLES20.glShaderSource(shader, HEADER + body.trimIndent())
        GLES20.glCompileShader(shader)
        if (status(shader, GLES20.GL_COMPILE_STATUS, GLES20::glGetShaderiv) == 0) {
            val log = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            throw CompileException(log)
        }
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, shader)
        GLES20.glLinkProgram(program)
        GLES20.glDeleteShader(shader)
        if (status(program, GLES20.GL_LINK_STATUS, GLES20::glGetProgramiv) == 0) {
            val log = GLES20.glGetProgramInfoLog(program)
            GLES20.glDeleteProgram(program)
            throw CompileException(log)
        }
        return program
    }

    fun dispatch(program: Int) {
        GLES20.glUseProgram(program)
        GLES31.glDispatchCompute(SIZE / LOCAL, SIZE / LOCAL, 1)
    }

    /** An immutable single-level texture with nearest filtering (float32 and integer textures need it). */
    fun texture(internalFormat: Int): Int {
        val texture = IntArray(1).also { GLES20.glGenTextures(1, it, 0) }[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLES30.glTexStorage2D(GLES20.GL_TEXTURE_2D, 1, internalFormat, SIZE, SIZE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
        return texture
    }

    fun deleteTexture(texture: Int) = GLES20.glDeleteTextures(1, intArrayOf(texture), 0)

    /** Copies channel R of every texel into a shader storage buffer with texelFetch and returns its bytes. */
    fun readBack(texture: Int, integer: Boolean): ByteBuffer {
        val sampler = if (integer) "usampler2D" else "sampler2D"
        val type = if (integer) "uint" else "float"
        val program = program(
            """
            layout(binding = 0) uniform highp $sampler src;
            layout(std430, binding = 0) writeonly buffer Out { highp $type values[]; };
            void main() {
                ivec2 p = ivec2(gl_GlobalInvocationID.xy);
                values[p.x + p.y * $SIZE] = texelFetch(src, p, 0).r;
            }
            """,
        )
        val buffer = IntArray(1).also { GLES20.glGenBuffers(1, it, 0) }[0]
        try {
            GLES20.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, buffer)
            GLES20.glBufferData(GLES31.GL_SHADER_STORAGE_BUFFER, BYTES, null, GLES30.GL_STATIC_READ)
            GLES30.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 0, buffer)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
            GLES31.glMemoryBarrier(GLES31.GL_TEXTURE_FETCH_BARRIER_BIT)
            dispatch(program)
            GLES31.glMemoryBarrier(GLES31.GL_BUFFER_UPDATE_BARRIER_BIT)
            val mapped = GLES30.glMapBufferRange(GLES31.GL_SHADER_STORAGE_BUFFER, 0, BYTES, GLES30.GL_MAP_READ_BIT)
            checkNotNull(mapped) { "glMapBufferRange failed: ${hex(GLES20.glGetError())}" }
            val copy = ByteBuffer.allocate(BYTES).order(ByteOrder.nativeOrder())
            copy.put((mapped as ByteBuffer).order(ByteOrder.nativeOrder())).flip()
            GLES30.glUnmapBuffer(GLES31.GL_SHADER_STORAGE_BUFFER)
            return copy
        } finally {
            GLES20.glDeleteBuffers(1, intArrayOf(buffer), 0)
            GLES20.glDeleteProgram(program)
        }
    }

    /** Drops errors left by an earlier test so each test reports only its own. */
    fun clearErrors() {
        do {
            val error = GLES20.glGetError()
        } while (error != GLES20.GL_NO_ERROR)
    }

    fun hex(value: Int): String = "0x" + Integer.toHexString(value)

    private fun status(id: Int, name: Int, query: (Int, Int, IntArray, Int) -> Unit): Int =
        IntArray(1).also { query(id, name, it, 0) }[0]
}
