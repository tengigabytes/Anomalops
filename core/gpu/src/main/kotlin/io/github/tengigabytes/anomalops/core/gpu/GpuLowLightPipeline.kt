// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.core.gpu

import io.github.tengigabytes.anomalops.core.imaging.align.Alignment
import io.github.tengigabytes.anomalops.core.imaging.develop.AutoLook
import io.github.tengigabytes.anomalops.core.imaging.develop.Demosaic
import io.github.tengigabytes.anomalops.core.imaging.develop.LookOptions
import io.github.tengigabytes.anomalops.core.imaging.develop.NoiseProfile
import io.github.tengigabytes.anomalops.core.imaging.develop.RawFrame
import io.github.tengigabytes.anomalops.core.imaging.develop.RenderOptions
import io.github.tengigabytes.anomalops.core.imaging.develop.ShadingMap
import io.github.tengigabytes.anomalops.core.imaging.merge.BurstReference
import io.github.tengigabytes.anomalops.core.imaging.merge.LowLightMerge

/**
 * One burst for [GpuLowLightPipeline]: equal-exposure RAW [frames] of one lens (at least two), and what the capture
 * reported for rendering them: white-balance [gains] (red, green, blue), the colour [matrix] (camera to linear
 * sRGB, row by row), the lens [shading] map, the sensor's [noise] profile and the gain the camera left to be
 * applied after the RAW ([postRawGain], boost / 100). The last three may be missing.
 */
class LowLightBurst(
    val frames: List<RawFrame>,
    val gains: FloatArray,
    val matrix: FloatArray,
    val shading: ShadingMap? = null,
    val noise: NoiseProfile? = null,
    val postRawGain: Float? = null,
)

/** The finished picture, packed ARGB as `Render.toArgb` makes it, and how it came about. */
class LowLightPicture(
    val argb: IntArray,
    val width: Int,
    val height: Int,
    /** The frame the others were merged onto. */
    val reference: Int,
    val options: RenderOptions,
    /** One per merged frame, in burst order without the reference. */
    val alignments: List<Alignment>,
)

/**
 * FR-17 from RAW frames to a picture, the one entry to the GPU pipeline (ADR-0017): the sharpest frame is the
 * reference ([BurstReference]); every frame is decoded at half size ([GpuDevelop]), aligned and merged onto it
 * ([GpuLowLightMerge]) with the noise level from the sensor's profile; [AutoLook] sets exposure, colour denoising
 * and sharpening, and the merge is rendered and read back. Planes are kept between bursts of the same size.
 * Needs a current [GlesContext], and must be closed on its thread.
 */
class GpuLowLightPipeline(private val look: LookOptions = LookOptions()) : AutoCloseable {
    private val develop = GpuDevelop()
    private val merge = GpuLowLightMerge()
    private var reference: List<GpuPlane> = emptyList()
    private var frame: List<GpuPlane> = emptyList()

    fun render(burst: LowLightBurst): LowLightPicture {
        val frames = burst.frames
        require(frames.size >= 2) { "a burst needs at least two frames, got ${frames.size}" }
        val first = frames[0]
        require(frames.all { it.width == first.width && it.height == first.height && it.layout == first.layout }) {
            "frames differ in size or colour layout"
        }
        val best = BurstReference.sharpest(frames)
        planesFor(first.width / 2, first.height / 2)
        develop.halfSize(frames[best], into = reference)
        val sigma = burst.noise?.lumaSigma(frames[best])
            ?: LowLightMerge.estimateNoise(Demosaic.halfSize(frames[best]).luma())
        val alignments = mutableListOf<Alignment>()
        val merged = merged(frames, best, sigma, alignments)
        try {
            val gain = burst.postRawGain
            val options =
                AutoLook.options(frames[best], burst.gains, burst.matrix, burst.shading, look, postRawGain = gain)
            val argb =
                develop.toArgb(merged, burst.gains, burst.matrix, options, burst.shading, first.width, first.height)
            return LowLightPicture(argb, first.width / 2, first.height / 2, best, options, alignments)
        } finally {
            merged.forEach { it.close() }
        }
    }

    /** Every frame but [best] aligned and added to it; the merged planes are the caller's to close. */
    private fun merged(
        frames: List<RawFrame>,
        best: Int,
        sigma: Float,
        alignments: MutableList<Alignment>,
    ): List<GpuPlane> {
        val accumulator = merge.start(reference[LUMA], reference.take(LUMA), sigma)
        var finished = false
        try {
            for (k in frames.indices) {
                if (k == best) continue
                develop.halfSize(frames[k], into = frame)
                alignments += accumulator.add(frame[LUMA], frame.take(LUMA))
            }
            return accumulator.finish().also { finished = true }
        } finally {
            // finish() hands the sums over; an abandoned burst must free them itself.
            if (!finished) accumulator.close()
        }
    }

    override fun close() {
        (reference + frame).forEach { it.close() }
        merge.close()
        develop.close()
    }

    private fun planesFor(width: Int, height: Int) {
        if (reference.firstOrNull()?.let { it.width == width && it.height == height } == true) return
        (reference + frame).forEach { it.close() }
        reference = List(PLANES) { GpuPlane(width, height, PlaneFormat.FLOAT32) }
        frame = List(PLANES) { GpuPlane(width, height, PlaneFormat.FLOAT32) }
    }

    private companion object {
        const val PLANES = 4
        const val LUMA = 3
    }
}
