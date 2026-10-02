// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.core.gpu

/**
 * The kernels of FR-33's candidates A and B on the GPU ([GpuContrastSelect], [GpuLaplacianPyramid]), each the GPU
 * form of a part of `:core:imaging`'s `stack/` with the same order of operations, except that box sums are float
 * where the CPU's summed-area table is double.
 */
internal object StackShaders {
    /** How [BOX_ROWS] reads its input: as is, squared, absolute, or 1 where it equals `frame` (a one-hot map). */
    const val READ_PLAIN = 0
    const val READ_SQUARE = 1
    const val READ_ABS = 2
    const val READ_ONE_HOT = 3

    /**
     * First half of `Filters.box`: each pixel's sum over its row's window of `radius`, clipped at the edges, of the
     * input read as `mode` says. [BOX_COLUMNS] completes it.
     */
    val BOX_ROWS = """
        ${Glsl.LOCAL_2D}
        layout(binding = 0) uniform highp sampler2D src;
        layout(r32f, binding = 0) writeonly uniform highp image2D dst;
        uniform int radius;
        uniform int mode;
        uniform float frame;
        float read(ivec2 p) {
            float v = texelFetch(src, p, 0).r;
            if (mode == $READ_SQUARE) return v * v;
            if (mode == $READ_ABS) return abs(v);
            if (mode == $READ_ONE_HOT) return v == frame ? 1.0 : 0.0;
            return v;
        }
        void main() {
            ivec2 p = ivec2(gl_GlobalInvocationID.xy);
            ivec2 size = imageSize(dst);
            if (any(greaterThanEqual(p, size))) return;
            int x0 = max(p.x - radius, 0);
            int x1 = min(p.x + radius + 1, size.x);
            precise float s = 0.0;
            for (int x = x0; x < x1; x++) s += read(ivec2(x, p.y));
            imageStore(dst, p, vec4(s));
        }
    """

    /** Second half of `Filters.box`: the column sum of [BOX_ROWS]'s row sums, divided by the clipped window's area. */
    val BOX_COLUMNS = """
        ${Glsl.LOCAL_2D}
        layout(binding = 0) uniform highp sampler2D rows;
        layout(r32f, binding = 0) writeonly uniform highp image2D dst;
        uniform int radius;
        void main() {
            ivec2 p = ivec2(gl_GlobalInvocationID.xy);
            ivec2 size = imageSize(dst);
            if (any(greaterThanEqual(p, size))) return;
            int x0 = max(p.x - radius, 0);
            int x1 = min(p.x + radius + 1, size.x);
            int y0 = max(p.y - radius, 0);
            int y1 = min(p.y + radius + 1, size.y);
            precise float s = 0.0;
            for (int y = y0; y < y1; y++) s += texelFetch(rows, ivec2(p.x, y), 0).r;
            precise float mean = s / float((x1 - x0) * (y1 - y0));
            imageStore(dst, p, vec4(mean));
        }
    """

    /** `Filters.laplacian`: 4 p minus the four neighbours, edges clamped. */
    val LAPLACIAN = """
        ${Glsl.LOCAL_2D}
        layout(binding = 0) uniform highp sampler2D src;
        layout(r32f, binding = 0) writeonly uniform highp image2D dst;
        void main() {
            ivec2 p = ivec2(gl_GlobalInvocationID.xy);
            ivec2 size = imageSize(dst);
            if (any(greaterThanEqual(p, size))) return;
            ivec2 hi = size - 1;
            float c = texelFetch(src, p, 0).r;
            float l = texelFetch(src, clamp(p - ivec2(1, 0), ivec2(0), hi), 0).r;
            float r = texelFetch(src, clamp(p + ivec2(1, 0), ivec2(0), hi), 0).r;
            float u = texelFetch(src, clamp(p - ivec2(0, 1), ivec2(0), hi), 0).r;
            float d = texelFetch(src, clamp(p + ivec2(0, 1), ivec2(0), hi), 0).r;
            precise float v = 4.0 * c - l - r - u - d;
            imageStore(dst, p, vec4(v));
        }
    """

    /**
     * Candidate A's first pass for one frame: where its measure (sampler 0) beats the best so far (image 0), the
     * best and the chosen frame (image 1) take it; ties keep the earlier frame. The first frame only writes.
     */
    val SELECT = """
        ${Glsl.LOCAL_2D}
        layout(binding = 0) uniform highp sampler2D measure;
        layout(r32f, binding = 0) uniform highp image2D best;
        layout(r32f, binding = 1) uniform highp image2D choice;
        uniform int first;
        uniform float frame;
        void main() {
            ivec2 p = ivec2(gl_GlobalInvocationID.xy);
            if (any(greaterThanEqual(p, imageSize(best)))) return;
            float m = texelFetch(measure, p, 0).r;
            if (first != 0 || imageLoad(best, p).r < m) {
                imageStore(best, p, vec4(m));
                imageStore(choice, p, vec4(frame));
            }
        }
    """

    /** dst += src (sampler 0 into image 0), as candidate A sums the weights' total in frame order. */
    val ADD = """
        ${Glsl.LOCAL_2D}
        layout(binding = 0) uniform highp sampler2D src;
        layout(r32f, binding = 0) uniform highp image2D dst;
        uniform int first;
        void main() {
            ivec2 p = ivec2(gl_GlobalInvocationID.xy);
            if (any(greaterThanEqual(p, imageSize(dst)))) return;
            float v = texelFetch(src, p, 0).r;
            precise float s = (first != 0 ? 0.0 : imageLoad(dst, p).r) + v;
            imageStore(dst, p, vec4(s));
        }
    """

