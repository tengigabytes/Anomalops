// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.experiment

import io.github.tengigabytes.anomalops.core.imaging.align.Plane
import io.github.tengigabytes.anomalops.core.imaging.align.Similarity
import kotlin.random.Random

/**
 * The scene of the GPU benchmarks, the same as `AlignScene` in `:core:gpu`'s tests: random grids of 256, 64, 16
 * and 4 pixel cells, bilinear, in equal parts, in the 12-bit RAW range, on [WIDTH] x [HEIGHT] planes (the half-size
 * plane of a 12.5 MP RAW), so every pyramid level has detail.
 */
internal object BenchScene {
    const val WIDTH = 2040
    const val HEIGHT = 1536
    private const val RANGE = 4000f
    private const val GRID_SEED = 10
    private const val PAD = 4
    private val cells = intArrayOf(256, 64, 16, 4)
    private val grids = cells.mapIndexed { k, c ->
        val r = Random(GRID_SEED + k)
        val gw = WIDTH / c + 2 * PAD
        val gh = HEIGHT / c + 2 * PAD
        Plane(gw, gh, FloatArray(gw * gh) { r.nextFloat() })
    }

    /** The scene seen through [view] (`frame(view.map(p)) = scene(p)`) with uniform noise of ±[noise]. */
    fun render(view: Similarity, noise: Float, noiseSeed: Int): Plane {
        val r = Random(noiseSeed)
        val cx = Similarity.centre(WIDTH)
        val cy = Similarity.centre(HEIGHT)
        return Plane(
            WIDTH,
            HEIGHT,
            FloatArray(WIDTH * HEIGHT) { i ->
                val x = cx + (i % WIDTH - cx - view.dx) / view.scale
                val y = cy + (i / WIDTH - cy - view.dy) / view.scale
                var v = 0f
                cells.forEachIndexed { k, c -> v += grids[k].sample(x / c + PAD, y / c + PAD) }
                RANGE * v / cells.size + noise * (2 * r.nextFloat() - 1)
            },
        )
    }
}
