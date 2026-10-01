// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.core.profile

import kotlinx.serialization.Serializable

/**
 * One device model's capability and calibration table (ADR-0003), stored as
 * `assets/device-profiles/<Build.DEVICE>.json`. The Kotlin model is the schema: decoding is strict, so an
 * unknown or missing field fails loudly. `capabilities` is generated from a probe report by
 * `scripts/probe_to_profile.py`; `presetLenses`, `calibration` and `focusCalibration` are written by hand.
 */
@Serializable
data class DeviceProfile(
    val schemaVersion: Int,
    val device: DeviceInfo,
    val source: ProbeSource,
    val capabilities: Capabilities,
    val presetLenses: PresetLenses,
    val calibration: List<CalibrationEntry>,
    val focusCalibration: List<FocusCalibrationEntry>,
) {
    fun physicalCamera(id: String): PhysicalCamera? = capabilities.physicalCameras.firstOrNull { it.id == id }

    /** FR-11: the physical camera a scene preset uses on this model. */
    fun cameraFor(preset: ScenePreset): PhysicalCamera? = physicalCamera(presetLenses.idFor(preset))

    /** ADR-0002: the white-balance calibration for a camera under the given conditions, or null if none exists. */
    fun calibrationFor(physicalId: String, key: CalibrationKey): CalibrationEntry? =
        calibration.firstOrNull { it.matches(physicalId, key) }

    /** ADR-0014: the measured focus correction of a camera, or null if it has none. */
    fun focusCalibrationFor(physicalId: String): FocusCalibrationEntry? =
        focusCalibration.firstOrNull { it.physicalId == physicalId }
}

@Serializable
data class DeviceInfo(val buildDevice: String, val model: String, val socModel: String)

@Serializable
data class ProbeSource(val probeSchema: String, val probedAt: String, val buildId: String, val sdkIntFull: Int)

@Serializable
data class Capabilities(
    val logicalCameras: List<LogicalCamera>,
    val physicalCameras: List<PhysicalCamera>,
    val sensors: Map<String, SensorInfo>,
)

@Serializable
data class LogicalCamera(
    val id: String,
    val facing: String,
    val hardwareLevel: String,
    val physicalIds: List<String>,
    val aeTargetFpsRanges: List<List<Int>>,
    val zoomRatioRange: List<Double>,
    val extensions: List<String>,
    val flashAvailable: Boolean,
)

@Serializable
data class PhysicalCamera(
    val id: String,
    val logicalId: String,
    val lens: LensKind,
    val readout: Readout,
    val focalLengthMm: Double,
    val minFocusDistanceDiopters: Double,
    val manualSensor: Boolean,
    val manualPostProcessing: Boolean,
    val raw: Boolean,
    val isoRange: List<Int>,
    val maxAnalogIso: Int,
    val exposureRangeNs: List<Long>,
    val afModes: List<String>,
    val outputs: Map<String, OutputInfo>,
    val color: SensorColor,
)

@Serializable
enum class LensKind { MAIN, ULTRAWIDE, TELE, FRONT }

/** How the sensor is read (docs/test/g0-blazer.md 1.1): full sensor with 2x2 binning, or unbinned 2x centre crop. */
@Serializable
enum class Readout { BINNED, CROP_2X }

@Serializable
data class OutputInfo(val max: String, val minFrameNs: Long, val stallNs: Long)

/** Sensor colour calibration from CameraCharacteristics; matrices are 3x3, row-major (ADR-0002). */
@Serializable
data class SensorColor(
    val referenceIlluminant1: Int?,
    val referenceIlluminant2: Int?,
    val colorTransform1: List<Double>?,
    val colorTransform2: List<Double>?,
    val forwardMatrix1: List<Double>?,
    val forwardMatrix2: List<Double>?,
)

@Serializable
data class SensorInfo(val available: Boolean, val maxDelayUs: Int?)

enum class ScenePreset { SNAPSHOT, WIDE, FISH_SCHOOL, MACRO, LOW_LIGHT }

/** Physical camera ID per scene preset (docs/product/mvp-scope.md, section 4). */
@Serializable
data class PresetLenses(
    val snapshot: String,
    val wide: String,
    val fishSchool: String,
    val macro: String,
    val lowLight: String,
) {
    fun idFor(preset: ScenePreset): String = when (preset) {
        ScenePreset.SNAPSHOT -> snapshot
        ScenePreset.WIDE -> wide
        ScenePreset.FISH_SCHOOL -> fishSchool
        ScenePreset.MACRO -> macro
        ScenePreset.LOW_LIGHT -> lowLight
    }
}
