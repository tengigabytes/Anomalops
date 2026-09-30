// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.core.imaging.align

/** A frame's alignment to the reference: the whole-frame transform, then the per-tile residuals. */
class Alignment(val global: Similarity, val tiles: TileField)

/**
 * Aligns frames of a burst to one reference (ADR-0015; FR-17 low-light merge, FR-33 focus stacking, FR-18 / FR-19
 * later). Build it once per burst: the reference pyramid is reused for every frame. A CPU reference
 * implementation; FR-17 asks for the GPU, which can be checked against this.
 */
class FrameAligner(reference: Plane, private val options: AlignOptions = AlignOptions()) {
    private val referencePyramid = Pyramid(reference)

    fun align(frame: Plane): Alignment {
        val base = referencePyramid[0]
        require(frame.width == base.width && frame.height == base.height) {
            "frame ${frame.width}x${frame.height} against reference ${base.width}x${base.height}"
        }
        val framePyramid = Pyramid(frame, maxLevels = referencePyramid.top)
        val global = GlobalAligner(options).align(referencePyramid, framePyramid)
        return Alignment(global, TileAligner(options).align(referencePyramid, framePyramid, global))
    }

    /** [frame] resampled onto the reference grid with only the whole-frame transform; NaN where it has no data. */
    fun warp(frame: Plane, global: Similarity): Plane {
        val out = Plane(frame.width, frame.height)
        val transform = global.atLevel(0)
        for (y in 0 until frame.height) {
            val fy = transform.mapY(y.toFloat(), frame.height)
            for (x in 0 until frame.width) out[x, y] = frame.sample(transform.mapX(x.toFloat(), frame.width), fy)
        }
        return out
    }
}
