// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.core.camera.request

import io.github.tengigabytes.anomalops.core.camera.exposure.Exposure
import io.github.tengigabytes.anomalops.core.camera.exposure.ShutterPriority
import io.github.tengigabytes.anomalops.core.camera.preset.FocusPolicy
import io.github.tengigabytes.anomalops.core.camera.preset.PresetTable
import io.github.tengigabytes.anomalops.core.profile.CalibrationKey
import io.github.tengigabytes.anomalops.core.profile.DeviceProfile
import io.github.tengigabytes.anomalops.core.profile.PhysicalCamera
import io.github.tengigabytes.anomalops.core.profile.ScenePreset
import kotlin.math.min

/** FR-61a: the still output format; the profile's `outputs` map is keyed by these names. */
enum class StillFormat {
    JPEG_R,
    JPEG,
    ;

    companion object {
        /** JPEG_R (Ultra HDR) when the camera supports it, otherwise plain JPEG. */
        fun bestFor(camera: PhysicalCamera): StillFormat = if (JPEG_R.name in camera.outputs) JPEG_R else JPEG
    }
}

/** Builds preview and still request specs for scene presets on one device model (FR-11, ADR-0002, ADR-0009). */
class RequestPlanner(private val profile: DeviceProfile) {
    fun camera(preset: ScenePreset): PhysicalCamera =
        requireNotNull(profile.cameraFor(preset)) { "profile ${profile.device.buildDevice} has no camera for $preset" }

    /** Preview: auto-exposure as the light meter, manual white balance, the preset's initial focus mode. */
    fun preview(preset: ScenePreset, conditions: CalibrationKey): RequestSpec {
        val camera = camera(preset)
        val focus = when (PresetTable.parametersFor(preset).focus) {
            FocusPolicy.ContinuousPicture -> FocusSpec.ContinuousPicture
            is FocusPolicy.AutoWithFixedFallback -> FocusSpec.Auto
        }
        return RequestSpec(preset, camera.id, AeMode.ON, exposure = null, focus, color(camera, conditions))
    }

    /** Still: the current preview spec with the metered exposure converted to shutter priority (ADR-0009). */
    fun still(preview: RequestSpec, metered: Exposure, format: StillFormat): RequestSpec {
        val camera = camera(preview.preset)
        check(camera.id == preview.physicalId) { "preview uses ${preview.physicalId}, preset uses ${camera.id}" }
        val output = requireNotNull(camera.outputs[format.name]) { "camera ${camera.id} cannot output $format" }
        val exposure = ShutterPriority.convert(
            metered = metered,
            maxExposureNs = PresetTable.parametersFor(preview.preset).maxExposureNs,
            isoRange = camera.isoRange[0]..camera.isoRange[1],
            minFrameNs = output.minFrameNs,
        )
        return preview.copy(ae = AeMode.OFF, exposure = exposure)
    }

    /** FR-31: after a failed AF scan, focus at the preset's fallback distance, within the lens's range. */
    fun focusFallback(spec: RequestSpec): RequestSpec {
        val policy = PresetTable.parametersFor(spec.preset).focus
        if (policy !is FocusPolicy.AutoWithFixedFallback) return spec
        val diopters = min(1.0 / policy.fallbackDistanceM, camera(spec.preset).minFocusDistanceDiopters)
        return spec.copy(focus = FocusSpec.Fixed(diopters))
    }

    private fun color(camera: PhysicalCamera, conditions: CalibrationKey): ColorSpec {
        val entry = profile.calibrationFor(camera.id, conditions)?.takeIf { camera.manualPostProcessing }
        return entry?.let { ColorSpec.Manual(it.gains, it.colorMatrix) } ?: ColorSpec.AutoApproximate
    }
}
