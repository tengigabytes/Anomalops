// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.probe

import android.opengl.GLES20
import android.opengl.GLES30
import android.opengl.GLES31
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Which texture formats the GPU path of ADR-0017 can use, tested by running compute shaders rather than by
 * reading the spec: half and 32-bit float images as imageStore targets, read-write images for in-place
 * accumulation, colour-renderable formats, and a 16-bit unsigned upload for RAW frames. A shader that fails to
 * compile is reported with its log instead of an error.
 */
internal object GlesFormatProbe {
    private const val RAW_CODE_STEP = 16

    private class FloatFormat(
        val name: String,
        val internalFormat: Int,
        val qualifier: String,
        val uploadFormat: Int,
        val channels: Int,
        val half: Boolean,
    )

    private val formats = listOf(
        FloatFormat("R16F", GLES30.GL_R16F, "r16f", GLES30.GL_RED, 1, half = true),
        FloatFormat("RGBA16F", GLES30.GL_RGBA16F, "rgba16f", GLES20.GL_RGBA, 4, half = true),
        FloatFormat("R32F", GLES30.GL_R32F, "r32f", GLES30.GL_RED, 1, half = false),
        FloatFormat("RGBA32F", GLES30.GL_RGBA32F, "rgba32f", GLES20.GL_RGBA, 4, half = false),
    )

    fun probe(): JSONObject = jsonOf(
        "imageStore" to perFormat { imageStore(it) },
        "imageReadWrite" to perFormat { readWrite(it) },
        "colorRenderable" to JSONObject().apply {
            formats.forEach {
                put(
                    it.name,
                    colorRenderable(it.internalFormat),
                )
            }
        },
        "r16uiUpload" to guarded { r16uiUpload() },
    )

    private fun perFormat(test: (FloatFormat) -> JSONObject) =
        JSONObject().apply { formats.forEach { put(it.name, guarded { test(it) }) } }

    private fun guarded(test: () -> JSONObject): JSONObject = runCatching {
        GlCompute.clearErrors()
        test()
    }.getOrElse {
        if (it is GlCompute.CompileException) {
            jsonOf("compiled" to false, "log" to it.message)
        } else {
            jsonOf("error" to it.toString())
        }
    }

    /** Write-only image: one imageStore per texel, read back with texelFetch. */
    private fun imageStore(format: FloatFormat): JSONObject {
        val program = GlCompute.program(
            """
            layout(${format.qualifier}, binding = 0) writeonly uniform highp image2D dst;
            ${GlCompute.VALUE_GLSL}
            void main() {
                ivec2 p = ivec2(gl_GlobalInvocationID.xy);
                imageStore(dst, p, vec4(value(p)));
            }
            """,
        )
        val texture = GlCompute.texture(format.internalFormat)
        try {
            GLES31.glBindImageTexture(0, texture, 0, false, 0, GLES31.GL_WRITE_ONLY, format.internalFormat)
            GlCompute.dispatch(program)
            return ReadbackCheck.compare(GlCompute.readBack(texture, integer = false), format.half, passes = 1)
        } finally {
            GLES20.glDeleteProgram(program)
            GlCompute.deleteTexture(texture)
        }
    }

    /** Read-write image (imageLoad + imageStore on the same texel), as an in-place accumulator would use it. */
    private fun readWrite(format: FloatFormat): JSONObject {
        val program = GlCompute.program(
            """
            layout(${format.qualifier}, binding = 0) uniform highp image2D acc;
            ${GlCompute.VALUE_GLSL}
            void main() {
                ivec2 p = ivec2(gl_GlobalInvocationID.xy);
                imageStore(acc, p, imageLoad(acc, p) + vec4(value(p)));
            }
            """,
        )
        val texture = GlCompute.texture(format.internalFormat)
        try {
            val zeros = ByteBuffer.allocateDirect(GlCompute.COUNT * format.channels * Float.SIZE_BYTES)
                .order(ByteOrder.nativeOrder())
            GLES20.glTexSubImage2D(
                GLES20.GL_TEXTURE_2D, 0, 0, 0, GlCompute.SIZE, GlCompute.SIZE,
                format.uploadFormat, GLES20.GL_FLOAT, zeros,
            )
            GLES31.glBindImageTexture(0, texture, 0, false, 0, GLES31.GL_READ_WRITE, format.internalFormat)
            repeat(2) {
                GlCompute.dispatch(program)
                GLES31.glMemoryBarrier(GLES31.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT)
            }
            return ReadbackCheck.compare(GlCompute.readBack(texture, integer = false), format.half, passes = 2)
        } finally {
            GLES20.glDeleteProgram(program)
            GlCompute.deleteTexture(texture)
        }
    }

    private fun colorRenderable(internalFormat: Int): Boolean {
        val texture = GlCompute.texture(internalFormat)
        val framebuffer = IntArray(1).also { GLES20.glGenFramebuffers(1, it, 0) }[0]
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer)
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER,
            GLES20.GL_COLOR_ATTACHMENT0,
            GLES20.GL_TEXTURE_2D,
            texture,
            0,
        )
        val complete = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) == GLES20.GL_FRAMEBUFFER_COMPLETE
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glDeleteFramebuffers(1, intArrayOf(framebuffer), 0)
        GlCompute.deleteTexture(texture)
        return complete
    }

    /** RAW upload path (ADR-0017 section 3): R16UI texture filled from a short buffer, sampled as usampler2D. */
    private fun r16uiUpload(): JSONObject {
        val texture = GlCompute.texture(GLES30.GL_R16UI)
        try {
            val data = ByteBuffer.allocateDirect(GlCompute.COUNT * Short.SIZE_BYTES).order(ByteOrder.nativeOrder())
            val shorts = data.asShortBuffer()
            for (i in 0 until GlCompute.COUNT) shorts.put((i * RAW_CODE_STEP).toShort())
            GLES20.glTexSubImage2D(
                GLES20.GL_TEXTURE_2D, 0, 0, 0, GlCompute.SIZE, GlCompute.SIZE,
                GLES30.GL_RED_INTEGER, GLES20.GL_UNSIGNED_SHORT, data,
            )
            val values = GlCompute.readBack(texture, integer = true).asIntBuffer()
            val mismatches = (0 until GlCompute.COUNT).count { values.get(it) != it * RAW_CODE_STEP }
            return jsonOf(
                "compiled" to true,
                "glError" to GlCompute.hex(GLES20.glGetError()),
                "mismatches" to mismatches,
            )
        } finally {
            GlCompute.deleteTexture(texture)
        }
    }
}
