// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.core.camera.session

import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult

/**
 * Settings read back from a still's `CaptureResult`, to compare with the request (ADR-0002, ADR-0009, FR-11)
 * and to read converged auto white balance for the M1 pipeline calibration. Null when the HAL omits a key.
 *
 * @property fromPhysical the per-lens values came from the physical camera's result rather than the logical one.
 */
data class ReportedSettings(
    val exposureTimeNs: Long?,
    val iso: Int?,
    val afMode: Int?,
    val awbMode: Int?,
    val color: ReportedColor?,
    val fromPhysical: Boolean,
)

/** `COLOR_CORRECTION_GAINS` (R, G even, G odd, B) and `COLOR_CORRECTION_TRANSFORM` (3x3, row-major). */
data class ReportedColor(val gains: List<Double>, val transform: List<Double>)

private const val MATRIX_SIDE = 3

/** Per-lens keys come from the physical result when there is one; mode keys exist only on the logical one. */
internal fun reportedSettings(result: TotalCaptureResult, physicalId: String): ReportedSettings {
    val physical = result.physicalCameraTotalResults[physicalId]
    val source: CaptureResult = physical ?: result
    val gains = source.get(CaptureResult.COLOR_CORRECTION_GAINS)
    val transform = source.get(CaptureResult.COLOR_CORRECTION_TRANSFORM)
    val color = if (gains != null && transform != null) {
        ReportedColor(
            gains = listOf(gains.red, gains.greenEven, gains.greenOdd, gains.blue).map { it.toDouble() },
            transform = List(MATRIX_SIDE * MATRIX_SIDE) {
                transform.getElement(it % MATRIX_SIDE, it / MATRIX_SIDE).toDouble()
            },
        )
    } else {
        null
    }
    return ReportedSettings(
        exposureTimeNs = source.get(CaptureResult.SENSOR_EXPOSURE_TIME),
        iso = source.get(CaptureResult.SENSOR_SENSITIVITY),
        afMode = result.get(CaptureResult.CONTROL_AF_MODE),
        awbMode = result.get(CaptureResult.CONTROL_AWB_MODE),
        color = color,
        fromPhysical = physical != null,
    )
}
