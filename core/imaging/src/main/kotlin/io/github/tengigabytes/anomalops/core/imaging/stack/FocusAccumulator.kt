// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.core.imaging.stack

import io.github.tengigabytes.anomalops.core.imaging.align.Plane

/**
 * A focus stack fed one frame at a time (ADR-0017): [add] every frame of the bracket in order, already aligned and
 * with no missing pixels, then call [finish] once. Gives the same result as [FocusStack.merge] on the same frames.
 */
interface FocusAccumulator {
    fun add(luma: Plane, channels: List<Plane>)

    fun finish(): List<Plane>
}

/**
 * For candidates whose decision needs every frame at once (A's smoothed choice maps, C's guided weights): keeps
 * the frames and merges them in [finish], so memory still grows with the bracket.
 */
internal class CollectingAccumulator(private val stack: FocusStack) : FocusAccumulator {
    private val luma = mutableListOf<Plane>()
    private val channels = mutableListOf<List<Plane>>()

    override fun add(luma: Plane, channels: List<Plane>) {
        this.luma += luma
        this.channels += channels
    }

    override fun finish(): List<Plane> = stack.merge(luma, channels)
}