    /**
     * Candidate A's second pass for one frame and [channels] planes: each sum (images 0..) gains the channel
     * (samplers 2..) times the frame's weight (sampler 0) over the total (sampler 1), or 1 / `frames` where the total
     * is 0. The first frame starts the sums.
     */
    fun blend(channels: Int): String {
        val samplers = (0 until channels).joinToString("\n") {
            "layout(binding = ${it + 2}) uniform highp sampler2D ch$it;"
        }
        val images = (0 until channels).joinToString("\n") {
            "layout(r32f, binding = $it) uniform highp image2D sum$it;"
        }
        val adds = (0 until channels).joinToString("\n") {
            """
            precise float t$it = share * texelFetch(ch$it, p, 0).r;
            precise float s$it = (first != 0 ? 0.0 : imageLoad(sum$it, p).r) + t$it;
            imageStore(sum$it, p, vec4(s$it));
            """
        }
        return """
            ${Glsl.LOCAL_2D}
            layout(binding = 0) uniform highp sampler2D weight;
            layout(binding = 1) uniform highp sampler2D total;
            $samplers
            $images
            uniform int first;
            uniform float frames;
            void main() {
                ivec2 p = ivec2(gl_GlobalInvocationID.xy);
                if (any(greaterThanEqual(p, textureSize(weight, 0)))) return;
                float t = texelFetch(total, p, 0).r;
                precise float share = t > 0.0 ? texelFetch(weight, p, 0).r / t : 1.0 / frames;
                $adds
            }
        """
    }

    /**
     * One band of `LaplacianPyramidStack.bands`: the level (sampler 0) minus the next coarser one (sampler 1)
     * brought back to this size as `Filters.double` does (bilinear at (x + 0.5) / 2 - 0.5, clamped).
     */
    val BAND = """
        ${Glsl.LOCAL_2D}
        layout(binding = 0) uniform highp sampler2D fine;
        layout(binding = 1) uniform highp sampler2D coarse;
        layout(r32f, binding = 0) writeonly uniform highp image2D dst;
        uniform int collapse;
        ${Glsl.SAMPLE}
        void main() {
            ivec2 p = ivec2(gl_GlobalInvocationID.xy);
            if (any(greaterThanEqual(p, imageSize(dst)))) return;
            ivec2 cs = textureSize(coarse, 0);
            precise float sx = clamp((float(p.x) + 0.5) / 2.0 - 0.5, 0.0, float(cs.x - 1));
            precise float sy = clamp((float(p.y) + 0.5) / 2.0 - 0.5, 0.0, float(cs.y - 1));
            float up;
            sampleAt(coarse, cs, vec2(sx, sy), up);
            float f = texelFetch(fine, p, 0).r;
            precise float v = collapse != 0 ? f + up : f - up;
            imageStore(dst, p, vec4(v));
        }
    """

    /**
     * Candidate B for one band level of one frame: where the frame's luma score (sampler 0) beats the best (image 0;
     * ties keep the earlier frame), the best takes it; the mask (image 1) is 1 there and 0 elsewhere, for
     * [COPY_WHERE]. The first frame wins everywhere.
     */
    val MARK = """
        ${Glsl.LOCAL_2D}
        layout(binding = 0) uniform highp sampler2D score;
        layout(r32f, binding = 0) uniform highp image2D best;
        layout(r32f, binding = 1) writeonly uniform highp image2D mask;
        uniform int first;
        void main() {
            ivec2 p = ivec2(gl_GlobalInvocationID.xy);
            if (any(greaterThanEqual(p, imageSize(best)))) return;
            float s = texelFetch(score, p, 0).r;
            bool wins = first != 0 || imageLoad(best, p).r < s;
            if (wins) imageStore(best, p, vec4(s));
            imageStore(mask, p, vec4(wins ? 1.0 : 0.0));
        }
    """

    /** dst (image 0) takes src (sampler 1) where the mask (sampler 0) is set. */
    val COPY_WHERE = """
        ${Glsl.LOCAL_2D}
        layout(binding = 0) uniform highp sampler2D mask;
        layout(binding = 1) uniform highp sampler2D src;
        layout(r32f, binding = 0) writeonly uniform highp image2D dst;
        void main() {
            ivec2 p = ivec2(gl_GlobalInvocationID.xy);
            if (any(greaterThanEqual(p, imageSize(dst)))) return;
            if (texelFetch(mask, p, 0).r > 0.5) imageStore(dst, p, vec4(texelFetch(src, p, 0).r));
        }
    """

    /** dst = src / `divisor` (sampler 0 into image 0), for B's mean of the coarsest level. */
    val SCALE = """
        ${Glsl.LOCAL_2D}
        layout(binding = 0) uniform highp sampler2D src;
        layout(r32f, binding = 0) writeonly uniform highp image2D dst;
        uniform float divisor;
        void main() {
            ivec2 p = ivec2(gl_GlobalInvocationID.xy);
            if (any(greaterThanEqual(p, imageSize(dst)))) return;
            precise float v = texelFetch(src, p, 0).r / divisor;
            imageStore(dst, p, vec4(v));
        }
    """
}
