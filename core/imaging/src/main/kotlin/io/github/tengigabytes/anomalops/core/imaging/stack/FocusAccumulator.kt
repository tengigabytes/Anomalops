// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.core.imaging.stack

import io.github.tengigabytes.anomalops.core.imaging.align.Plane

/**
 * A focus stack fed one frame at a time (ADR-0017): [add] every frame of the bracket in order, already aligned and
 * with no missing pixels, [passes] times (the same frames in the same order each time, [endPass] between passes),
 * then call [finish] once. Gives the same result as [FocusStack.merge] on the same frames.
 */
interface FocusAccumulator {
    /** 1 for most candidates; 2 for A, whose weights need every frame's score before any frame is merged. */
    val passes: Int get() = 1

    fun add(luma: Plane, channels: List<Plane>)

    /** Called after every pass but the last. */
    fun endPass() {}

    fun finish(): List<Plane>
}

/** Every frame through every pass of this accumulator, then the result. */
internal fun FocusAccumulator.mergeAll(luma: List<Plane>, channels: List<List<Plane>>): List<Plane> {
    repeat(passes) { pass ->
        luma.indices.forEach { add(luma[it], channels[it]) }
        if (pass < passes - 1) endPass()
    }
    return finish()
}

/**
 * For candidates whose decision needs every frame at once (C's guided weights): keeps the frames and merges them
 * in [finish], so memory still grows with the bracket.
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
