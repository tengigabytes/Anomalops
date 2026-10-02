// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.core.gpu

import io.github.tengigabytes.anomalops.core.imaging.align.Plane
import io.github.tengigabytes.anomalops.core.imaging.align.Region
import io.github.tengigabytes.anomalops.core.imaging.align.Similarity
import kotlin.random.Random

/**
 * The scene of the alignment tests: random grids of 256, 64, 16 and 4 pixel cells, bilinear, in equal parts, in
 * the 12-bit RAW range, on [WIDTH] x [HEIGHT] planes (the half-size plane of a 12.5 MP RAW). It has detail at every
 * pyramid level, as a photograph has; a texture of only fine detail (cells of about 5 pixels) fooled the top level
 * of the coarse search on the CPU as well (docs/test/m9-gpu-fr17.md).
 */
internal object AlignScene {
    const val WIDTH = 2040
    const val HEIGHT = 1536
    private const val RANGE = 4000f
    private const val GRID_SEED = 10

    /** Grid cells kept around the plane, so views that scale or shift it still land inside the grids. */
    private const val PAD = 4
    private val cells = intArrayOf(256, 64, 16, 4)
    private val grids = cells.mapIndexed { k, c ->
        val r = Random(GRID_SEED + k)
        val gw = WIDTH / c + 2 * PAD
        val gh = HEIGHT / c + 2 * PAD
        Plane(gw, gh, FloatArray(gw * gh) { r.nextFloat() })
    }

    /**
     * The scene seen through [view] (`frame(view.map(p)) = scene(p)`) with uniform noise of ±[noise] from
     * [noiseSeed]. [local] moves the content inside a region of the frame by a further shift, as a subject moving
     * on its own: there the frame shows what lies that far up and to the left of it.
     */
    fun render(
        view: Similarity,
        noise: Float,
        noiseSeed: Int,
        local: Pair<Region, Pair<Float, Float>>? = null,
    ): Plane {
        val r = Random(noiseSeed)
        val cx = Similarity.centre(WIDTH)
        val cy = Similarity.centre(HEIGHT)
        return Plane(
            WIDTH,
            HEIGHT,
            FloatArray(WIDTH * HEIGHT) { i ->
                var fx = (i % WIDTH).toFloat()
                var fy = (i / WIDTH).toFloat()
                local?.let { (region, shift) ->
                    if (fx.toInt() in region.left until region.right && fy.toInt() in region.top until region.bottom) {
                        fx -= shift.first
                        fy -= shift.second
                    }
                }
                val x = cx + (fx - cx - view.dx) / view.scale
                val y = cy + (fy - cy - view.dy) / view.scale
                var v = 0f
                cells.forEachIndexed { k, c -> v += grids[k].sample(x / c + PAD, y / c + PAD) }
                RANGE * v / cells.size + noise * (2 * r.nextFloat() - 1)
            },
        )
    }
}
