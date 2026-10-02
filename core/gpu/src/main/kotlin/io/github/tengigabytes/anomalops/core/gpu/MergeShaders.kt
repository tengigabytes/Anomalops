// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.core.gpu

/**
 * The kernels of [GpuLowLightMerge], each mirroring a part of `LowLightMerge` (and its `OffsetField`) in
 * `:core:imaging` with the same order of operations.
 */
internal object MergeShaders {
    /** `LowLightMerge`'s smallest weight that still counts. */
    private const val MIN_WEIGHT = "1e-3"

    /**
     * `OffsetField`: where reference pixel p lies in the frame, the whole-frame transform plus the tile residuals
     * (buffer 0, (dx, dy) per tile, row-major) interpolated between tile centres; edges hold the nearest tile.
     */
    private const val POSITION = """
        layout(std430, binding = 0) readonly buffer Tiles { highp vec2 tiles[]; };
        uniform vec2 centre;
        uniform float scale;
        uniform vec2 shift;
        uniform int tileSize;
        uniform ivec2 grid;
        vec2 tileAt(int c, int r) { return tiles[r * grid.x + c]; }
        vec2 framePos(ivec2 p) {
            vec2 res = vec2(0.0);
            if (grid.x > 0 && grid.y > 0) {
                float size = float(tileSize);
                precise float fx = clamp((float(p.x) + 0.5) / size - 0.5, 0.0, float(grid.x - 1));
                precise float fy = clamp((float(p.y) + 0.5) / size - 0.5, 0.0, float(grid.y - 1));
                int c0 = min(int(fx), grid.x - 1);
                int r0 = min(int(fy), grid.y - 1);
                int c1 = min(c0 + 1, grid.x - 1);
                int r1 = min(r0 + 1, grid.y - 1);
                precise float ax = fx - float(c0);
                precise float ay = fy - float(r0);
                precise vec2 top = tileAt(c0, r0) * (1.0 - ax) + tileAt(c1, r0) * ax;
                precise vec2 bottom = tileAt(c0, r1) * (1.0 - ax) + tileAt(c1, r1) * ax;
                precise vec2 r = top * (1.0 - ay) + bottom * ay;
                res = r;
            }
            precise vec2 q = centre + scale * (vec2(p) - centre) + shift + res;
            return q;
        }
    """

    /** The frame's luma at the aligned position minus the reference's; NaN outside the frame. */
    val DIFF = """
        ${Glsl.LOCAL_2D}
        layout(binding = 0) uniform highp sampler2D frame;
        layout(binding = 1) uniform highp sampler2D ref;
        layout(r32f, binding = 0) writeonly uniform highp image2D diff;
        $POSITION
        ${Glsl.SAMPLE}
        void main() {
            ivec2 p = ivec2(gl_GlobalInvocationID.xy);
            if (any(greaterThanEqual(p, imageSize(diff)))) return;
            float v;
            float d = ${Glsl.NAN};
            if (sampleAt(frame, textureSize(frame, 0), framePos(p), v)) {
                precise float dd = v - texelFetch(ref, p, 0).r;
                d = dd;
            }
            imageStore(diff, p, vec4(d));
        }
    """

    /**
     * `Accumulator.accumulate` for [channels] planes: the 3 x 3 mean of the difference (NaN ignored) gives the
     * weight exp(-d² / allowance); where it counts and every channel has a value, the weight and the weighted
     * values are added to the running sums (read-write R32F images).
     */
    fun accumulate(channels: Int): String {
        val samplers = (0 until channels).joinToString("\n") {
            "layout(binding = ${it + 1}) uniform highp sampler2D ch$it;"
        }
        val images = (0 until channels).joinToString("\n") {
            "layout(r32f, binding = ${it + 1}) uniform highp image2D sum$it;"
        }
        val reads = (0 until channels).joinToString("\n") {
            "float v$it; if (!sampleAt(ch$it, textureSize(ch$it, 0), q, v$it)) return;"
        }
        val adds = (0 until channels).joinToString("\n") {
            "precise float s$it = imageLoad(sum$it, p).r + w * v$it; imageStore(sum$it, p, vec4(s$it));"
        }
        return """
            ${Glsl.LOCAL_2D}
            layout(binding = 0) uniform highp sampler2D diff;
            $samplers
            layout(r32f, binding = 0) uniform highp image2D weight;
            $images
            uniform float allowance;
            $POSITION
            ${Glsl.SAMPLE}
            float mean3(ivec2 p, ivec2 size) {
                precise float s = 0.0;
                int n = 0;
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        float v = texelFetch(diff, clamp(p + ivec2(dx, dy), ivec2(0), size - 1), 0).r;
                        if (!isnan(v)) {
                            s += v;
                            n++;
                        }
                    }
                }
                precise float m = s / float(n);
                return n == 0 ? ${Glsl.NAN} : m;
            }
            void main() {
                ivec2 p = ivec2(gl_GlobalInvocationID.xy);
                ivec2 size = textureSize(diff, 0);
                if (any(greaterThanEqual(p, size))) return;
                float d = mean3(p, size);
                if (isnan(d)) return;
                precise float w = exp(-d * d / allowance);
                if (!(w >= $MIN_WEIGHT)) return;
                vec2 q = framePos(p);
                $reads
                precise float ws = imageLoad(weight, p).r + w;
                imageStore(weight, p, vec4(ws));
                $adds
            }
        """
    }

    /** dst = src (sampler 0 into image 0), or a constant where there is no source. */
    val COPY = """
        ${Glsl.LOCAL_2D}
        layout(binding = 0) uniform highp sampler2D src;
        layout(r32f, binding = 0) writeonly uniform highp image2D dst;
        uniform int fill;
        uniform float value;
        void main() {
            ivec2 p = ivec2(gl_GlobalInvocationID.xy);
            if (any(greaterThanEqual(p, imageSize(dst)))) return;
            imageStore(dst, p, vec4(fill != 0 ? value : texelFetch(src, p, 0).r));
        }
    """

    /** sum /= weight in place (`Accumulator.finish`). */
    val DIVIDE = """
        ${Glsl.LOCAL_2D}
        layout(binding = 0) uniform highp sampler2D weight;
        layout(r32f, binding = 0) uniform highp image2D sum;
        void main() {
            ivec2 p = ivec2(gl_GlobalInvocationID.xy);
            if (any(greaterThanEqual(p, imageSize(sum)))) return;
            precise float v = imageLoad(sum, p).r / texelFetch(weight, p, 0).r;
            imageStore(sum, p, vec4(v));
        }
    """
}
