// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.core.gpu

import android.opengl.GLES20
import android.opengl.GLES30
import android.opengl.GLES31
import io.github.tengigabytes.anomalops.core.imaging.align.NormalEquations
import io.github.tengigabytes.anomalops.core.imaging.align.Region
import io.github.tengigabytes.anomalops.core.imaging.align.Similarity
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The normal equations of `GaussNewton` on level 0, as `GradientSums` of `:core:imaging` computes them on the CPU
 * (ADR-0017 step 4). Each invocation sums [ROWS] grid points in float, laid out as in [MeanSquaredDiffKernel]; each
 * work group sums its invocations in shared memory, and the per-group sums are added on the CPU in double. The
 * CPU sums every term in double, so the results agree closely but not bit for bit. Needs a current [GlesContext].
 */
class GaussNewtonKernel : AutoCloseable {
    private val local = LOCAL
    private val rows = ROWS
    private val group = local * local
    private val partials = IntArray(1).also { GLES20.glGenBuffers(1, it, 0) }[0]
    private var partialBytes = 0

    private val program = ComputeProgram(
        """
        layout(local_size_x = $local, local_size_y = $local) in;
        layout(binding = 0) uniform highp sampler2D ref;
        layout(binding = 1) uniform highp sampler2D frame;
        layout(std430, binding = 0) writeonly buffer Partials { highp float partials[]; };
        uniform ivec4 region;
        uniform int spacing;
        uniform ivec2 grid;
        uniform vec2 centre;
        uniform float scale;
        uniform vec2 shift;
        shared float sums[${TERMS * group}];
        ${Glsl.SAMPLE}
        float refAt(int x, int y) { return texelFetch(ref, ivec2(x, y), 0).r; }
        void main() {
            uint lid = gl_LocalInvocationIndex;
            ivec2 size = textureSize(frame, 0);
            float t[$TERMS];
            for (int k = 0; k < $TERMS; k++) t[k] = 0.0;
            int gx = int(gl_GlobalInvocationID.x);
            if (gx < grid.x) {
                int x = region.x + gx * spacing;
                for (int k = 0; k < $rows; k++) {
                    int gy = (int(gl_WorkGroupID.y) * $rows + k) * $local + int(gl_LocalInvocationID.y);
                    if (gy >= grid.y) break;
                    int y = region.y + gy * spacing;
                    precise vec2 q = centre + scale * (vec2(x, y) - centre) + shift;
                    precise vec2 qx0 = q - vec2(1.0, 0.0);
                    precise vec2 qx1 = q + vec2(1.0, 0.0);
                    precise vec2 qy0 = q - vec2(0.0, 1.0);
                    precise vec2 qy1 = q + vec2(0.0, 1.0);
                    float f, l, r, u, d;
                    if (!(sampleAt(frame, size, q, f) && sampleAt(frame, size, qx1, r)
                        && sampleAt(frame, size, qx0, l) && sampleAt(frame, size, qy1, d)
                        && sampleAt(frame, size, qy0, u))) continue;
                    precise float fxGrad = (r - l) / 2.0;
                    precise float fyGrad = (d - u) / 2.0;
                    if (isnan(f + fxGrad + fyGrad)) continue;
                    // The reference's gradient is per reference pixel; per frame pixel it is divided by the scale.
                    precise float gxv = (fxGrad + (refAt(x + 1, y) - refAt(x - 1, y)) / (2.0 * scale)) / 2.0;
                    precise float gyv = (fyGrad + (refAt(x, y + 1) - refAt(x, y - 1)) / (2.0 * scale)) / 2.0;
                    float j0 = gxv * (float(x) - centre.x) + gyv * (float(y) - centre.y);
                    float res = f - refAt(x, y);
                    t[0] += j0 * j0;
                    t[1] += j0 * gxv;
                    t[2] += j0 * gyv;
                    t[3] += gxv * gxv;
                    t[4] += gxv * gyv;
                    t[5] += gyv * gyv;
                    t[6] -= j0 * res;
                    t[7] -= gxv * res;
                    t[8] -= gyv * res;
                }
            }
            for (int k = 0; k < $TERMS; k++) sums[uint(k) * ${group}u + lid] = t[k];
            memoryBarrierShared();
            barrier();
            for (uint o = ${group / 2}u; o > 0u; o >>= 1u) {
                if (lid < o) {
                    for (uint k = 0u; k < ${TERMS}u; k++) sums[k * ${group}u + lid] += sums[k * ${group}u + lid + o];
                }
                memoryBarrierShared();
                barrier();
            }
            if (lid < ${TERMS}u) {
                uint g = gl_WorkGroupID.y * gl_NumWorkGroups.x + gl_WorkGroupID.x;
                partials[g * ${TERMS}u + lid] = sums[lid * ${group}u];
            }
        }
        """,
    )

