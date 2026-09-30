// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.conditions

import io.github.tengigabytes.anomalops.core.telemetry.depth.DepthZone
import io.github.tengigabytes.anomalops.core.telemetry.depth.ManualDepthSource

/** mvp-scope.md 2.2: the depth-band key cycles shallow → mid → deep → shallow. */
fun DepthZone.next(): DepthZone = DepthZone.entries[(ordinal + 1) % DepthZone.entries.size]

/** One press of the depth-band key (FR-21, ADR-0008's manual source). */
fun ManualDepthSource.cycle() {
    select(readings.value.zone.next())
}
