// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.checklist

import io.github.tengigabytes.anomalops.core.profile.LensFilter

/** FR-58: the five items shown before dive lock, in display order. */
enum class CheckItem { BATTERY, STORAGE, TORCH, FILTER, DIVE_COMPUTER_CLOCK }

enum class CheckStatus {
    /** The app measured it and it is fine. */
    OK,

    /** The app measured it and the diver should act before the dive. */
    WARN,

    /** The app cannot tell (or could not read it); the diver has to look. */
    LOOK,
}

data class CheckResult(val item: CheckItem, val status: CheckStatus)

/**
 * What the app knows just before dive lock. Null means it could not be read. [shotsLeft] comes from the FR-67
 * capacity estimate; [torchOn] from the camera service's torch callback.
 */
data class PreDiveState(val batteryPercent: Int?, val shotsLeft: Int?, val torchOn: Boolean?, val filter: LensFilter)

/**
 * FR-58: one page, one confirmation. Warnings inform and never block: the diver confirms with one press whatever
 * the page shows, since only the diver knows whether a low battery still covers this dive.
 */
object PreDiveChecklist {
    /**
     * Proposed: NFR-3 budgets 35 % for a 90-minute dive; 50 % leaves 15 points for the time before and after.
     */
    const val MIN_BATTERY_PERCENT = 50

    /** Proposed: NFR-1's session is 300 photos and 10 bursts of about 80 frames (docs/test/m2-instrumented.md). */
    const val MIN_SHOTS_LEFT = 1_100

    fun evaluate(state: PreDiveState): List<CheckResult> = CheckItem.entries.map { item ->
        CheckResult(item, status(item, state))
    }

    private fun status(item: CheckItem, state: PreDiveState): CheckStatus = when (item) {
        CheckItem.BATTERY -> atLeast(state.batteryPercent, MIN_BATTERY_PERCENT)

        CheckItem.STORAGE -> atLeast(state.shotsLeft, MIN_SHOTS_LEFT)

        // UNVERIFIED(G1): FR-58's "flash off" read as the phone's torch, which would light and heat the housing;
        // the app itself never fires the flash (FR-81).
        CheckItem.TORCH -> when (state.torchOn) {
            null -> CheckStatus.LOOK
            true -> CheckStatus.WARN
            false -> CheckStatus.OK
        }

        // The app knows the setting, not what is screwed onto the port; the page shows the setting to compare.
        CheckItem.FILTER -> CheckStatus.LOOK

        // FR-44 aligns photos to the dive computer; only the diver can compare the two clocks.
        CheckItem.DIVE_COMPUTER_CLOCK -> CheckStatus.LOOK
    }

    private fun atLeast(value: Int?, minimum: Int): CheckStatus = when {
        value == null -> CheckStatus.LOOK
        value >= minimum -> CheckStatus.OK
        else -> CheckStatus.WARN
    }
}