    /**
     * The normal equations at [s] between [reference] and [frame] (level 0, the same size), over the grid of
     * [region] with spacing [step]. The region must leave one pixel at every edge for the reference's gradient.
     */
    fun equations(reference: GpuPlane, frame: GpuPlane, s: Similarity, region: Region, step: Int): NormalEquations {
        require(region.left >= 1 && region.top >= 1) { "region $region touches the plane's edge" }
        require(region.right < reference.width && region.bottom < reference.height) { "region $region at the edge" }
        val nx = (region.right - region.left + step - 1) / step
        val ny = (region.bottom - region.top + step - 1) / step
        val groupsX = (nx + local - 1) / local
        val groupsY = (ny + local * rows - 1) / (local * rows)
        val bytes = groupsX * groupsY * TERMS * Float.SIZE_BYTES
        GLES20.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, partials)
        if (bytes > partialBytes) {
            GLES20.glBufferData(GLES31.GL_SHADER_STORAGE_BUFFER, bytes, null, GLES30.GL_STREAM_READ)
            partialBytes = bytes
        }
        GLES30.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 0, partials)
        reference.bindSampler(0)
        frame.bindSampler(1)
        val u = LevelUniforms(s, 0, reference.width, reference.height)
        program.use()
            .uniform("region", region.left, region.top, region.right, region.bottom)
            .uniform("spacing", step)
            .uniform("grid", nx, ny)
            .uniform("centre", u.centreX, u.centreY)
            .uniform("scale", u.scale)
            .uniform("shift", u.dx, u.dy)
            .dispatch(groupsX, groupsY)
        GLES31.glMemoryBarrier(GLES31.GL_BUFFER_UPDATE_BARRIER_BIT)
        return reduce(bytes, groupsX * groupsY)
    }

    override fun close() {
        program.close()
        GLES20.glDeleteBuffers(1, intArrayOf(partials), 0)
    }

    private fun reduce(bytes: Int, groups: Int): NormalEquations {
        val mapped = GLES30.glMapBufferRange(GLES31.GL_SHADER_STORAGE_BUFFER, 0, bytes, GLES30.GL_MAP_READ_BIT)
        checkNotNull(mapped) { "glMapBufferRange failed" }
        val values = (mapped as ByteBuffer).order(ByteOrder.nativeOrder()).asFloatBuffer()
        val t = DoubleArray(TERMS)
        for (g in 0 until groups) {
            for (k in 0 until TERMS) t[k] += values.get(g * TERMS + k)
        }
        GLES30.glUnmapBuffer(GLES31.GL_SHADER_STORAGE_BUFFER)
        checkGl("GaussNewton equations")
        val a = DoubleArray(SYMMETRIC.size) { t[SYMMETRIC[it]] }
        return NormalEquations(a, DoubleArray(TERMS - MATRIX_TERMS) { t[MATRIX_TERMS + it] })
    }

    private companion object {
        /** As [MeanSquaredDiffKernel]: 16 x 16 work groups, 8 grid rows per invocation. */
        const val LOCAL = 16
        const val ROWS = 8

        /** The six distinct entries of the symmetric 3 x 3 matrix (a00, a01, a02, a11, a12, a22), then b. */
        const val TERMS = 9
        const val MATRIX_TERMS = 6

        /** The term of each entry of the 3 x 3 matrix, row by row. */
        val SYMMETRIC = intArrayOf(0, 1, 2, 1, 3, 4, 2, 4, 5)
    }
}
