// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.core.gpu

/** The kernels of [GpuStackGuard], each mirroring a part of `StackGuard` in `:core:imaging`. */
internal object GuardShaders {
    const val GROUP = 256

    /** `StackGuard.fill`: the frame (sampler 0) with its NaN pixels replaced by the reference's (sampler 1). */
    val FILL = """
        ${Glsl.LOCAL_2D}
        layout(binding = 0) uniform highp sampler2D frame;
        layout(binding = 1) uniform highp sampler2D reference;
        layout(r32f, binding = 0) writeonly uniform highp image2D dst;
        void main() {
            ivec2 p = ivec2(gl_GlobalInvocationID.xy);
            if (any(greaterThanEqual(p, imageSize(dst)))) return;
            float v = texelFetch(frame, p, 0).r;
            imageStore(dst, p, vec4(isnan(v) ? texelFetch(reference, p, 0).r : v));
        }
    """

    /**
     * The sums behind `StackGuard.sharpness`: the 4-neighbour Laplacian (edges clamped) of every pixel in `region`
     * (left, top, right, bottom; right and bottom exclusive), summed and squared-summed per work group of
     * [GROUP] rows of pixels into (sum, sum of squares) pairs, which the CPU adds in double.
     */
    val SHARPNESS = """
        layout(local_size_x = $GROUP) in;
        layout(binding = 0) uniform highp sampler2D src;
        layout(std430, binding = 0) writeonly buffer Partials { highp vec2 partials[]; };
        uniform ivec4 region;
        shared float s_sum[$GROUP];
        shared float s_sq[$GROUP];
        void main() {
            uint lid = gl_LocalInvocationIndex;
            int y = region.y + int(gl_WorkGroupID.x);
            ivec2 size = textureSize(src, 0);
            ivec2 hi = size - 1;
            float s = 0.0;
            float q = 0.0;
            for (int x = region.x + int(lid); x < region.z; x += $GROUP) {
                ivec2 p = ivec2(x, y);
                float c = texelFetch(src, p, 0).r;
                float l = texelFetch(src, clamp(p - ivec2(1, 0), ivec2(0), hi), 0).r;
                float r = texelFetch(src, clamp(p + ivec2(1, 0), ivec2(0), hi), 0).r;
                float u = texelFetch(src, clamp(p - ivec2(0, 1), ivec2(0), hi), 0).r;
                float d = texelFetch(src, clamp(p + ivec2(0, 1), ivec2(0), hi), 0).r;
                precise float v = 4.0 * c - l - r - u - d;
                s += v;
                q += v * v;
            }
            s_sum[lid] = s;
            s_sq[lid] = q;
            memoryBarrierShared();
            barrier();
            for (uint o = ${GROUP / 2}u; o > 0u; o >>= 1u) {
                if (lid < o) {
                    s_sum[lid] += s_sum[lid + o];
                    s_sq[lid] += s_sq[lid + o];
                }
                memoryBarrierShared();
                barrier();
            }
            if (lid == 0u) partials[gl_WorkGroupID.x] = vec2(s_sum[0], s_sq[0]);
        }
    """
}
