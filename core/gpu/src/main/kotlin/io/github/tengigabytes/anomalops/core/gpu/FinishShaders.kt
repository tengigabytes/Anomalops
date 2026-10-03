// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.core.gpu

/**
 * The kernels of [GpuFinish]: the whole-picture steps on packed ARGB after rendering (`Render.finish`). Both read
 * buffer 0 and write buffer 1, one uint per pixel, and use integers throughout in the CPU's order, so the codes
 * are the same.
 */
internal object FinishShaders {
    /**
     * One pass of `ChromaDenoise`: neighbours `spacing` pixels apart on a 5 x 5 grid, weighted by `SPATIAL` and by
     * buffer 2 (`ChromaDenoise.rangeWeights`, indexed by the luma difference in codes).
     */
    val CHROMA = """
        ${Glsl.LOCAL_2D}
        layout(std430, binding = 0) readonly buffer In { highp uint src[]; };
        layout(std430, binding = 1) writeonly buffer Out { highp uint dst[]; };
        layout(std430, binding = 2) readonly buffer Range { highp int rangeWeight[]; };
        uniform ivec2 size;
        uniform int spacing;
        const int SPATIAL[25] = int[25](
            2, 5, 6, 5, 2, 5, 10, 12, 10, 5, 6, 12, 16, 12, 6, 5, 10, 12, 10, 5, 2, 5, 6, 5, 2);
        ivec3 rgbOf(uint c) {
            return ivec3(int((c >> 16) & 0xFFu), int((c >> 8) & 0xFFu), int(c & 0xFFu));
        }
        int lumaOf(ivec3 c) {
            return 54 * c.r + 183 * c.g + 19 * c.b;
        }
        void main() {
            ivec2 p = ivec2(gl_GlobalInvocationID.xy);
            if (any(greaterThanEqual(p, size))) return;
            uint own = src[p.y * size.x + p.x];
            int luma = lumaOf(rgbOf(own));
            ivec3 sums = ivec3(0);
            int total = 0;
            for (int dy = -2; dy <= 2; dy++) {
                for (int dx = -2; dx <= 2; dx++) {
                    ivec2 q = clamp(p + spacing * ivec2(dx, dy), ivec2(0), size - 1);
                    ivec3 c = rgbOf(src[q.y * size.x + q.x]);
                    int y = lumaOf(c);
                    int w = SPATIAL[(dy + 2) * 5 + dx + 2] * rangeWeight[abs(y - luma) >> 8];
                    sums += w * ((((c << 8) - y) >> 4) + 4096);
                    total += w;
                }
            }
            ivec3 chroma = (sums + total / 2) / total - 4096;
            ivec3 o = clamp((luma + chroma * 16 + 128) >> 8, 0, 255);
            dst[p.y * size.x + p.x] = (own & 0xFF000000u) | (uint(o.r) << 16) | (uint(o.g) << 8) | uint(o.b);
        }
    """

    /** `Sharpen.apply`; `amount` is `Sharpen.quantise`'s integer. */
    val SHARPEN = """
        ${Glsl.LOCAL_2D}
        layout(std430, binding = 0) readonly buffer In { highp uint src[]; };
        layout(std430, binding = 1) writeonly buffer Out { highp uint dst[]; };
        uniform ivec2 size;
        uniform int amount;
        const int KERNEL[5] = int[5](1, 4, 6, 4, 1);
        int lumaAt(int x, int y) {
            uint c = src[clamp(y, 0, size.y - 1) * size.x + clamp(x, 0, size.x - 1)];
            return 54 * int((c >> 16) & 0xFFu) + 183 * int((c >> 8) & 0xFFu) + 19 * int(c & 0xFFu);
        }
        void main() {
            ivec2 p = ivec2(gl_GlobalInvocationID.xy);
            if (any(greaterThanEqual(p, size))) return;
            int blur = 0;
            for (int dy = -2; dy <= 2; dy++) {
                int row = 0;
                for (int dx = -2; dx <= 2; dx++) row += KERNEL[dx + 2] * lumaAt(p.x + dx, p.y + dy);
                blur += KERNEL[dy + 2] * row;
            }
            uint c = src[p.y * size.x + p.x];
            int delta = (amount * (((lumaAt(p.x, p.y) << 8) - blur) >> 8) + 32768) >> 16;
            int r = clamp(int((c >> 16) & 0xFFu) + delta, 0, 255);
            int g = clamp(int((c >> 8) & 0xFFu) + delta, 0, 255);
            int b = clamp(int(c & 0xFFu) + delta, 0, 255);
            dst[p.y * size.x + p.x] = (c & 0xFF000000u) | (uint(r) << 16) | (uint(g) << 8) | uint(b);
        }
    """
}
