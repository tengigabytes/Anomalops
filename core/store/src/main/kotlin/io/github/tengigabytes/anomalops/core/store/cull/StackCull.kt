// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.core.store.cull

enum class FailReason { BLURRED, CLIPPED_DARK, CLIPPED_BRIGHT }

/** FR-69 for one frame: [score] ranks it within its stack (FR-70); [reasons] empty means it passed. */
data class CullVerdict(val score: Double, val reasons: Set<FailReason>) {
    val failed: Boolean get() = reasons.isNotEmpty()
}

/**
 * FR-69: marks the failed frames of one burst stack from sharpness and clipping. Sharpness is judged relative to
 * the stack's sharpest frame, since the absolute Laplacian variance depends on the scene.
 *
 * Not covered yet: FR-69's "subject in frame" needs subject detection, which this module does not have.
 * UNVERIFIED(G4): every threshold here is a placeholder until it is fitted to the 20 real bursts FR-69 is
 * accepted on (≥ 85 % agreement with a human, docs/product/roadmap.md section 6).
 */
object StackCull {
    /** A frame below this share of the stack's best sharpness is blurred. */
    const val MIN_RELATIVE_SHARPNESS = 0.5

    /** Dark water behind a subject is normal, so far more dark than bright clipping is allowed. */
    const val MAX_DARK_CLIP = 0.5
    const val MAX_BRIGHT_CLIP = 0.05

    /** How much clipping lowers the score against relative sharpness. */
    const val CLIP_WEIGHT = 1.0

    fun judge(frames: List<FrameMetrics>): List<CullVerdict> {
        val best = frames.maxOfOrNull { it.sharpness } ?: return emptyList()
        return frames.map { frame ->
            // A stack with no detail at all cannot be judged on sharpness.
            val relative = if (best > 0.0) frame.sharpness / best else 1.0
            val reasons = buildSet {
                if (relative < MIN_RELATIVE_SHARPNESS) add(FailReason.BLURRED)
                if (frame.darkClip > MAX_DARK_CLIP) add(FailReason.CLIPPED_DARK)
                if (frame.brightClip > MAX_BRIGHT_CLIP) add(FailReason.CLIPPED_BRIGHT)
            }
            CullVerdict(score = relative - CLIP_WEIGHT * (frame.darkClip + frame.brightClip), reasons = reasons)
        }
    }
}
