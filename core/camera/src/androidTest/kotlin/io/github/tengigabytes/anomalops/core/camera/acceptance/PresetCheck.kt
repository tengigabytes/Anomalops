// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.core.camera.acceptance

import android.hardware.camera2.CaptureResult
import io.github.tengigabytes.anomalops.core.camera.preset.FocusPolicy
import io.github.tengigabytes.anomalops.core.camera.preset.PresetTable
import io.github.tengigabytes.anomalops.core.camera.request.FocusSpec
import io.github.tengigabytes.anomalops.core.camera.session.StillCapture
import io.github.tengigabytes.anomalops.core.profile.CalibrationKey
import io.github.tengigabytes.anomalops.core.profile.DeviceProfile
import io.github.tengigabytes.anomalops.core.profile.ScenePreset
import kotlin.math.abs

/**
 * FR-11 acceptance (docs/product/mvp-acceptance.md): a still's `CaptureResult` must show the preset's physical
 * lens, white balance, exposure limit and AF mode (docs/product/mvp-scope.md, section 4). A macro still after a
 * failed scan (dark, or nothing within reach) runs at the fallback distance with AF off (FR-35); that passes when
 * the distance is the preset's.
 */
internal object PresetCheck {
    private const val GAIN_TOLERANCE = 1e-4
    private const val DIOPTER_TOLERANCE = 1e-6

    fun problems(profile: DeviceProfile, conditions: CalibrationKey, preset: ScenePreset, still: StillCapture) =
        buildList {
            val camera = requireNotNull(profile.cameraFor(preset))
            val reported = still.reported
            if (still.spec.physicalId != camera.id) add("lens ${still.spec.physicalId}, expected ${camera.id}")
            if (!reported.fromPhysical) add("no physical result for lens ${camera.id}")
            val limit = PresetTable.parametersFor(preset).maxExposureNs
            val time = reported.exposureTimeNs
            if (time == null || time > limit) add("exposure $time ns above the $limit ns limit")
            val policy = PresetTable.parametersFor(preset).focus
            val af = expectedAfMode(policy, still.spec.focus)
            if (reported.afMode != af) add("AF mode ${reported.afMode}, expected $af")
            addAll(fallback(policy, still.spec.focus))
            addAll(whiteBalance(still, profile.calibrationFor(camera.id, conditions)?.gains))
            if (still.flashFired) add("flash fired")
        }

    /** ADR-0002: calibrated conditions run with AWB off at the table's gains; others fall back to auto. */
    private fun whiteBalance(still: StillCapture, expectedGains: List<Double>?): List<String> = buildList {
        val awb = still.reported.awbMode
        if (expectedGains == null) {
            if (awb != CaptureResult.CONTROL_AWB_MODE_AUTO) add("uncalibrated but AWB mode $awb")
            return@buildList
        }
        if (awb != CaptureResult.CONTROL_AWB_MODE_OFF) add("calibrated but AWB mode $awb")
        val gains = still.reported.color?.gains.orEmpty()
        val matches = gains.size == expectedGains.size &&
            gains.zip(expectedGains).all { (a, b) -> abs(a - b) <= GAIN_TOLERANCE }
        if (!matches) add("gains $gains, expected $expectedGains")
    }

    private fun expectedAfMode(policy: FocusPolicy, requested: FocusSpec): Int = when (policy) {
        FocusPolicy.ContinuousPicture -> CaptureResult.CONTROL_AF_MODE_CONTINUOUS_PICTURE

        is FocusPolicy.AutoWithFixedFallback ->
            if (requested is FocusSpec.Fixed) CaptureResult.CONTROL_AF_MODE_OFF else CaptureResult.CONTROL_AF_MODE_AUTO
    }

    /** FR-35: a fixed-focus still must use the preset's fallback distance. */
    private fun fallback(policy: FocusPolicy, requested: FocusSpec): List<String> {
        if (policy !is FocusPolicy.AutoWithFixedFallback || requested !is FocusSpec.Fixed) return emptyList()
        val expected = 1.0 / policy.fallbackDistanceM
        return if (abs(requested.diopters - expected) <= DIOPTER_TOLERANCE) {
            emptyList()
        } else {
            listOf("fixed focus ${requested.diopters} D, expected $expected D")
        }
    }
}
