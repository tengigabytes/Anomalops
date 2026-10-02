// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.core.gpu

/**
 * The two kernels of [GpuTileAligner], one work group of [GROUP] invocations per tile. Each mirrors a part of
 * `TileAligner` in `:core:imaging` with the same order of operations; sums are float in shared memory where the
 * CPU sums in double.
 */
internal object TileShaders {
    const val GROUP = 256

    /** Infinity, `meanSquaredDiff`'s cost when too few points land in the frame. */
    private const val INF = "uintBitsToFloat(0x7f800000u)"

    /** Sums [names] (floats in shared arrays `s_<name>`) over the work group into element 0; uniform control flow. */
    private fun reduce(vararg names: String) = """
        for (uint o = ${GROUP / 2}u; o > 0u; o >>= 1u) {
            if (lid < o) { ${names.joinToString(" ") { "s_$it[lid] += s_$it[lid + o];" }} }
            memoryBarrierShared();
            barrier();
        }
    """

    /** `TileAligner.texture`: the reference tile's mean squared central-difference gradient. */
    val TEXTURE = """
        layout(local_size_x = $GROUP) in;
        layout(binding = 0) uniform highp sampler2D plane;
        layout(std430, binding = 0) writeonly buffer Textures { highp float textures[]; };
        uniform int tileSize;
        uniform int cols;
        shared float s_sum[$GROUP];
        shared float s_n[$GROUP];
        float at(int x, int y) { return texelFetch(plane, ivec2(x, y), 0).r; }
        void main() {
            uint lid = gl_LocalInvocationIndex;
            int tile = int(gl_WorkGroupID.x);
            ivec2 size = textureSize(plane, 0);
            int x0 = max((tile % cols) * tileSize, 1);
            int y0 = max((tile / cols) * tileSize, 1);
            int w = max(min((tile % cols + 1) * tileSize, size.x - 1) - x0, 0);
            int h = max(min((tile / cols + 1) * tileSize, size.y - 1) - y0, 0);
            float s = 0.0;
            float n = 0.0;
            for (int i = int(lid); i < w * h; i += $GROUP) {
                int x = x0 + i % w;
                int y = y0 + i / w;
                precise float gx = (at(x + 1, y) - at(x - 1, y)) / 2.0;
                precise float gy = (at(x, y + 1) - at(x, y - 1)) / 2.0;
                precise float t = gx * gx + gy * gy;
                s += t;
                n += 1.0;
            }
            s_sum[lid] = s;
            s_n[lid] = n;
            memoryBarrierShared();
            barrier();
            ${reduce("sum", "n")}
            if (lid == 0u) textures[tile] = s_n[0] == 0.0 ? 0.0 : s_sum[0] / s_n[0];
        }
    """

