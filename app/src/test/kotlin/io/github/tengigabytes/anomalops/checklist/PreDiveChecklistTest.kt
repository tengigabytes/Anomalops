// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.checklist

import io.github.tengigabytes.anomalops.core.profile.LensFilter
import org.junit.Assert.assertEquals
import org.junit.Test

/** FR-58: every item appears once, in order; measured items are OK or WARN, the rest ask the diver to look. */
class PreDiveChecklistTest {
    private val ready = PreDiveState(batteryPercent = 100, shotsLeft = 5_000, torchOn = false, filter = LensFilter.RED)

    private fun statuses(state: PreDiveState) = PreDiveChecklist.evaluate(state).associate { it.item to it.status }

    @Test
    fun fr58_listsTheFiveItemsInOrder() {
        assertEquals(CheckItem.entries, PreDiveChecklist.evaluate(ready).map { it.item })
    }

    @Test
    fun fr58_readyPhoneLeavesOnlyWhatTheDiverMustLookAt() {
        assertEquals(
            mapOf(
                CheckItem.BATTERY to CheckStatus.OK,
                CheckItem.STORAGE to CheckStatus.OK,
                CheckItem.TORCH to CheckStatus.OK,
                CheckItem.FILTER to CheckStatus.LOOK,
                CheckItem.DIVE_COMPUTER_CLOCK to CheckStatus.LOOK,
            ),
            statuses(ready),
        )
    }

    @Test
    fun fr58_warnsBelowTheThresholds() {
        val low = statuses(ready.copy(batteryPercent = 49, shotsLeft = 1_099, torchOn = true))
        assertEquals(CheckStatus.WARN, low[CheckItem.BATTERY])
        assertEquals(CheckStatus.WARN, low[CheckItem.STORAGE])
        assertEquals(CheckStatus.WARN, low[CheckItem.TORCH])
        val edge = statuses(ready.copy(batteryPercent = 50, shotsLeft = 1_100))
        assertEquals(CheckStatus.OK, edge[CheckItem.BATTERY])
        assertEquals(CheckStatus.OK, edge[CheckItem.STORAGE])
    }

    @Test
    fun fr58_unreadableValuesAskTheDiverToLook() {
        val unknown = statuses(ready.copy(batteryPercent = null, shotsLeft = null, torchOn = null))
        assertEquals(CheckStatus.LOOK, unknown[CheckItem.BATTERY])
        assertEquals(CheckStatus.LOOK, unknown[CheckItem.STORAGE])
        assertEquals(CheckStatus.LOOK, unknown[CheckItem.TORCH])
    }
}
