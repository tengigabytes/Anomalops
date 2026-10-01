// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.core.gpu

import android.opengl.GLES20
import android.opengl.GLES30
import android.opengl.GLES31
import io.github.tengigabytes.anomalops.core.imaging.align.Region
import io.github.tengigabytes.anomalops.core.imaging.align.Similarity
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * `meanSquaredDiff` of `:core:imaging` for a batch of candidate transforms in one dispatch, as the whole-frame
 * search tries many per pyramid level (ADR-0017 step 3). Each work group sums its grid points' squared differences
 * in shared memory; the per-group sums are added on the CPU in double. Needs a current [GlesContext].
 */
class MeanSquaredDiffKernel : AutoCloseable {
    private val program = ComputeProgram(
        """
        layout(local_size_x = $GROUP) in;
        layout(binding = 0) uniform highp sampler2D ref;
        layout(binding = 1) uniform highp sampler2D frame;
        layout(std430, binding = 0) readonly buffer Candidates { highp vec4 candidates[]; };
        layout(std430, binding = 1) writeonly buffer Partials { highp vec2 partials[]; };
        uniform ivec4 region;
        uniform int spacing;
        uniform ivec2 grid;
        uniform vec2 centre;
        shared float sums[$GROUP];
        shared float counts[$GROUP];
        ${Glsl.SAMPLE}
        void main() {
            uint lid = gl_LocalInvocationID.x;
            int i = int(gl_GlobalInvocationID.x);
            vec4 c = candidates[gl_WorkGroupID.y];
            float s = 0.0;
            float n = 0.0;
            if (i < grid.x * grid.y) {
                int x = region.x + (i % grid.x) * spacing;
                int y = region.y + (i / grid.x) * spacing;
                precise vec2 q = centre + c.x * (vec2(x, y) - centre) + c.yz;
                float v;
                if (sampleAt(frame, textureSize(frame, 0), q, v)) {
                    float d = texelFetch(ref, ivec2(x, y), 0).r - v;
                    s = d * d;
                    n = 1.0;
                }
            }
            sums[lid] = s;
            counts[lid] = n;
            memoryBarrierShared();
            barrier();
            for (uint o = ${GROUP / 2}u; o > 0u; o >>= 1u) {
                if (lid < o) {
                    sums[lid] += sums[lid + o];
                    counts[lid] += counts[lid + o];
                }
                memoryBarrierShared();
                barrier();
            }
            if (lid == 0u) {
                partials[gl_WorkGroupID.y * gl_NumWorkGroups.x + gl_WorkGroupID.x] = vec2(sums[0], counts[0]);
            }
        }
        """,
    )

    /**
     * The cost of each of [candidates] (whole-frame transforms in full-resolution pixels) between [reference] and
     * [frame] on pyramid [level], at the grid points of [region] with spacing [step]; [full] is the level-0 size.
     * Infinity where fewer than half the points land in the frame, as on the CPU.
     */
    @Suppress("LongParameterList") // The CPU function's parameters, with the level instead of a LevelTransform.
    fun evaluate(
        reference: GpuPlane,
        frame: GpuPlane,
        candidates: List<Similarity>,
        level: Int,
        region: Region,
        step: Int,
        full: Pair<Int, Int>,
    ): FloatArray {
        val nx = (region.right - region.left + step - 1) / step
        val ny = (region.bottom - region.top + step - 1) / step
        val groups = (nx * ny + GROUP - 1) / GROUP
        val buffers = IntArray(2).also { GLES20.glGenBuffers(2, it, 0) }
        try {
            upload(buffers[0], candidates, level, full)
            GLES20.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, buffers[1])
            val partialBytes = candidates.size * groups * 2 * Float.SIZE_BYTES
            GLES20.glBufferData(GLES31.GL_SHADER_STORAGE_BUFFER, partialBytes, null, GLES30.GL_STREAM_READ)
            GLES30.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 1, buffers[1])
            reference.bindSampler(0)
            frame.bindSampler(1)
            val centre = LevelUniforms(Similarity(), level, full.first, full.second)
            program.use()
                .uniform("region", region.left, region.top, region.right, region.bottom)
                .uniform("spacing", step)
                .uniform("grid", nx, ny)
                .uniform("centre", centre.centreX, centre.centreY)
                .dispatch(groups, candidates.size)
            GLES31.glMemoryBarrier(GLES31.GL_BUFFER_UPDATE_BARRIER_BIT)
            return reduce(partialBytes, candidates.size, groups, nx * ny)
        } finally {
            GLES20.glDeleteBuffers(2, buffers, 0)
        }
    }

    override fun close() = program.close()

    private fun upload(buffer: Int, candidates: List<Similarity>, level: Int, full: Pair<Int, Int>) {
        val data = ByteBuffer.allocateDirect(candidates.size * VEC4_BYTES).order(ByteOrder.nativeOrder())
        candidates.forEach { s ->
            val u = LevelUniforms(s, level, full.first, full.second)
            data.putFloat(u.scale).putFloat(u.dx).putFloat(u.dy).putFloat(0f)
        }
        data.flip()
        GLES20.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, buffer)
        GLES20.glBufferData(GLES31.GL_SHADER_STORAGE_BUFFER, data.capacity(), data, GLES20.GL_STATIC_DRAW)
        GLES30.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 0, buffer)
    }

    private fun reduce(bytes: Int, candidates: Int, groups: Int, points: Int): FloatArray {
        val mapped = GLES30.glMapBufferRange(GLES31.GL_SHADER_STORAGE_BUFFER, 0, bytes, GLES30.GL_MAP_READ_BIT)
        checkNotNull(mapped) { "glMapBufferRange failed" }
        val partials = (mapped as ByteBuffer).order(ByteOrder.nativeOrder()).asFloatBuffer()
        val costs = FloatArray(candidates) { k ->
            var sum = 0.0
            var used = 0
            for (g in 0 until groups) {
                sum += partials.get(2 * (k * groups + g))
                used += partials.get(2 * (k * groups + g) + 1).toInt()
            }
            if (used * 2 < points || used == 0) Float.POSITIVE_INFINITY else (sum / used).toFloat()
        }
        GLES30.glUnmapBuffer(GLES31.GL_SHADER_STORAGE_BUFFER)
        checkGl("meanSquaredDiff")
        return costs
    }

    private companion object {
        const val GROUP = 256
        const val VEC4_BYTES = 16
    }
}
