// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.core.camera.focus

import kotlin.math.abs
import kotlin.math.sqrt

/** One calibration point (ADR-0014, section 4): the sharpest reported focus at a target [trueDiopters] away. */
data class FocusPoint(val reportedDiopters: Float, val trueDiopters: Float)

/**
 * A lens's measured focus correction (ADR-0014, section 4; macro test plan T4): true = [slope] x reported +
 * [offsetDiopters], fitted by least squares over [fittedRange] (reported diopters), with the fit's worst and RMS
 * residuals. It records the [calibration] type and [minFocusDiopters] the lens reported when measured; a lens that
 * reports otherwise at run time (a system update, say) is treated as having no table (ADR-0014, section 1).
 */
data class FocusCalibrationTable(
    val slope: Float,
    val offsetDiopters: Float,
    val fittedRange: ClosedFloatingPointRange<Float>,
    val residualMaxDiopters: Float,
    val residualRmsDiopters: Float,
    val calibration: FocusCalibration,
    val minFocusDiopters: Float,
) {
    init {
        require(slope > 0f) { "slope $slope" }
    }

    /** The true distance, in diopters, of a reported focus distance; extrapolated linearly outside [fittedRange]. */
    fun trueDiopters(reported: Float): Float = slope * reported + offsetDiopters

    /** The `LENS_FOCUS_DISTANCE` to request for a true distance; not clamped to what the lens reaches. */
    fun reportedFor(trueDiopters: Float): Float = (trueDiopters - offsetDiopters) / slope

    /** Whether [reported] lies where the table was measured. */
    fun covers(reported: Float): Boolean = reported in fittedRange

    /** Whether the table still describes a lens reporting [calibration] and [minFocusDiopters] now. */
    fun appliesTo(calibration: FocusCalibration, minFocusDiopters: Float): Boolean =
        calibration == this.calibration && abs(minFocusDiopters - this.minFocusDiopters) <= SAME_DIOPTERS

    /**
     * T4's proposed pass mark: the worst residual within a quarter of the lens's depth of field, 2 x hyperfocal / 4.
     * A table that misses it still converts distances, but a bracket keeps the extra frame of an uncorrected
     * APPROXIMATE lens (ADR-0014, 2026-10-01 note).
     */
    fun precise(hyperfocalDiopters: Float): Boolean = residualMaxDiopters <= 2 * hyperfocalDiopters / QUARTERS

    companion object {
        /** Two readings of the same lens agree within this (diopters). */
        const val SAME_DIOPTERS = 1e-3f
        private const val QUARTERS = 4
        private const val MIN_POINTS = 3

        /** Least-squares fit of true on reported diopters; needs [MIN_POINTS] points at two or more distances. */
        fun fit(
            points: List<FocusPoint>,
            calibration: FocusCalibration,
            minFocusDiopters: Float,
        ): FocusCalibrationTable {
            require(points.size >= MIN_POINTS) { "${points.size} points; at least $MIN_POINTS" }
            val n = points.size.toDouble()
            val meanX = points.sumOf { it.reportedDiopters.toDouble() } / n
            val meanY = points.sumOf { it.trueDiopters.toDouble() } / n
            val sxx = points.sumOf { (it.reportedDiopters - meanX).let { d -> d * d } }
            val sxy = points.sumOf { (it.reportedDiopters - meanX) * (it.trueDiopters - meanY) }
            require(sxx > 0.0) { "all points at one reported distance" }
            val slope = sxy / sxx
            val offset = meanY - slope * meanX
            val residuals = points.map { it.trueDiopters - (slope * it.reportedDiopters + offset) }
            return FocusCalibrationTable(
                slope = slope.toFloat(),
                offsetDiopters = offset.toFloat(),
                fittedRange = points.minOf { it.reportedDiopters }..points.maxOf { it.reportedDiopters },
                residualMaxDiopters = residuals.maxOf { abs(it) }.toFloat(),
                residualRmsDiopters = sqrt(residuals.sumOf { it * it } / n).toFloat(),
                calibration = calibration,
                minFocusDiopters = minFocusDiopters,
            )
        }
    }
}
