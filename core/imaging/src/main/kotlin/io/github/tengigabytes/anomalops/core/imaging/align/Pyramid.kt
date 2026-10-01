// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.core.imaging.align

/**
 * Level 0 is the plane itself; each level above halves it (2 x 2 means) until the next would be smaller than
 * [minSize] on its short side or [maxLevels] is reached.
 */
class Pyramid(base: Plane, minSize: Int = DEFAULT_MIN_SIZE, maxLevels: Int = DEFAULT_MAX_LEVELS) {
    val levels: List<Plane> = buildList {
        add(base)
        while (size <= maxLevels && minOf(last().width, last().height) / 2 >= minSize) add(last().half())
    }

    val top: Int get() = levels.lastIndex

    operator fun get(level: Int): Plane = levels[level]

    companion object {
        const val DEFAULT_MIN_SIZE = 48
        const val DEFAULT_MAX_LEVELS = 6
    }
}

/**
 * Mean squared difference between [reference] at the points of a regular grid with spacing [step] inside
 * [region] and [frame] at the points [transform] maps them to, both planes on the pyramid level the transform is
 * for, of full-size planes [full] (width to height). Squared rather than absolute so the cost is a parabola near its
 * minimum for [TileAligner]'s sub-pixel fit ([parabola]), whose candidates all share one fractional offset; across
 * fractional offsets it is biased when the frames differ in noise or sharpness (see [GaussNewton]). Infinity when
 * fewer than half the points land in the frame.
 */
internal fun meanSquaredDiff(
    reference: Plane,
    frame: Plane,
    transform: Similarity.LevelTransform,
    region: Region,
    step: Int,
    full: Pair<Int, Int>,
): Float {
    var sum = 0.0
    var used = 0
    var total = 0
    for (y in region.top until region.bottom step step) {
        val fy = transform.mapY(y.toFloat(), full.second)
        for (x in region.left until region.right step step) {
            total++
            val v = frame.sample(transform.mapX(x.toFloat(), full.first), fy)
            if (!v.isNaN()) {
                val d = reference[x, y] - v
                sum += d * d
                used++
            }
        }
    }
    return if (used * 2 < total || used == 0) Float.POSITIVE_INFINITY else (sum / used).toFloat()
}

/** A rectangle of pixels, [left] and [top] inclusive, [right] and [bottom] exclusive. */
data class Region(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    companion object {
        /** The centre of a [width] x [height] plane, [margin] (a fraction per side) left out. */
        fun inner(width: Int, height: Int, margin: Float): Region {
            val mx = (width * margin).toInt()
            val my = (height * margin).toInt()
            return Region(mx, my, width - mx, height - my)
        }
    }
}
