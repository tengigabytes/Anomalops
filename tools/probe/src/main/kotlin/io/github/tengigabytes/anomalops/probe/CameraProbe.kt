// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.probe

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import org.json.JSONArray
import org.json.JSONObject
import io.github.tengigabytes.anomalops.probe.ConstantNames as N

/**
 * Dumps CameraCharacteristics for every logical camera and its physical sub-cameras.
 * Answers the G0 questions of ADR-0001 (capabilities, physical cameras), ADR-0002 (manual post-processing),
 * ADR-0009 (AE fps ranges, ISO range) and provides the colour matrices needed for calibration (FR-21).
 */
internal class CameraProbe(private val manager: CameraManager) {

    fun probeAll(): JSONObject {
        val listed = manager.cameraIdList.toList()
        val cameras = JSONArray()
        listed.forEach { id ->
            val ch = manager.getCameraCharacteristics(id)
            val physical = JSONArray()
            ch.physicalCameraIds.sorted().forEach { pid ->
                physical.put(describe(pid, manager.getCameraCharacteristics(pid)).put("listedInCameraIdList", pid in listed))
            }
            cameras.put(describe(id, ch).put("physicalCameras", physical).put("extensions", extensions(id)))
        }
        return jsonOf("cameraIdList" to listed.toJsonArray(), "cameras" to cameras)
    }

    private fun describe(id: String, ch: CameraCharacteristics): JSONObject = jsonOf(
        "id" to id,
        "facing" to N.name(N.lensFacing, ch[CameraCharacteristics.LENS_FACING]),
        "hardwareLevel" to N.name(N.hardwareLevels, ch[CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL]),
        "capabilities" to N.names(N.capabilities, ch[CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES]).toJsonArray(),
        "lens" to lens(ch),
        "sensor" to sensor(ch),
        "controls" to controls(ch),
        "color" to color(ch),
        "streams" to StreamProbe.describe(ch[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP]),
        "streamsMaximumResolution" to
            StreamProbe.describe(ch[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION]),
        "dynamicRangeProfiles" to
            ch[CameraCharacteristics.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES]?.supportedProfiles?.sorted()?.toJsonArray(),
        "availableCaptureRequestKeys" to ch.availableCaptureRequestKeys.map { it.name }.sortedJson(),
        "availableSessionKeys" to ch.availableSessionKeys?.map { it.name }.sortedJson(),
        "availablePhysicalCameraRequestKeys" to ch.availablePhysicalCameraRequestKeys?.map { it.name }.sortedJson(),
        "keysNeedingPermission" to ch.keysNeedingPermission.map { it.name }.sortedJson(),
    )

    private fun lens(ch: CameraCharacteristics) = jsonOf(
        "focalLengthsMm" to ch[CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS].toJson(),
        "apertures" to ch[CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES].toJson(),
        "minimumFocusDistanceDiopters" to ch[CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE]?.finiteOrString(),
        "hyperfocalDistanceDiopters" to ch[CameraCharacteristics.LENS_INFO_HYPERFOCAL_DISTANCE]?.finiteOrString(),
        "focusDistanceCalibration" to
            N.name(N.focusCalibrations, ch[CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION]),
        "opticalStabilization" to
            N.names(N.oisModes, ch[CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION]).toJsonArray(),
        // [fx, fy, cx, cy, s] in pixels: a 2x centre crop of the same sensor shows 2x the focal length in pixels.
        "intrinsicCalibration" to ch[CameraCharacteristics.LENS_INTRINSIC_CALIBRATION].toJson(),
        "intrinsicCalibrationMaximumResolution" to
            ch[CameraCharacteristics.LENS_INTRINSIC_CALIBRATION_MAXIMUM_RESOLUTION].toJson(),
        "distortion" to ch[CameraCharacteristics.LENS_DISTORTION].toJson(),
        "poseTranslationM" to ch[CameraCharacteristics.LENS_POSE_TRANSLATION].toJson(),
        "poseRotation" to ch[CameraCharacteristics.LENS_POSE_ROTATION].toJson(),
        "poseReference" to ch[CameraCharacteristics.LENS_POSE_REFERENCE],
    )

