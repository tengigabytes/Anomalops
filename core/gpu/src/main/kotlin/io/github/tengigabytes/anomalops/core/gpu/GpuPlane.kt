// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.core.gpu

import android.opengl.GLES20
import android.opengl.GLES30
import android.opengl.GLES31

/**
 * How a plane is stored on the GPU (ADR-0017 section 3). [HALF] keeps the value in the red channel of an RGBA16F
 * image, because R16F cannot be an imageStore target on the phones tested (docs/test/g0-blazer.md, section 6); the
 * other three channels are unused for now, so it takes as much memory as [FLOAT32]. Writes to [HALF] round toward
 * zero on the phone measured so far.
 */
enum class PlaneFormat(internal val internalFormat: Int, internal val qualifier: String) {
    FLOAT32(GLES30.GL_R32F, "r32f"),
    HALF(GLES30.GL_RGBA16F, "rgba16f"),
}

/** One plane of linear values in a GPU texture, the counterpart of `Plane`. Needs a current [GlesContext]. */
class GpuPlane(val width: Int, val height: Int, val format: PlaneFormat) : AutoCloseable {
    internal val texture: Int = IntArray(1).also { GLES20.glGenTextures(1, it, 0) }[0]

    init {
        require(width > 0 && height > 0) { "empty plane ${width}x$height" }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLES30.glTexStorage2D(GLES20.GL_TEXTURE_2D, 1, format.internalFormat, width, height)
        filter(GLES20.GL_NEAREST)
        checkGl("GpuPlane ${width}x$height $format")
    }

    /** Binds the texture to sampler unit [unit] with the given filter (linear needs [PlaneFormat.HALF]). */
    internal fun bindSampler(unit: Int, filter: Int = GLES20.GL_NEAREST) {
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + unit)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        filter(filter)
    }

    /** Binds the texture as image unit [unit] for writing ([access] GL_WRITE_ONLY, GL_READ_WRITE, …). */
    internal fun bindImage(unit: Int, access: Int) =
        GLES31.glBindImageTexture(unit, texture, 0, false, 0, access, format.internalFormat)

    override fun close() = GLES20.glDeleteTextures(1, intArrayOf(texture), 0)

    private fun filter(mode: Int) {
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, mode)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, mode)
    }

    internal companion object {
        /** Work-group edge of the 2-D kernels. */
        const val LOCAL = 16

        fun groups(size: Int) = (size + LOCAL - 1) / LOCAL
    }
}
