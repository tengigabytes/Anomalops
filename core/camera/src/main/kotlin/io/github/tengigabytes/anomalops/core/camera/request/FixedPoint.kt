// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.core.camera.request

import kotlin.math.roundToInt

/**
 * Rational encoding of `COLOR_CORRECTION_TRANSFORM` elements. The Pixel 10 Pro HAL works on a 1/256 grid and
 * truncates toward zero; a power-of-two denominator keeps grid values exact, so a matrix read back from a
 * `CaptureResult` is applied unchanged (docs/test/m1-pipeline-calibration.md).
 */
internal object FixedPoint {
    const val DENOMINATOR = 1 shl 16

    fun numerator(value: Double): Int = (value * DENOMINATOR).roundToInt()
}
