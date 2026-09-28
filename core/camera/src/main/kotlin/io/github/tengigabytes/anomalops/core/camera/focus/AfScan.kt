// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.core.camera.focus

import android.hardware.camera2.CaptureResult

/** How one AUTO scan ended (FR-31, FR-35). */
enum class ScanOutcome {
    /** The HAL reported `FOCUSED_LOCKED`; taken as is, although it can be wrong on featureless targets. */
    LOCKED,

    /** The HAL reported `NOT_FOCUSED_LOCKED`, or the trigger capture failed. */
    FAILED,

    /** No lock within the budget. */
    TIMED_OUT,
}

/**
 * Judges one `AF_TRIGGER_START` scan from the capture results that follow it. Results before the trigger's own
 * result are stale (they may still show an earlier lock) and are ignored. The search starts at the trigger's
 * result and has [budgetNs] (FR-35: 0.5 s); if that result never comes, the scan times out [triggerLimitNs] after
 * the trigger was sent. Measured on the Pixel 10 Pro: lock 30–250 ms after the search starts and at most 455 ms
 * after sending (docs/test/m4-af-timeline.md).
 */
class AfScan(
    private val sentNs: Long,
    private val budgetNs: Long = BUDGET_NS,
    private val triggerLimitNs: Long = TRIGGER_LIMIT_NS,
) {
    private var firstFrame: Long? = null
    private var searchStartNs = 0L

    var outcome: ScanOutcome? = null
        private set

    /** From sending the trigger to the outcome. */
    var totalMs = 0.0
        private set

    /** From the trigger's result, where the search starts, to the outcome (FR-35); [totalMs] if it never came. */
    var searchMs = 0.0
        private set

    /** The trigger's own result: the search starts here. Returns the outcome when this result settles it. */
    fun triggered(frameNumber: Long, afState: Int?, nowNs: Long): ScanOutcome? {
        if (outcome != null) return null
        firstFrame = frameNumber
        searchStartNs = nowNs
        return result(frameNumber, afState, nowNs)
    }

    /** Any later result. Returns the outcome once, on the result that settles it; null otherwise. */
    fun result(frameNumber: Long, afState: Int?, nowNs: Long): ScanOutcome? {
        if (outcome != null) return null
        val first = firstFrame
        val settled = when {
            first == null -> ScanOutcome.TIMED_OUT.takeIf { nowNs - sentNs > triggerLimitNs }
            frameNumber < first -> null
            afState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED -> ScanOutcome.LOCKED
            afState == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED -> ScanOutcome.FAILED
            nowNs - searchStartNs > budgetNs -> ScanOutcome.TIMED_OUT
            else -> null
        }
        return settled?.also { finish(it, nowNs) }
    }

    /** The trigger capture failed. */
    fun failed(nowNs: Long): ScanOutcome? = if (outcome != null) null else ScanOutcome.FAILED.also { finish(it, nowNs) }

    private fun finish(settled: ScanOutcome, nowNs: Long) {
        outcome = settled
        totalMs = (nowNs - sentNs) / NS_PER_MS
        searchMs = if (firstFrame == null) totalMs else (nowNs - searchStartNs) / NS_PER_MS
    }

    companion object {
        const val BUDGET_NS = 500_000_000L
        const val TRIGGER_LIMIT_NS = 1_000_000_000L
        private const val NS_PER_MS = 1e6
    }
}
