// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.core.gpu

/** GLSL shared by the kernels; each mirrors a CPU function of `:core:imaging` so results can be compared. */
internal object Glsl {
    /** 2-D work groups of [GpuPlane.LOCAL] x [GpuPlane.LOCAL]. */
    val LOCAL_2D = "layout(local_size_x = ${GpuPlane.LOCAL}, local_size_y = ${GpuPlane.LOCAL}) in;"

    /** A quiet NaN, which `Plane` uses for "no data". */
    const val NAN = "uintBitsToFloat(0x7fc00000u)"

    /**
     * `Plane.sample`: bilinear at a fractional position, false outside the pixel centres (also for NaN), with
     * the same clamping and the same order of operations; `precise` so it rounds as the CPU does.
     */
    const val SAMPLE = """
        bool sampleAt(highp sampler2D tex, ivec2 size, vec2 p, out float v) {
            if (!(p.x >= 0.0 && p.x <= float(size.x - 1) && p.y >= 0.0 && p.y <= float(size.y - 1))) return false;
            int x0 = max(min(int(floor(p.x)), size.x - 2), 0);
            int y0 = max(min(int(floor(p.y)), size.y - 2), 0);
            float fx = p.x - float(x0);
            float fy = p.y - float(y0);
            int x1 = min(x0 + 1, size.x - 1);
            int y1 = min(y0 + 1, size.y - 1);
            float a = texelFetch(tex, ivec2(x0, y0), 0).r;
            float b = texelFetch(tex, ivec2(x1, y0), 0).r;
            float c = texelFetch(tex, ivec2(x0, y1), 0).r;
            float d = texelFetch(tex, ivec2(x1, y1), 0).r;
            precise float top = a + (b - a) * fx;
            precise float bottom = c + (d - c) * fx;
            precise float value = top + (bottom - top) * fy;
            v = value;
            return true;
        }
    """
}