    /**
     * `TileAligner.alignTile` for one tile per work group: the ±[radius] search on [startLevel], ±1 on each level
     * below, the parabolas on level 0 and the final cost; then the trust test against `threshold` (texture below it:
     * zero residual). Writes (dx, dy, cost, trusted) per tile.
     */
    val ALIGN = """
        layout(local_size_x = $GROUP) in;
        layout(binding = 0) uniform highp sampler2D ref0;
        layout(binding = 1) uniform highp sampler2D ref1;
        layout(binding = 2) uniform highp sampler2D ref2;
        layout(binding = 3) uniform highp sampler2D frame0;
        layout(binding = 4) uniform highp sampler2D frame1;
        layout(binding = 5) uniform highp sampler2D frame2;
        layout(std430, binding = 0) readonly buffer Textures { highp float textures[]; };
        layout(std430, binding = 1) writeonly buffer Field { highp vec4 field[]; };
        uniform int tileSize;
        uniform int cols;
        uniform int startLevel;
        uniform int radius;
        uniform float threshold;
        uniform float scale;
        uniform vec2 shift;
        uniform vec2 centre0;
        uniform vec2 centre1;
        uniform vec2 centre2;
        shared float s_sum[$GROUP];
        shared float s_n[$GROUP];
        uint lid;
        ${Glsl.SAMPLE}
        float refAt(int level, ivec2 p) {
            if (level == 0) return texelFetch(ref0, p, 0).r;
            if (level == 1) return texelFetch(ref1, p, 0).r;
            return texelFetch(ref2, p, 0).r;
        }
        bool frameAt(int level, vec2 q, out float v) {
            if (level == 0) return sampleAt(frame0, textureSize(frame0, 0), q, v);
            if (level == 1) return sampleAt(frame1, textureSize(frame1, 0), q, v);
            return sampleAt(frame2, textureSize(frame2, 0), q, v);
        }
        // meanSquaredDiff over the tile on [level] with the whole-frame shift plus (u, v); the same for every
        // invocation, so call it from uniform control flow only.
        float cost(int level, float u, float v) {
            int f = 1 << level;
            ivec2 lo = ivec2(gl_WorkGroupID.x % uint(cols), gl_WorkGroupID.x / uint(cols)) * tileSize / f;
            int n = tileSize / f;
            vec2 c = level == 0 ? centre0 : (level == 1 ? centre1 : centre2);
            precise float sx = (shift.x + u) / float(f);
            precise float sy = (shift.y + v) / float(f);
            float s = 0.0;
            float used = 0.0;
            for (int i = int(lid); i < n * n; i += $GROUP) {
                ivec2 p = lo + ivec2(i % n, i / n);
                precise vec2 q = c + scale * (vec2(p) - c) + vec2(sx, sy);
                float val;
                if (frameAt(level, q, val)) {
                    float d = refAt(level, p) - val;
                    s += d * d;
                    used += 1.0;
                }
            }
            s_sum[lid] = s;
            s_n[lid] = used;
            memoryBarrierShared();
            barrier();
            ${reduce("sum", "n")}
            float total = s_sum[0];
            used = s_n[0];
            barrier();
            return (used * 2.0 < float(n * n) || used == 0.0) ? $INF : total / used;
        }
        vec2 search(int level, vec2 start, int r) {
            float step = float(1 << level);
            vec2 best = start;
            float bestCost = $INF;
            for (int j = -r; j <= r; j++) {
                for (int i = -r; i <= r; i++) {
                    precise vec2 candidate = start + vec2(float(i) * step, float(j) * step);
                    float c = cost(level, candidate.x, candidate.y);
                    if (c < bestCost) {
                        bestCost = c;
                        best = candidate;
                    }
                }
            }
            return best;
        }
        bool finite(float x) { return !isinf(x) && !isnan(x); }
        float parabola(float minus, float centre, float plus) {
            precise float curvature = minus - 2.0 * centre + plus;
            if (!(finite(minus) && finite(centre) && finite(plus)) || curvature <= 0.0) return 0.0;
            precise float offset = (minus - plus) / (2.0 * curvature);
            return clamp(offset, -0.5, 0.5);
        }
        void main() {
            lid = gl_LocalInvocationIndex;
            vec2 best = search(startLevel, vec2(0.0), radius);
            for (int level = startLevel - 1; level >= 0; level--) best = search(level, best, 1);
            precise float um = best.x - 1.0;
            precise float up = best.x + 1.0;
            precise float vm = best.y - 1.0;
            precise float vp = best.y + 1.0;
            float du = parabola(cost(0, um, best.y), cost(0, best.x, best.y), cost(0, up, best.y));
            float dv = parabola(cost(0, best.x, vm), cost(0, best.x, best.y), cost(0, best.x, vp));
            precise float u = best.x + du;
            precise float v = best.y + dv;
            float c = cost(0, u, v);
            if (lid == 0u) {
                bool trusted = textures[gl_WorkGroupID.x] >= threshold;
                field[gl_WorkGroupID.x] = trusted ? vec4(u, v, c, 1.0) : vec4(0.0, 0.0, c, 0.0);
            }
        }
    """
}
