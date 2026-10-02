// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.core.gpu

import io.github.tengigabytes.anomalops.core.imaging.align.AlignOptions
import io.github.tengigabytes.anomalops.core.imaging.align.GlobalAligner
import io.github.tengigabytes.anomalops.core.imaging.align.Region
import io.github.tengigabytes.anomalops.core.imaging.align.Similarity

/**
 * `GlobalAligner` with its costs and normal equations computed on the GPU (ADR-0017 step 4): the search and the
 * Gauss-Newton rounds are the CPU's own code, so only the sums differ. Needs a current [GlesContext].
 */
class GpuGlobalAligner(private val options: AlignOptions = AlignOptions()) : AutoCloseable {
    private val search = GlobalAligner(options)
    private val costs = MeanSquaredDiffKernel()
    private val gaussNewton = GaussNewtonKernel()

    fun align(reference: GpuPyramid, frame: GpuPyramid): Similarity {
        require(reference.top == frame.top) { "pyramids of different depth" }
        val base = reference[0]
        val full = base.width to base.height
        val region = Region.inner(base.width, base.height, options.margin)
        return search.align(
            reference.top,
            costs = { level, candidates ->
                val ref = reference[level]
                val levelRegion = Region.inner(ref.width, ref.height, options.margin)
                costs.evaluate(ref, frame[level], candidates, level, levelRegion, options.sampleStep, full)
            },
            equations = { gaussNewton.equations(base, frame[0], it, region, options.sampleStep) },
        )
    }

    override fun close() {
        costs.close()
        gaussNewton.close()
    }
}
