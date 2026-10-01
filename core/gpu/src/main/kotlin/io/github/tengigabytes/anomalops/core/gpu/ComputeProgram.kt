// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.core.gpu

import android.opengl.GLES20
import android.opengl.GLES31

/**
 * A compiled compute shader. [source] is the body after the header: GLSL ES 3.10 with `GL_EXT_gpu_shader5` for the
 * `precise` qualifier, which keeps the GPU from fusing a multiply and an add where the CPU reference rounds twice
 * (without it, a warp's sample positions differed from the CPU's by one float step in about a third of the
 * pixels). Needs a current [GlesContext].
 */
class ComputeProgram(source: String) : AutoCloseable {
    private val program: Int = link(compile(HEADER + source.trimIndent()))
    private val locations = HashMap<String, Int>()

    fun use(): ComputeProgram = apply { GLES20.glUseProgram(program) }

    fun uniform(name: String, value: Int): ComputeProgram = apply { GLES20.glUniform1i(location(name), value) }

    fun uniform(name: String, value: Float): ComputeProgram = apply { GLES20.glUniform1f(location(name), value) }

    fun uniform(name: String, x: Float, y: Float): ComputeProgram = apply { GLES20.glUniform2f(location(name), x, y) }

    fun uniform(name: String, x: Int, y: Int): ComputeProgram = apply { GLES20.glUniform2i(location(name), x, y) }

    fun uniform(name: String, x: Int, y: Int, z: Int, w: Int): ComputeProgram =
        apply { GLES20.glUniform4i(location(name), x, y, z, w) }

    /** Runs [groupsX] x [groupsY] x [groupsZ] work groups; the program must be in use. */
    fun dispatch(groupsX: Int, groupsY: Int = 1, groupsZ: Int = 1) {
        GLES31.glDispatchCompute(groupsX, groupsY, groupsZ)
        checkGl("glDispatchCompute")
    }

    override fun close() = GLES20.glDeleteProgram(program)

    private fun location(name: String): Int = locations.getOrPut(name) {
        GLES20.glGetUniformLocation(program, name).also { require(it >= 0) { "no uniform $name" } }
    }

    class CompileException(log: String) : RuntimeException(log)

    private companion object {
        const val HEADER = "#version 310 es\n#extension GL_EXT_gpu_shader5 : require\n"

        fun compile(source: String): Int {
            val shader = GLES20.glCreateShader(GLES31.GL_COMPUTE_SHADER)
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)
            val status = IntArray(1).also { GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, it, 0) }
            if (status[0] == 0) {
                val log = GLES20.glGetShaderInfoLog(shader)
                GLES20.glDeleteShader(shader)
                throw CompileException(log)
            }
            return shader
        }

        fun link(shader: Int): Int {
            val program = GLES20.glCreateProgram()
            GLES20.glAttachShader(program, shader)
            GLES20.glLinkProgram(program)
            GLES20.glDeleteShader(shader)
            val status = IntArray(1).also { GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, it, 0) }
            if (status[0] == 0) {
                val log = GLES20.glGetProgramInfoLog(program)
                GLES20.glDeleteProgram(program)
                throw CompileException(log)
            }
            return program
        }
    }
}

/** Throws if GL reported an error since the last check. */
internal fun checkGl(what: String) {
    val error = GLES20.glGetError()
    check(error == GLES20.GL_NO_ERROR) { "$what: GL error 0x${Integer.toHexString(error)}" }
}
