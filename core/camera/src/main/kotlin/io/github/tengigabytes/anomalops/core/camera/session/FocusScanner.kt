// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.core.camera.session

import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.os.Handler
import android.os.SystemClock
import android.util.Log
import io.github.tengigabytes.anomalops.core.camera.focus.AfScan
import io.github.tengigabytes.anomalops.core.camera.focus.ScanOutcome
import io.github.tengigabytes.anomalops.core.camera.request.FocusSpec
import io.github.tengigabytes.anomalops.core.camera.request.RequestPlanner
import io.github.tengigabytes.anomalops.core.camera.request.RequestSpec
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/** Builds a request for a spec with a template and extra settings (the preview target is added by the caller). */
internal typealias RequestBuilder = (RequestSpec, Int, CaptureRequest.Builder.() -> Unit) -> CaptureRequest

/**
 * FR-31 / FR-35 for presets that focus with AUTO (macro). Maintainer decision (docs/test/m4-af-timeline.md): one
 * scan when the preview enters the preset, then the diver moves the camera to focus; if the scan fails or overruns
 * [AfScan.BUDGET_NS], the preset focuses at its fixed fallback distance for the rest of the visit. A new session
 * (after stop or a burst) starts a new visit, because the lock is not known to survive it. Continuous-AF presets
 * are left to the HAL. Runs on the camera thread only.
 */
internal class FocusScanner(
    private val planner: RequestPlanner,
    private val handler: Handler,
    private val onScan: (FocusScan) -> Unit,
) {
    private var visit: Visit? = null

    /** One stay on an AUTO-focus preset in one session. [repeat] sets the preview request for a spec. */
    private class Visit(val session: CameraCaptureSession, var spec: RequestSpec, var repeat: (RequestSpec) -> Unit) {
        var scan: AfScan? = null
        val settled = CompletableDeferred<ScanOutcome>()
        var fellBack = false
    }

    /**
     * Previews [spec] through [repeat]: continuous AF as is; the first AUTO request of a visit followed by a
     * trigger; later requests of the visit (a new colour) with the fallback distance if the scan fell back.
     */
    fun show(session: CameraCaptureSession, spec: RequestSpec, request: RequestBuilder, repeat: (RequestSpec) -> Unit) {
        if (spec.focus != FocusSpec.Auto) {
            visit = null
            repeat(spec)
            return
        }
        val same = visit?.takeIf { it.session === session && it.spec.preset == spec.preset }
        if (same != null) {
            same.spec = spec
            same.repeat = repeat
            repeat(if (same.fellBack) planner.focusFallback(spec) else spec)
            return
        }
        val started = Visit(session, spec, repeat).also { visit = it }
        repeat(spec)
        trigger(started, request)
    }

    /** Every preview result of the current session. */
    fun onResult(session: CameraCaptureSession, result: TotalCaptureResult, physicalId: String) {
        val current = active(session) ?: return
        val scan = current.scan ?: return
        settle(
            current,
            scan.result(result.frameNumber, afState(result, physicalId), SystemClock.elapsedRealtimeNanos()),
        )
    }

    /** Before a still or a burst: wait for the scan of the current visit, if one is running. */
    suspend fun settled() {
        visit?.settled?.let { withTimeoutOrNull(SETTLE_TIMEOUT_MS) { it.await() } }
    }

    fun reset() {
        visit = null
    }

    private fun trigger(started: Visit, request: RequestBuilder) {
        val scan = AfScan(SystemClock.elapsedRealtimeNanos()).also { started.scan = it }
        val trigger = request(started.spec, CameraDevice.TEMPLATE_PREVIEW) {
            set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
        }
        val callback = object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult) {
                val now = SystemClock.elapsedRealtimeNanos()
                active(
                    s,
                )?.let { settle(it, scan.triggered(result.frameNumber, afState(result, started.spec.physicalId), now)) }
            }

            override fun onCaptureFailed(s: CameraCaptureSession, r: CaptureRequest, failure: CaptureFailure) {
                active(s)?.let { settle(it, scan.failed(SystemClock.elapsedRealtimeNanos())) }
            }
        }
        started.session.capture(trigger, callback, handler)
    }

    private fun active(session: CameraCaptureSession) =
        visit?.takeIf { it.session === session && !it.settled.isCompleted }

    private fun settle(current: Visit, outcome: ScanOutcome?) {
        outcome ?: return
        current.settled.complete(outcome)
        val fallback = if (outcome == ScanOutcome.LOCKED) null else planner.focusFallback(current.spec)
        if (fallback != null) {
            current.fellBack = true
            // A session that is closing refuses the request; the next visit scans again, so only log it.
            runCatching { current.repeat(fallback) }.onFailure { Log.w(TAG, "focus fallback not applied", it) }
        }
        val scan = checkNotNull(current.scan)
        val diopters = (fallback?.focus as? FocusSpec.Fixed)?.diopters
        onScan(FocusScan(current.spec.preset, current.spec.physicalId, outcome, scan.totalMs, scan.searchMs, diopters))
    }

    /** As in the AF experiment: the physical lens's result when it reports one, else the logical result. */
    private fun afState(result: TotalCaptureResult, physicalId: String): Int? =
        result.physicalCameraTotalResults[physicalId]?.get(CaptureResult.CONTROL_AF_STATE)
            ?: result.get(CaptureResult.CONTROL_AF_STATE)

    private companion object {
        const val TAG = "FocusScanner"
        const val SETTLE_TIMEOUT_MS = 1_200L
    }
}
