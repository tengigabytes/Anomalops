// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.probe

import android.opengl.GLES20
import android.opengl.GLES30
import android.opengl.GLES31
import org.json.JSONObject

/**
 * OpenGL ES version, compute-shader limits, float precision and extensions (ADR-0017 step 1), plus the
 * texture-format tests of [GlesFormatProbe]. Runs on the calling thread with its own headless context.
 */
internal object GlesProbe {
    private const val COMPUTE_AXES = 3

    fun probe(): JSONObject = runCatching { GlContext.create().use(::collect) }
        .getOrElse { jsonOf("error" to it.stackTraceToString()) }

    private fun collect(gl: GlContext): JSONObject = jsonOf(
        "egl" to gl.info(),
        "vendor" to GLES20.glGetString(GLES20.GL_VENDOR),
        "renderer" to GLES20.glGetString(GLES20.GL_RENDERER),
        "version" to GLES20.glGetString(GLES20.GL_VERSION),
        "shadingLanguageVersion" to GLES20.glGetString(GLES20.GL_SHADING_LANGUAGE_VERSION),
        "majorVersion" to int(GLES30.GL_MAJOR_VERSION),
        "minorVersion" to int(GLES30.GL_MINOR_VERSION),
        "limits" to limits(),
        "fragmentPrecision" to precision(),
        "extensions" to extensions().toJsonArray(),
        "formats" to GlesFormatProbe.probe(),
    )

    private fun limits(): JSONObject = jsonOf(
        "maxTextureSize" to int(GLES20.GL_MAX_TEXTURE_SIZE),
        "max3dTextureSize" to int(GLES30.GL_MAX_3D_TEXTURE_SIZE),
        "maxArrayTextureLayers" to int(GLES30.GL_MAX_ARRAY_TEXTURE_LAYERS),
        "maxComputeWorkGroupCount" to indexed(GLES31.GL_MAX_COMPUTE_WORK_GROUP_COUNT),
        "maxComputeWorkGroupSize" to indexed(GLES31.GL_MAX_COMPUTE_WORK_GROUP_SIZE),
        "maxComputeWorkGroupInvocations" to int(GLES31.GL_MAX_COMPUTE_WORK_GROUP_INVOCATIONS),
        "maxComputeSharedMemoryBytes" to int(GLES31.GL_MAX_COMPUTE_SHARED_MEMORY_SIZE),
        "maxComputeUniformComponents" to int(GLES31.GL_MAX_COMPUTE_UNIFORM_COMPONENTS),
        "maxComputeTextureImageUnits" to int(GLES31.GL_MAX_COMPUTE_TEXTURE_IMAGE_UNITS),
        "maxComputeImageUniforms" to int(GLES31.GL_MAX_COMPUTE_IMAGE_UNIFORMS),
        "maxComputeShaderStorageBlocks" to int(GLES31.GL_MAX_COMPUTE_SHADER_STORAGE_BLOCKS),
        "maxImageUnits" to int(GLES31.GL_MAX_IMAGE_UNITS),
        "maxShaderStorageBlockBytes" to long(GLES31.GL_MAX_SHADER_STORAGE_BLOCK_SIZE),
    )

    /** Compute shaders cannot be queried; the fragment stage shows whether mediump is really 16-bit. */
    private fun precision(): JSONObject = jsonOf(
        "mediumFloat" to precisionOf(GLES20.GL_MEDIUM_FLOAT),
        "highFloat" to precisionOf(GLES20.GL_HIGH_FLOAT),
    )

    private fun precisionOf(type: Int): JSONObject {
        val range = IntArray(2)
        val bits = IntArray(1)
        GLES20.glGetShaderPrecisionFormat(GLES20.GL_FRAGMENT_SHADER, type, range, 0, bits, 0)
        return jsonOf("rangeLog2" to listOf(range[0], range[1]).toJsonArray(), "precisionBits" to bits[0])
    }

    private fun extensions(): List<String> =
        (0 until int(GLES30.GL_NUM_EXTENSIONS)).map { GLES30.glGetStringi(GLES20.GL_EXTENSIONS, it) }.sorted()

    private fun int(name: Int): Int = IntArray(1).also { GLES20.glGetIntegerv(name, it, 0) }[0]

    private fun long(name: Int): Long = LongArray(1).also { GLES30.glGetInteger64v(name, it, 0) }[0]

    private fun indexed(name: Int) =
        (0 until COMPUTE_AXES).map { axis -> IntArray(1).also { GLES31.glGetIntegeri_v(name, axis, it, 0) }[0] }
            .toJsonArray()
}
