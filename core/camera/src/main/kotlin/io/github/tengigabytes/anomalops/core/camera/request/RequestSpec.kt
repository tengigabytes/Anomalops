// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.core.camera.request

import io.github.tengigabytes.anomalops.core.camera.exposure.StillExposure
import io.github.tengigabytes.anomalops.core.profile.ScenePreset

/**
 * FR-81, ADR-0009: the only `CONTROL_AE_MODE` values this app ever requests. The flash-capable modes are
 * deliberately not representable; every request also carries `FLASH_MODE_OFF`.
 */
enum class AeMode { ON, OFF }

sealed interface FocusSpec {
    /** `CONTROL_AF_MODE_CONTINUOUS_PICTURE`. */
    data object ContinuousPicture : FocusSpec

    /** `CONTROL_AF_MODE_AUTO`, scanned on an AF trigger. */
    data object Auto : FocusSpec

    /** `CONTROL_AF_MODE_OFF` at `LENS_FOCUS_DISTANCE` = [diopters]. */
    data class Fixed(val diopters: Double) : FocusSpec
}

sealed interface ColorSpec {
    /**
     * ADR-0002: `CONTROL_AWB_MODE_OFF`, `COLOR_CORRECTION_MODE_TRANSFORM_MATRIX`, and the calibrated
     * gains (R, G even, G odd, B) and 3x3 row-major transform.
     */
    data class Manual(val gains: List<Double>, val transform: List<Double>) : ColorSpec

    /** No calibration for these conditions: auto white balance, shown in the UI as approximate. */
    data object AutoApproximate : ColorSpec
}

/**
 * The Camera2-independent content of one capture request. Pure data so the request policy is unit-testable
 * on the JVM; a Camera2 adapter maps it onto `CaptureRequest` keys.
 */
data class RequestSpec(
    val preset: ScenePreset,
    val physicalId: String,
    val ae: AeMode,
    /** Manual exposure; present exactly when [ae] is [AeMode.OFF] (ADR-0009). */
    val exposure: StillExposure?,
    val focus: FocusSpec,
    val color: ColorSpec,
) {
    init {
        require((ae == AeMode.OFF) == (exposure != null)) { "manual exposure must accompany AE_MODE_OFF only" }
    }
}
