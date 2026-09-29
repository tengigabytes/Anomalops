// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.core.camera.session

import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.os.SystemClock
import io.github.tengigabytes.anomalops.core.camera.exposure.Exposure
import io.github.tengigabytes.anomalops.core.camera.request.RequestSpec
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * ADR-0009: the preview auto-exposure is the light meter. Keeps the latest exposure time and ISO of the preview,
 * reports every preview frame with the spec that produced it (NFR-4 timing), and feeds focus results to
 * [FocusScanner]. Used only on the camera thread; [reset] whenever the preview session goes away.
 */
internal class PreviewMeter(private val focus: FocusScanner, private val onFrame: (PreviewFrame) -> Unit) {
    private var metered: Exposure? = null
    private var firstMeter = CompletableDeferred<Unit>()

    /** The capture callback for the repeating preview request of [spec]. */
    fun callback(spec: RequestSpec) = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult) {
            val arrivedAtNs = SystemClock.elapsedRealtimeNanos()
            onFrame(PreviewFrame(spec, result.get(CaptureResult.SENSOR_TIMESTAMP) ?: 0L, arrivedAtNs))
            focus.onResult(s, result, spec.physicalId)
            // The physical result is present for the streaming lens (docs/test/m1-pipeline-calibration.md).
            val source = result.physicalCameraTotalResults[spec.physicalId] ?: result
            val time = source.get(CaptureResult.SENSOR_EXPOSURE_TIME)
            val iso = source.get(CaptureResult.SENSOR_SENSITIVITY)
            if (time != null && iso != null) {
                metered = Exposure(time, iso)
                firstMeter.complete(Unit)
            }
        }
    }

    /** A shutter press right after start waits for the first metered preview frame instead of failing. */
    suspend fun awaitMetered(): Exposure {
        if (metered == null) withTimeoutOrNull(FIRST_METER_TIMEOUT_MS) { firstMeter.await() }
        return metered ?: throw CameraFailure("no metered preview frame after $FIRST_METER_TIMEOUT_MS ms")
    }

    fun reset() {
        metered = null
        firstMeter = CompletableDeferred()
    }

    private companion object {
        const val FIRST_METER_TIMEOUT_MS = 1_000L
    }
}
