// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.core.gpu

import io.github.tengigabytes.anomalops.core.imaging.align.Plane
import kotlin.random.Random

/**
 * A synthetic focus bracket for the FR-33 tests: one [WIDTH] x [HEIGHT] scene of random grids (256, 64, 16 and
 * 4 pixel cells in equal parts, 12-bit range), and [FRAMES] frames each sharp in its own horizontal band and
 * box-blurred elsewhere, each with its own uniform noise.
 */
internal object BracketScene {
    const val WIDTH = 1024
    const val HEIGHT = 768
    const val FRAMES = 5
    private const val BLUR = 3
    private const val NOISE = 15f
    private const val NOISE_SEED = 20
    private const val RANGE = 4000f

    val sharp: Plane = scene()
    private val blurred = blur(sharp, BLUR)

    /** Frame [k]: sharp in the k-th band (or everywhere with [sharpEverywhere]), blurred elsewhere, plus noise. */
    fun frame(k: Int, sharpEverywhere: Boolean = false): Plane {
        val r = Random(NOISE_SEED + k)
        val top = k * HEIGHT / FRAMES
        val bottom = (k + 1) * HEIGHT / FRAMES
        return Plane(
            WIDTH,
            HEIGHT,
            FloatArray(WIDTH * HEIGHT) { i ->
                val inBand = sharpEverywhere || i / WIDTH in top until bottom
                (if (inBand) sharp.data[i] else blurred.data[i]) + NOISE * (2 * r.nextFloat() - 1)
            },
        )
    }

    private fun scene(): Plane {
        val cells = intArrayOf(256, 64, 16, 4)
        val grids = cells.mapIndexed { k, c ->
            val r = Random(10 + k)
            val gw = WIDTH / c + 2
            val gh = HEIGHT / c + 2
            Plane(gw, gh, FloatArray(gw * gh) { r.nextFloat() })
        }
        return Plane(
            WIDTH,
            HEIGHT,
            FloatArray(WIDTH * HEIGHT) { i ->
                var v = 0f
                cells.forEachIndexed { k, c ->
                    v += grids[k].sample(
                        (i % WIDTH).toFloat() / c,
                        (i / WIDTH).toFloat() / c,
                    )
                }
                RANGE * v / cells.size
            },
        )
    }

    /** A separable box blur of [radius], edges clamped. */
    private fun blur(p: Plane, radius: Int): Plane {
        fun pass(src: Plane, horizontal: Boolean) = Plane(
            src.width,
            src.height,
            FloatArray(src.data.size) { i ->
                val x = i % src.width
                val y = i / src.width
                var s = 0f
                for (d in -radius..radius) {
                    s += if (horizontal) {
                        src[(x + d).coerceIn(0, src.width - 1), y]
                    } else {
                        src[x, (y + d).coerceIn(0, src.height - 1)]
                    }
                }
                s / (2 * radius + 1)
            },
        )
        return pass(pass(p, true), false)
    }
}
