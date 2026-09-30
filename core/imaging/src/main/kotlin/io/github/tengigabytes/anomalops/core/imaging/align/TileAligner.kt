// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.core.imaging.align

import kotlin.math.ceil

/**
 * The residual shift of each tile after the whole-frame [Similarity]: parallax, a fish or a soft coral that moved
 * on its own (FR-17's per-tile rejection, FR-33's local fit). Tiles of [AlignOptions.tileSize] level-0 pixels, no
 * overlap. Per tile: an exhaustive ±search on level [TILE_LEVEL], ±1 pixel on each level below, then a sub-pixel
 * parabola on level 0. The best mean squared difference is kept as [TileField.cost] so a caller can reject tiles
 * that did not match.
 *
 * A tile with little texture has a cost that hardly changes with the shift, and its fit follows whatever edge
 * lies at its border. Tiles whose texture (mean squared gradient) is below [AlignOptions.minTextureRatio] of the
 * median tile keep the whole-frame transform (zero residual) and are marked untrusted.
 */
class TileAligner(private val options: AlignOptions = AlignOptions()) {
    fun align(reference: Pyramid, frame: Pyramid, global: Similarity): TileField {
        val base = reference[0]
        val size = options.tileSize
        val cols = base.width / size
        val rows = base.height / size
        val field = TileField(size, cols, rows)
        val startLevel = minOf(TILE_LEVEL, reference.top)
        for (row in 0 until rows) {
            for (col in 0 until cols) {
                val tile = Region(col * size, row * size, (col + 1) * size, (row + 1) * size)
                val index = row * cols + col
                field.texture[index] = texture(base, tile)
                alignTile(reference, frame, global, tile, startLevel, field, index)
            }
        }
        val median = field.texture.sorted().let { it[it.size / 2] }
        for (i in field.texture.indices) {
            field.trusted[i] = field.texture[i] >= options.minTextureRatio * median
            if (!field.trusted[i]) {
                field.dx[i] = 0f
                field.dy[i] = 0f
            }
        }
        return field
    }

    @Suppress("LongParameterList") // One tile's inputs and where its result goes; a holder would only rename them.
    private fun alignTile(
        reference: Pyramid,
        frame: Pyramid,
        global: Similarity,
        tile: Region,
        startLevel: Int,
        field: TileField,
        index: Int,
    ) {
        val full = reference[0].width to reference[0].height
        fun cost(level: Int, u: Float, v: Float): Float {
            val f = 1 shl level
            val region = Region(tile.left / f, tile.top / f, tile.right / f, tile.bottom / f)
            val shifted = global.copy(dx = global.dx + u, dy = global.dy + v).atLevel(level)
            return meanSquaredDiff(reference[level], frame[level], shifted, region, 1, full)
        }
        val radius = ceil(options.tileSearchPx.toFloat() / (1 shl startLevel)).toInt()
        var best = search(startLevel, 0f, 0f, radius, ::cost)
        for (level in startLevel - 1 downTo 0) best = search(level, best.first, best.second, 1, ::cost)
        val (u, v) = best
        val du = parabola(cost(0, u - 1, v), cost(0, u, v), cost(0, u + 1, v), 1f)
        val dv = parabola(cost(0, u, v - 1), cost(0, u, v), cost(0, u, v + 1), 1f)
        field.dx[index] = u + du
        field.dy[index] = v + dv
        field.cost[index] = cost(0, u + du, v + dv)
    }

    /** Mean squared central-difference gradient of [plane] inside [tile], one pixel in from its edges. */
    private fun texture(plane: Plane, tile: Region): Float {
        var sum = 0.0
        var n = 0
        for (y in maxOf(tile.top, 1) until minOf(tile.bottom, plane.height - 1)) {
            for (x in maxOf(tile.left, 1) until minOf(tile.right, plane.width - 1)) {
                val gx = (plane[x + 1, y] - plane[x - 1, y]) / 2
                val gy = (plane[x, y + 1] - plane[x, y - 1]) / 2
                sum += gx * gx + gy * gy
                n++
            }
        }
        return if (n == 0) 0f else (sum / n).toFloat()
    }

    /** The integer shift (level-0 pixels) within ±[radius] steps of [level] around ([u], [v]) with the least cost. */
    private fun search(
        level: Int,
        u: Float,
        v: Float,
        radius: Int,
        cost: (Int, Float, Float) -> Float,
    ): Pair<Float, Float> {
        val step = (1 shl level).toFloat()
        var best = u to v
        var bestCost = Float.POSITIVE_INFINITY
        for (j in -radius..radius) {
            for (i in -radius..radius) {
                val c = cost(level, u + i * step, v + j * step)
                if (c < bestCost) {
                    bestCost = c
                    best = (u + i * step) to (v + j * step)
                }
            }
        }
        return best
    }

    private companion object {
        /** Level 2 (a quarter size) keeps a 32-pixel tile at 8 x 8, still enough texture to match. */
        const val TILE_LEVEL = 2
    }
}

/**
 * Per-tile residual shifts on top of the whole-frame transform, row-major, in level-0 pixels of the frame;
 * [cost] is each tile's best mean squared difference (infinite when the tile left the frame), [texture] the
 * reference tile's mean squared gradient, and [trusted] false where the texture was too low to fit a shift.
 */
class TileField(val tileSize: Int, val cols: Int, val rows: Int) {
    val dx = FloatArray(cols * rows)
    val dy = FloatArray(cols * rows)
    val cost = FloatArray(cols * rows)
    val texture = FloatArray(cols * rows)
    val trusted = BooleanArray(cols * rows)

    fun index(col: Int, row: Int): Int = row * cols + col
}
