// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.core.gpu

/**
 * The kernels of [GpuDevelop]: RAW to camera RGB planes (`Demosaic.halfSize` and `Rgb.luma` of `:core:imaging`)
 * and camera RGB to 8-bit sRGB ARGB (`Render.toArgb`), with the same order of operations.
 */
internal object DevelopShaders {
    /**
     * One output pixel per 2 x 2 cell of the R16UI RAW: each photosite's colour from `cell` (colour index of the
     * four cell positions, row by row) and its linear value from buffer 0, `RawFrame.linear` of every 16-bit
     * sample for each cell position (65 536 per position), made on the CPU so the division rounds as there (with
     * the division in the shader, about two thirds of the red values differed from the CPU's); red, the mean of the
     * greens, blue, and the Rec. 709 luma, into four R32F images.
     */
    val HALF_SIZE = """
        ${Glsl.LOCAL_2D}
        layout(binding = 0) uniform highp usampler2D raw;
        layout(r32f, binding = 0) writeonly uniform highp image2D red;
        layout(r32f, binding = 1) writeonly uniform highp image2D green;
        layout(r32f, binding = 2) writeonly uniform highp image2D blue;
        layout(r32f, binding = 3) writeonly uniform highp image2D luma;
        layout(std430, binding = 0) readonly buffer Linear { highp float linear[]; };
        uniform ivec4 cell;
        void main() {
            ivec2 p = ivec2(gl_GlobalInvocationID.xy);
            if (any(greaterThanEqual(p, imageSize(luma)))) return;
            float sums[3] = float[3](0.0, 0.0, 0.0);
            for (int dy = 0; dy <= 1; dy++) {
                for (int dx = 0; dx <= 1; dx++) {
                    ivec2 q = 2 * p + ivec2(dx, dy);
                    int k = dy * 2 + dx;
                    float v = linear[k * 65536 + int(texelFetch(raw, q, 0).r & 0xFFFFu)];
                    precise float s = sums[cell[k]] + v;
                    sums[cell[k]] = s;
                }
            }
            precise float g = sums[1] / 2.0;
            precise float y = 0.2126 * sums[0] + 0.7152 * g + 0.0722 * sums[2];
            imageStore(red, p, vec4(sums[0]));
            imageStore(green, p, vec4(g));
            imageStore(blue, p, vec4(sums[2]));
            imageStore(luma, p, vec4(y));
        }
    """

    /**
     * `Render.pixel` and `Render.encode` per pixel of the R, G, B planes, written as packed ARGB (one uint per
     * pixel, buffer 0). Buffer 1 holds the parameters (gains 3, matrix 9, then exposure, highlight knee, shoulder,
     * white), buffer 2 the shading map's gains (four per grid point) when `shadingColumns` > 0, buffer 3 the
     * 65 536-entry sRGB table, four codes per uint.
     */
    val RENDER = """
        ${Glsl.LOCAL_2D}
        layout(binding = 0) uniform highp sampler2D red;
        layout(binding = 1) uniform highp sampler2D green;
        layout(binding = 2) uniform highp sampler2D blue;
        layout(std430, binding = 0) writeonly buffer Out { highp uint argb[]; };
        layout(std430, binding = 1) readonly buffer Params { highp float params[]; };
        layout(std430, binding = 2) readonly buffer Shading { highp float shadingGains[]; };
        layout(std430, binding = 3) readonly buffer Lut { highp uint lut[]; };
        uniform int shadingColumns;
        uniform int shadingRows;
        uniform ivec2 rawSize;
        float gainAt(int x0, int y0, float fx, float fy, int channel) {
            int c = shadingColumns;
            float g00 = shadingGains[(y0 * c + x0) * 4 + channel];
            float g10 = shadingGains[(y0 * c + x0 + 1) * 4 + channel];
            float g01 = shadingGains[((y0 + 1) * c + x0) * 4 + channel];
            float g11 = shadingGains[((y0 + 1) * c + x0 + 1) * 4 + channel];
            precise float top = g00 + (g10 - g00) * fx;
            precise float bottom = g01 + (g11 - g01) * fx;
            precise float g = top + (bottom - top) * fy;
            return g;
        }
        vec3 shadeAt(ivec2 p, ivec2 size) {
            if (shadingColumns == 0) return vec3(1.0);
            precise float sx = float(rawSize.x) / float(size.x);
            precise float sy = float(rawSize.y) / float(size.y);
            precise float x = (float(p.x) + 0.5) * sx - 0.5;
            precise float y = (float(p.y) + 0.5) * sy - 0.5;
            float lastX = float(shadingColumns - 1);
            float lastY = float(shadingRows - 1);
            precise float gx = clamp(x / float(rawSize.x - 1) * lastX, 0.0, lastX);
            precise float gy = clamp(y / float(rawSize.y - 1) * lastY, 0.0, lastY);
            int x0 = min(int(gx), shadingColumns - 2);
            int y0 = min(int(gy), shadingRows - 2);
            precise float fx = gx - float(x0);
            precise float fy = gy - float(y0);
            precise float g = (gainAt(x0, y0, fx, fy, 1) + gainAt(x0, y0, fx, fy, 2)) / 2.0;
            return vec3(gainAt(x0, y0, fx, fy, 0), g, gainAt(x0, y0, fx, fy, 3));
        }
        float tone(float x, float s, float w) {
            if (x <= s) return x;
            if (x >= w) return 1.0;
            precise float a = (x - s) / (1.0 - s);
            precise float top = (w - s) / (1.0 - s);
            precise float t = s + (1.0 - s) * a * (1.0 + a / (top * top)) / (1.0 + a);
            return t;
        }
        uint encode(float linear) {
            int index = clamp(int(linear * 65535.0 + 0.5), 0, 65535);
            return (lut[index >> 2] >> (8u * uint(index & 3))) & 0xFFu;
        }
        void main() {
            ivec2 p = ivec2(gl_GlobalInvocationID.xy);
            ivec2 size = textureSize(red, 0);
            if (any(greaterThanEqual(p, size))) return;
            float v[3] = float[3](texelFetch(red, p, 0).r, texelFetch(green, p, 0).r, texelFetch(blue, p, 0).r);
            vec3 shade = shadeAt(p, size);
            float knee = params[13];
            precise float k = (max(max(v[0], v[1]), v[2]) - knee) / (1.0 - knee);
            float c = clamp(k, 0.0, 1.0);
            precise float t = c * c * (3.0 - 2.0 * c);
            float o[3];
            for (int row = 0; row < 3; row++) {
                precise float sum = 0.0;
                for (int i = 0; i < 3; i++) sum += params[3 + row * 3 + i] * v[i] * shade[i] * params[i];
                precise float e = sum * params[12];
                o[row] = e;
            }
            if (t > 0.0) {
                float m = max(max(o[0], o[1]), o[2]);
                for (int i = 0; i < 3; i++) {
                    precise float b = o[i] + t * (m - o[i]);
                    o[i] = b;
                }
            }
            for (int i = 0; i < 3; i++) o[i] = max(o[i], 0.0);
            float peak = max(max(o[0], o[1]), o[2]);
            if (peak > params[14]) {
                precise float scale = tone(peak, params[14], params[15]) / peak;
                for (int i = 0; i < 3; i++) {
                    precise float s = o[i] * scale;
                    o[i] = s;
                }
            }
            argb[p.y * size.x + p.x] = 0xFF000000u | (encode(o[0]) << 16) | (encode(o[1]) << 8) | encode(o[2]);
        }
    """
}
