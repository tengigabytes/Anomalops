// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.core.camera.whitebalance

import io.github.tengigabytes.anomalops.core.profile.ScenePreset

/** Why a gray-card measurement was refused; the UI asks the diver to aim again. */
enum class PatchProblem { CLIPPED, TOO_DARK }

sealed interface Measurement {
    /** `COLOR_CORRECTION_GAINS` in the order R, G even, G odd, B, green fixed at 1. */
    data class Gains(val gains: List<Double>) : Measurement

    data class Refused(val problem: PatchProblem) : Measurement
}

/**
 * FR-22: one-press custom white balance. The gains make the patch neutral: red and blue are scaled to the mean
 * green. The calibration table stays the default; a slot overrides it only for the presets that select it.
 */
object CustomWhiteBalance {
    /** Proposed: a patch with more than 1 % clipped photosites no longer has true channel ratios. */
    const val MAX_CLIPPED_SHARE = 0.01

    /**
     * Proposed: below this mean green (DN above black) noise dominates the ratios. UNVERIFIED(G1): to be set from
     * the RAW noise of the Pixel 10 Pro main camera at the preset's highest ISO.
     */
    const val MIN_GREEN_DN = 32.0

    fun measure(means: ChannelMeans): Measurement {
        val green = (means.gEven + means.gOdd) / 2
        return when {
            means.clippedShare > MAX_CLIPPED_SHARE -> Measurement.Refused(PatchProblem.CLIPPED)
            green < MIN_GREEN_DN || means.r <= 0.0 || means.b <= 0.0 -> Measurement.Refused(PatchProblem.TOO_DARK)
            else -> Measurement.Gains(listOf(green / means.r, 1.0, 1.0, green / means.b))
        }
    }
}

/** A saved custom white balance: the diver's name for it, the gains and the colour matrix in use when measured. */
data class WbSlot(val name: String, val gains: List<Double>, val colorMatrix: List<Double>) {
    init {
        require(gains.size == GAINS && gains.all { it.isFinite() && it > 0.0 }) { "gains must be $GAINS positives" }
        require(colorMatrix.size == MATRIX) { "colour matrix must be 3 x 3" }
    }

    private companion object {
        const val GAINS = 4
        const val MATRIX = 9
    }
}

/**
 * FR-22: [COUNT] named slots, and per preset which slot (if any) replaces the calibration table. Choosing a
 * preset brings back its own choice, which is what "the slots follow the preset" means here (an interpretation).
 * Not thread-safe; persistence is the caller's.
 */
class WbSlots {
    private val slots = arrayOfNulls<WbSlot>(COUNT)
    private val selected = mutableMapOf<ScenePreset, Int>()

    fun slot(index: Int): WbSlot? = slots[checked(index)]

    fun save(index: Int, slot: WbSlot) {
        slots[checked(index)] = slot
    }

    fun rename(index: Int, name: String) {
        slots[checked(index)] = checkNotNull(slots[index]) { "slot $index is empty" }.copy(name = name)
    }

    /** Empties the slot; presets that used it return to the calibration table. */
    fun clear(index: Int) {
        slots[checked(index)] = null
        selected.values.removeAll { it == index }
    }

    /** [index] null returns [preset] to the calibration table. */
    fun select(preset: ScenePreset, index: Int?) {
        if (index == null) {
            selected.remove(preset)
        } else {
            checkNotNull(slots[checked(index)]) { "slot $index is empty" }
            selected[preset] = index
        }
    }

    /** The slot [preset] uses, or null for the calibration table. */
    fun activeFor(preset: ScenePreset): WbSlot? = selected[preset]?.let { slots[it] }

    private fun checked(index: Int): Int {
        require(index in 0 until COUNT) { "slot must be 0..${COUNT - 1}" }
        return index
    }

    companion object {
        const val COUNT = 4
    }
}
