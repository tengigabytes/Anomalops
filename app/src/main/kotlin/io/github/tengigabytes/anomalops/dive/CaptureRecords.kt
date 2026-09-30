// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.dive

import android.os.SystemClock
import io.github.tengigabytes.anomalops.capture.ShotPipeline
import io.github.tengigabytes.anomalops.core.camera.request.ColorSpec
import io.github.tengigabytes.anomalops.core.profile.CalibrationKey
import io.github.tengigabytes.anomalops.core.profile.ScenePreset
import io.github.tengigabytes.anomalops.lock.SessionRows

/** A `captures.csv` row for one still: request and result side by side (ADR-0008). */
fun stillRecord(shot: ShotPipeline.Shot, key: CalibrationKey): SessionRows.Capture {
    val capture = shot.capture
    val exposure = capture.spec.exposure
    return SessionRows.Capture(
        elapsedNs = SystemClock.elapsedRealtimeNanos(),
        kind = "still",
        sensorTimestampNs = capture.sensorTimestampNs,
        preset = capture.spec.preset.name,
        lens = capture.spec.physicalId,
        depthBand = key.depthBand.name,
        filter = key.filter.name,
        diveLight = key.diveLight,
        format = capture.format.toString(),
        frames = 1,
        requestExposureNs = exposure?.exposure?.timeNs,
        requestIso = exposure?.exposure?.iso,
        isoClamped = exposure?.isoClamped,
        requestGains = (capture.spec.color as? ColorSpec.Manual)?.gains,
        reportedExposureNs = capture.reported.exposureTimeNs,
        reportedIso = capture.reported.iso,
        reportedGains = capture.reported.color?.gains,
        file = shot.saved.displayName,
    )
}

/** A `captures.csv` row for one burst (FR-15): frame count and stem; per-frame values stay in the log. */
fun burstRecord(burst: ShotPipeline.Burst, preset: ScenePreset, key: CalibrationKey): SessionRows.Capture =
    SessionRows.Capture(
        elapsedNs = SystemClock.elapsedRealtimeNanos(),
        kind = "burst",
        sensorTimestampNs = null,
        preset = preset.name,
        lens = null,
        depthBand = key.depthBand.name,
        filter = key.filter.name,
        diveLight = key.diveLight,
        format = null,
        frames = burst.frames,
        requestExposureNs = null,
        requestIso = null,
        isoClamped = null,
        requestGains = null,
        reportedExposureNs = null,
        reportedIso = null,
        reportedGains = null,
        file = burst.stem,
    )