    private fun sensor(ch: CameraCharacteristics) = jsonOf(
        "physicalSizeMm" to ch[CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE]?.toString(),
        "pixelArraySize" to ch[CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE]?.toString(),
        "pixelArraySizeMaximumResolution" to
            ch[CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE_MAXIMUM_RESOLUTION]?.toString(),
        "activeArraySize" to ch[CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE]?.flattenToString(),
        "sensitivityRange" to ch[CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE].toJson(),
        "maxAnalogSensitivity" to ch[CameraCharacteristics.SENSOR_MAX_ANALOG_SENSITIVITY],
        "exposureTimeRangeNs" to ch[CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE].toJson(),
        "maxFrameDurationNs" to ch[CameraCharacteristics.SENSOR_INFO_MAX_FRAME_DURATION],
        "whiteLevel" to ch[CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL],
        "binningFactor" to ch[CameraCharacteristics.SENSOR_INFO_BINNING_FACTOR]?.toString(),
        "colorFilterArrangement" to ch[CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT],
        "timestampSource" to N.name(N.timestampSources, ch[CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE]),
    )

    private fun controls(ch: CameraCharacteristics) = jsonOf(
        "aeModes" to N.names(N.aeModes, ch[CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES]).toJsonArray(),
        "aeTargetFpsRanges" to
            ch[CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES]?.map { it.toJson() }?.toJsonArray(),
        "aeCompensationRange" to ch[CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE].toJson(),
        "aeCompensationStep" to ch[CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP]?.toString(),
        "afModes" to N.names(N.afModes, ch[CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES]).toJsonArray(),
        "awbModes" to N.names(N.awbModes, ch[CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES]).toJsonArray(),
        "flashAvailable" to ch[CameraCharacteristics.FLASH_INFO_AVAILABLE],
        "zoomRatioRange" to ch[CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE]?.let { "[${it.lower}, ${it.upper}]" },
        "maxDigitalZoom" to ch[CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM]?.finiteOrString(),
        "pipelineMaxDepth" to ch[CameraCharacteristics.REQUEST_PIPELINE_MAX_DEPTH]?.toInt(),
        "partialResultCount" to ch[CameraCharacteristics.REQUEST_PARTIAL_RESULT_COUNT],
        "syncMaxLatency" to ch[CameraCharacteristics.SYNC_MAX_LATENCY],
    )

    /** Sensor colour calibration used to derive white-balance gains and matrices (ADR-0002, FR-21). */
    private fun color(ch: CameraCharacteristics) = jsonOf(
        "referenceIlluminant1" to ch[CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1],
        "referenceIlluminant2" to ch[CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT2]?.toInt(),
        "calibrationTransform1" to ch[CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM1]?.toString(),
        "calibrationTransform2" to ch[CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM2]?.toString(),
        "colorTransform1" to ch[CameraCharacteristics.SENSOR_COLOR_TRANSFORM1]?.toString(),
        "colorTransform2" to ch[CameraCharacteristics.SENSOR_COLOR_TRANSFORM2]?.toString(),
        "forwardMatrix1" to ch[CameraCharacteristics.SENSOR_FORWARD_MATRIX1]?.toString(),
        "forwardMatrix2" to ch[CameraCharacteristics.SENSOR_FORWARD_MATRIX2]?.toString(),
    )

    private fun extensions(id: String): Any = runCatching {
        N.names(N.extensions, manager.getCameraExtensionCharacteristics(id).supportedExtensions.toIntArray())
            .toJsonArray()
    }.getOrElse { "${it.javaClass.simpleName}: ${it.message}" }

    private fun List<String>?.sortedJson(): Any = this?.sorted()?.toJsonArray() ?: JSONObject.NULL
}
