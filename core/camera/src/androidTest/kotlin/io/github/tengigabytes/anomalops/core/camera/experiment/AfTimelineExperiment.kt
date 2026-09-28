// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.core.camera.experiment

import android.Manifest
import android.graphics.ImageFormat
import android.hardware.HardwareBuffer
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import io.github.tengigabytes.anomalops.core.camera.session.configure
import io.github.tengigabytes.anomalops.core.camera.session.openDevice
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.Executor

/**
 * FR-35 / FR-31 measurement: how the HAL's AF behaves per lens, before choosing when to give up and fall back to
 * a fixed focus distance. Continuous AF is watched for [WATCH_MS] from the first frame; AUTO is triggered
 * [TRIGGERS] times and timed to its lock. The scene is named with `-e scene <name>` (flat wall, printed page,
 * 5 cm page). Logs the AF / lens state changes under [TAG]; takes no pictures.
 */
@RunWith(AndroidJUnit4::class)
class AfTimelineExperiment {
    @get:Rule
    val cameraPermission: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.CAMERA)

    private val scene = InstrumentationRegistry.getArguments().getString("scene", "unnamed")
    private val manager = InstrumentationRegistry.getInstrumentation().targetContext
        .getSystemService(CameraManager::class.java)
    private val thread = HandlerThread("af-experiment").apply { start() }
    private val handler = Handler(thread.looper)
    private val executor = Executor { handler.post(it) }
    private val preview = ImageReader.newInstance(
        ComboStreams.PREVIEW.width,
        ComboStreams.PREVIEW.height,
        ImageFormat.PRIVATE,
        SINK_IMAGES,
        HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE,
    ).apply { setOnImageAvailableListener({ it.acquireLatestImage()?.close() }, handler) }
    private val samples = mutableListOf<Sample>()

    /** One capture result, as seen by the physical lens when it reports, else by the logical camera. */
    private class Sample(val arrivedNs: Long, val af: Int?, val lens: Int?, val diopters: Float?)

    @After
    fun tearDown() {
        preview.close()
        thread.quitSafely()
    }

    @Test
    fun fr35_afTimelinePerLens() = runBlocking<Unit>(handler.asCoroutineDispatcher()) {
        LENSES.forEach { id ->
            val ch = manager.getCameraCharacteristics(id)
            Log.i(
                TAG,
                "lens $id minFocus=${ch[CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE]} D " +
                    "hyperfocal=${ch[CameraCharacteristics.LENS_INFO_HYPERFOCAL_DISTANCE]} D " +
                    "calibration=${ch[CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION]}",
            )
        }
        CONTINUOUS_LENSES.forEach { Log.i(TAG, "scene=$scene lens=$it CONTINUOUS ${continuous(it)}") }
        AUTO_LENSES.forEach { id ->
            auto(id).forEachIndexed { i, line -> Log.i(TAG, "scene=$scene lens=$id AUTO #${i + 1} $line") }
        }
    }

    private suspend fun continuous(id: String): String = withSession(id) { device, session ->
        val startNs = SystemClock.elapsedRealtimeNanos()
        session.setRepeatingRequest(
            request(device, id, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE).build(),
            watch(id),
            handler,
        )
        delay(WATCH_MS)
        summary(samples.toList(), startNs)
    }

    private suspend fun auto(id: String): List<String> = withSession(id) { device, session ->
        session.setRepeatingRequest(
            request(device, id, CaptureRequest.CONTROL_AF_MODE_AUTO).build(),
            watch(id),
            handler,
        )
        delay(SETTLE_MS)
        List(TRIGGERS) {
            samples.clear()
            val startNs = SystemClock.elapsedRealtimeNanos()
            val trigger = request(device, id, CaptureRequest.CONTROL_AF_MODE_AUTO)
            trigger.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
            session.capture(trigger.build(), watch(id), handler)
            delay(AUTO_WATCH_MS)
            val line = summary(samples.toList(), startNs)
            val cancel = request(device, id, CaptureRequest.CONTROL_AF_MODE_AUTO)
            cancel.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_CANCEL)
            session.capture(cancel.build(), null, handler)
            delay(SETTLE_MS)
            line
        }
    }

    private suspend fun <T> withSession(id: String, block: suspend (CameraDevice, CameraCaptureSession) -> T): T {
        samples.clear()
        val device = manager.openDevice(LOGICAL_BACK, handler) { Log.e(TAG, "camera lost: $it") }
        try {
            val output = OutputConfiguration(preview.surface).apply { setPhysicalCameraId(id) }
            val session = device.configure(listOf(output), executor) {}
            return block(device, session).also { session.close() }
        } finally {
            device.close()
        }
    }

    private fun request(device: CameraDevice, id: String, afMode: Int) =
        device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW, setOf(id)).apply {
            addTarget(preview.surface)
            set(CaptureRequest.CONTROL_AF_MODE, afMode)
        }

    private fun watch(id: String) = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult) {
            val physical = result.physicalCameraTotalResults[id]
            fun <T> key(k: CaptureResult.Key<T>): T? = physical?.get(k) ?: result.get(k)
            samples += Sample(
                SystemClock.elapsedRealtimeNanos(),
                key(CaptureResult.CONTROL_AF_STATE),
                key(CaptureResult.LENS_STATE),
                key(CaptureResult.LENS_FOCUS_DISTANCE),
            )
        }
    }

    /** State changes as `ms:AF/LENS@diopters`, lens movement episodes and total moving time, final state. */
    private fun summary(all: List<Sample>, startNs: Long): String {
        val changes = all.filterIndexed { i, s -> i == 0 || s.af != all[i - 1].af || s.lens != all[i - 1].lens }
        val moves = all.zipWithNext().count { (a, b) -> a.lens != MOVING && b.lens == MOVING }
        val movingMs = all.zipWithNext().filter { (a, _) -> a.lens == MOVING }
            .sumOf { (a, b) -> b.arrivedNs - a.arrivedNs } / NS_PER_MS
        val lastMoveEndMs = all.zipWithNext().lastOrNull { (a, b) -> a.lens == MOVING && b.lens != MOVING }
            ?.let { (_, b) -> (b.arrivedNs - startNs) / NS_PER_MS }
        val timeline = changes.take(MAX_CHANGES).joinToString(" ") { s ->
            "%.0f:%s/%s@%.2f".format((s.arrivedNs - startNs) / NS_PER_MS, afName(s.af), lensName(s.lens), s.diopters)
        }
        val last = all.lastOrNull()
        return "frames=${all.size} moves=$moves movingMs=%.0f lastMoveEndMs=%s final=%s@%.2f D | %s".format(
            movingMs,
            lastMoveEndMs?.let { "%.0f".format(it) } ?: "-",
            afName(last?.af),
            last?.diopters,
            timeline,
        )
    }

    private fun afName(state: Int?) = state?.let { AF_NAMES.getOrElse(it) { "$it" } } ?: "null"

    private fun lensName(state: Int?) = when (state) {
        MOVING -> "MOV"
        null -> "null"
        else -> "STA"
    }

    private companion object {
        const val TAG = "AfExperiment"
        const val LOGICAL_BACK = "0"
        const val SINK_IMAGES = 4
        const val WATCH_MS = 4_000L
        const val AUTO_WATCH_MS = 2_500L
        const val SETTLE_MS = 1_000L
        const val TRIGGERS = 3
        const val MAX_CHANGES = 30
        const val NS_PER_MS = 1e6
        const val MOVING = CaptureResult.LENS_STATE_MOVING

        // Main, ultrawide and the ultrawide 2x crop used by the macro preset (docs/product/mvp-scope.md, section 4).
        val LENSES = listOf("2", "3", "9")
        val CONTINUOUS_LENSES = listOf("2", "3", "9")
        val AUTO_LENSES = listOf("9", "2")
        val AF_NAMES = listOf(
            "INACTIVE",
            "PASSIVE_SCAN",
            "PASSIVE_FOCUSED",
            "ACTIVE_SCAN",
            "FOCUSED_LOCKED",
            "NOT_FOCUSED_LOCKED",
            "PASSIVE_UNFOCUSED",
        )
    }
}
