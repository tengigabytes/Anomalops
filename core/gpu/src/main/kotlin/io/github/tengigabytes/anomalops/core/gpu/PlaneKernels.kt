// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.core.gpu

import android.opengl.GLES20
import android.opengl.GLES31
import io.github.tengigabytes.anomalops.core.imaging.align.Similarity

/**
 * ADR-0017 step 3: the first kernels, each the GPU form of one `:core:imaging` function — [half] (`Plane.half`)
 * and [warp] (`FrameAligner.warp`). [warpFiltered] uses the texture unit's own bilinear filter instead, to see
 * what it costs in accuracy. The output format is the input's. Needs a current [GlesContext].
 */
class PlaneKernels : AutoCloseable {
    private val halves = HashMap<PlaneFormat, ComputeProgram>()
    private val warps = HashMap<Pair<PlaneFormat, Boolean>, ComputeProgram>()

    /** 2 x 2 means, half size; an odd last row or column is dropped. */
    fun half(src: GpuPlane): GpuPlane {
        require(src.width >= 2 && src.height >= 2) { "cannot halve ${src.width}x${src.height}" }
        val dst = GpuPlane(src.width / 2, src.height / 2, src.format)
        src.bindSampler(0)
        dst.bindImage(0, GLES31.GL_WRITE_ONLY)
        halves.getOrPut(src.format) { halfProgram(src.format) }.use()
            .dispatch(GpuPlane.groups(dst.width), GpuPlane.groups(dst.height))
        barrier()
        return dst
    }

    /** [frame] resampled onto the reference grid with only the whole-frame transform; NaN where it has no data. */
    fun warp(frame: GpuPlane, global: Similarity): GpuPlane = warp(frame, global, filtered = false)

    /**
     * As [warp], but with the texture unit's bilinear filter ([PlaneFormat.HALF] only: 32-bit float textures
     * cannot be filtered on the phones tested).
     */
    fun warpFiltered(frame: GpuPlane, global: Similarity): GpuPlane {
        require(frame.format == PlaneFormat.HALF) { "hardware filtering needs HALF, not ${frame.format}" }
        return warp(frame, global, filtered = true)
    }

    private fun warp(frame: GpuPlane, global: Similarity, filtered: Boolean): GpuPlane {
        val dst = GpuPlane(frame.width, frame.height, frame.format)
        frame.bindSampler(0, if (filtered) GLES20.GL_LINEAR else GLES20.GL_NEAREST)
        dst.bindImage(0, GLES31.GL_WRITE_ONLY)
        val level = LevelUniforms(global, 0, frame.width, frame.height)
        warps.getOrPut(frame.format to filtered) { warpProgram(frame.format, filtered) }.use()
            .uniform("centre", level.centreX, level.centreY)
            .uniform("scale", level.scale)
            .uniform("shift", level.dx, level.dy)
            .dispatch(GpuPlane.groups(dst.width), GpuPlane.groups(dst.height))
        barrier()
        if (filtered) frame.bindSampler(0)
        return dst
    }

    override fun close() {
        halves.values.forEach { it.close() }
        warps.values.forEach { it.close() }
    }

    private fun halfProgram(format: PlaneFormat) = ComputeProgram(
        """
        ${Glsl.LOCAL_2D}
        layout(binding = 0) uniform highp sampler2D src;
        layout(${format.qualifier}, binding = 0) writeonly uniform highp image2D dst;
        void main() {
            ivec2 p = ivec2(gl_GlobalInvocationID.xy);
            if (any(greaterThanEqual(p, imageSize(dst)))) return;
            ivec2 s = 2 * p;
            float a = texelFetch(src, s, 0).r;
            float b = texelFetch(src, s + ivec2(1, 0), 0).r;
            float c = texelFetch(src, s + ivec2(0, 1), 0).r;
            float d = texelFetch(src, s + ivec2(1, 1), 0).r;
            precise float mean = (a + b + c + d) * 0.25;
            imageStore(dst, p, vec4(mean));
        }
        """,
    )

    private fun warpProgram(format: PlaneFormat, filtered: Boolean): ComputeProgram {
        val sample = if (filtered) {
            "bool ok = inside(size, q); if (ok) v = texture(src, (q + 0.5) / vec2(size)).r;"
        } else {
            "bool ok = sampleAt(src, size, q, v);"
        }
        return ComputeProgram(
            """
            ${Glsl.LOCAL_2D}
            layout(binding = 0) uniform highp sampler2D src;
            layout(${format.qualifier}, binding = 0) writeonly uniform highp image2D dst;
            uniform vec2 centre;
            uniform float scale;
            uniform vec2 shift;
            ${Glsl.SAMPLE}
            bool inside(ivec2 size, vec2 p) {
                return p.x >= 0.0 && p.x <= float(size.x - 1) && p.y >= 0.0 && p.y <= float(size.y - 1);
            }
            void main() {
                ivec2 p = ivec2(gl_GlobalInvocationID.xy);
                ivec2 size = textureSize(src, 0);
                if (any(greaterThanEqual(p, size))) return;
                precise vec2 q = centre + scale * (vec2(p) - centre) + shift;
                float v;
                $sample
                imageStore(dst, p, vec4(ok ? v : ${Glsl.NAN}));
            }
            """,
        )
    }

    private fun barrier() =
        GLES31.glMemoryBarrier(GLES31.GL_TEXTURE_FETCH_BARRIER_BIT or GLES31.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT)
}

/**
 * `Similarity.atLevel(level)` as shader uniforms, computed with the same float operations as
 * `Similarity.LevelTransform` (its centre is private there), for planes of full size [fullWidth] x [fullHeight].
 */
internal class LevelUniforms(global: Similarity, level: Int, fullWidth: Int, fullHeight: Int) {
    private val factor = (1 shl level).toFloat()
    val scale = global.scale
    val dx = global.dx / factor
    val dy = global.dy / factor
    val centreX = (Similarity.centre(fullWidth) + HALF) / factor - HALF
    val centreY = (Similarity.centre(fullHeight) + HALF) / factor - HALF

    private companion object {
        const val HALF = 0.5f
    }
}
