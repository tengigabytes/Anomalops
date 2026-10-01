// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.core.camera.focus

/** `LENS_INFO_FOCUS_DISTANCE_CALIBRATION`, read at run time (ADR-0014). */
enum class FocusCalibration { CALIBRATED, APPROXIMATE, UNCALIBRATED }

/**
 * What the planner needs of one lens, all read at run time from `CameraCharacteristics` (ADR-0014):
 * `LENS_INFO_MINIMUM_FOCUS_DISTANCE`, `LENS_INFO_HYPERFOCAL_DISTANCE`, the focal length (for the magnification
 * correction; 0 leaves it out) and the calibration. [table] is the lens's measured correction (T4), if any; it is
 * ignored when the lens no longer reports what it reported when measured ([FocusCalibrationTable.appliesTo]).
 */
data class BracketLens(
    val minFocusDiopters: Float,
    val hyperfocalDiopters: Float,
    val calibration: FocusCalibration,
    val focalLengthMm: Float = 0f,
    val table: FocusCalibrationTable? = null,
) {
    /** [table] if it still applies to this lens. */
    val validTable: FocusCalibrationTable? get() = table?.takeIf { it.appliesTo(calibration, minFocusDiopters) }

    fun trueDiopters(reported: Float): Float = validTable?.trueDiopters(reported) ?: reported

    fun reportedFor(trueDiopters: Float): Float = validTable?.reportedFor(trueDiopters) ?: trueDiopters
}

/**
 * A focus bracket for FR-33: [requestedDiopters] are the `LENS_FOCUS_DISTANCE` values to send, nearest first.
 * [covered] is the true range (diopters, near to far) the frames cover. [truncated] means the subject is deeper
 * than [BracketPlanner.maxFrames] frames reach (FR-33: shoot anyway and suggest backing off); [tooClose] means
 * its near end is closer than the lens focuses.
 */
data class BracketPlan(
    val requestedDiopters: List<Float>,
    val covered: ClosedFloatingPointRange<Float>,
    val truncated: Boolean,
    val tooClose: Boolean,
)

/**
 * Plans FR-33's focus bracket in diopters (ADR-0014, section 3): each frame is sharp over about
 * 2 x hyperfocal / (1 + m) diopters (thin lens; m the magnification at that distance), frames step by [overlap]
 * of that, from the subject's near end to its far end. An APPROXIMATE lens gets one more frame of overlap unless
 * its table is [FocusCalibrationTable.precise]; an UNCALIBRATED lens without a table cannot be planned in distances
 * at all (returns null).
 */
class BracketPlanner(val maxFrames: Int = DEFAULT_MAX_FRAMES, private val overlap: Float = DEFAULT_OVERLAP) {
    init {
        require(maxFrames in MIN_FRAMES..MAX_FRAMES) { "FR-33 brackets 5-8 frames, not $maxFrames" }
        require(overlap > 0f && overlap <= 1f) { "overlap $overlap" }
    }

    /** A bracket over the subject from [nearCm] to [farCm] (true distances, nearest first), or null if unplannable. */
    fun plan(lens: BracketLens, nearCm: Float, farCm: Float): BracketPlan? {
        require(nearCm > 0f && farCm >= nearCm) { "subject $nearCm-$farCm cm" }
        val table = lens.validTable
        if (lens.calibration == FocusCalibration.UNCALIBRATED && table == null) return null
        val reach = lens.trueDiopters(lens.minFocusDiopters)
        val nearD = CM_PER_M / nearCm
        val farD = CM_PER_M / farCm
        val tooClose = nearD > reach
        val start = minOf(nearD, reach)
        val precise = table?.precise(lens.hyperfocalDiopters) == true
        val extra = if (lens.calibration == FocusCalibration.APPROXIMATE && !precise) 1 else 0
        val natural = centres(lens, start, farD)
        val wanted = (natural.size + extra).coerceAtLeast(1)
        val count = minOf(wanted, maxFrames)
        val truncated = wanted > maxFrames
        val centres = if (count == natural.size && !truncated) natural else spread(lens, start, farD, count, truncated)
        val requested = centres.map { lens.reportedFor(it).coerceIn(0f, lens.minFocusDiopters) }
        val first = centres.first() + depth(lens, centres.first()) / 2
        val last = centres.last() - depth(lens, centres.last()) / 2
        return BracketPlan(requested, first.coerceAtMost(reach)..last.coerceAtLeast(0f), truncated, tooClose)
    }

    /** Frame centres from [nearD] towards [farD], one overlapped step at a time, until the far end is inside. */
    private fun centres(lens: BracketLens, nearD: Float, farD: Float): List<Float> = buildList {
        var centre = nearD - depth(lens, nearD) / 2
        while (true) {
            add(centre)
            if (centre - depth(lens, centre) / 2 <= farD || size > MAX_STEPS) break
            centre -= overlap * depth(lens, centre)
        }
    }

    /**
     * [count] centres spread evenly over the subject between its near and far half-depth points; when [truncated],
     * the subject's middle is covered and both ends fall short.
     */
    private fun spread(lens: BracketLens, nearD: Float, farD: Float, count: Int, truncated: Boolean): List<Float> {
        val top = nearD - depth(lens, nearD) / 2
        val bottom = (farD + depth(lens, farD) / 2).coerceAtMost(top)
        if (count == 1) return listOf((top + bottom) / 2)
        val middle = (top + bottom) / 2
        val step = overlap * depth(lens, middle)
        // Truncated: frames a full step apart around the middle. Otherwise the subject's range, but never closer
        // than half a step, so the extra frame of an APPROXIMATE lens still widens the cover.
        val span = if (truncated) (count - 1) * step else maxOf(top - bottom, (count - 1) * step / 2)
        return List(count) { middle + span / 2 - span * it / (count - 1) }
    }

    /** Depth of field in diopters at [diopters]: 2 x hyperfocal / (1 + m), m = f / (s - f). */
    private fun depth(lens: BracketLens, diopters: Float): Float {
        val dof = 2 * lens.hyperfocalDiopters
        if (lens.focalLengthMm <= 0f || diopters <= 0f) return dof
        val distanceMm = MM_PER_M / diopters
        val m = lens.focalLengthMm / (distanceMm - lens.focalLengthMm).coerceAtLeast(lens.focalLengthMm)
        return dof / (1 + m)
    }

    companion object {
        const val DEFAULT_MAX_FRAMES = 6

        /** ADR-0014, proposed: step = 0.7 x depth of field. */
        const val DEFAULT_OVERLAP = 0.7f
        private const val MIN_FRAMES = 5
        private const val MAX_FRAMES = 8
        private const val MAX_STEPS = 1_000
        private const val CM_PER_M = 100f
        private const val MM_PER_M = 1_000f
    }
}
