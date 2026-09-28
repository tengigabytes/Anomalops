// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.core.camera.session

import io.github.tengigabytes.anomalops.core.camera.request.RequestSpec
import io.github.tengigabytes.anomalops.core.camera.request.StillFormat
import io.github.tengigabytes.anomalops.core.profile.ScenePreset

enum class CameraStatus { CLOSED, PREVIEWING, FAILED }

/** What the UI shows about the camera (ADR-0001: the UI never touches Camera2 directly). */
data class CameraState(
    val status: CameraStatus = CameraStatus.CLOSED,
    val preset: ScenePreset? = null,
    val physicalId: String? = null,
    /** ADR-0002: no calibration for the current conditions, so white balance is automatic and approximate. */
    val colorApproximate: Boolean = false,
    val error: String? = null,
)

/** One still as encoded by the camera, before it is stored (FR-61a, ADR-0004). */
class StillCapture(
    val bytes: ByteArray,
    val format: StillFormat,
    /** The request that produced it, including the ADR-0009 exposure and whether ISO was clamped. */
    val spec: RequestSpec,
    val sensorTimestampNs: Long,
    /** FR-81 check: the result reported `FLASH_STATE_FIRED`. */
    val flashFired: Boolean,
    /** What the physical camera's result reports, to compare with [spec] (ADR-0002, ADR-0009, FR-11). */
    val reported: ReportedSettings,
)

/** A Camera2 operation failed; the message is for logs and the UI status line. */
class CameraFailure(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)
