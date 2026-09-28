// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.core.camera.acceptance

import android.graphics.ImageFormat
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import io.github.tengigabytes.anomalops.core.camera.session.CameraController
import io.github.tengigabytes.anomalops.core.camera.session.StillCapture
import io.github.tengigabytes.anomalops.core.profile.CalibrationKey
import io.github.tengigabytes.anomalops.core.profile.DepthBand
import io.github.tengigabytes.anomalops.core.profile.DeviceProfile
import io.github.tengigabytes.anomalops.core.profile.DeviceProfiles
import io.github.tengigabytes.anomalops.core.profile.LensFilter
import io.github.tengigabytes.anomalops.core.profile.ScenePreset
import kotlin.math.ceil

/**
 * The real camera on an off-screen preview: a PRIVATE-format ImageReader that drops every frame, standing in for
 * the app's SurfaceView so no activity is needed.
 */
internal class CameraRig : AutoCloseable {
    val profile: DeviceProfile =
        requireNotNull(DeviceProfiles.load(Build.DEVICE)) { "no device profile for ${Build.DEVICE}" }
    val controller = CameraController(InstrumentationRegistry.getInstrumentation().targetContext, profile)

    /** The M1 screen's conditions; calibrated for lenses 2, 3 and 9 (docs/test/m1-pipeline-calibration.md). */
    val conditions = CalibrationKey(DepthBand.SHALLOW, LensFilter.NONE, diveLight = false)

    private val sinkThread = HandlerThread("preview-sink").apply { start() }
    private val sink = ImageReader.newInstance(
        CameraController.PREVIEW_SIZE.width,
        CameraController.PREVIEW_SIZE.height,
        ImageFormat.PRIVATE,
        SINK_IMAGES,
        HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE,
    ).apply { setOnImageAvailableListener({ it.acquireLatestImage()?.close() }, Handler(sinkThread.looper)) }

    suspend fun start(preset: ScenePreset) = controller.start(sink.surface, preset, conditions)

    /**
     * One still, with its RAW frame returned at once: the RAW reader holds 7 images (ADR-0005), and a test that
     * keeps them fills it and crashes the camera thread. The app hands them to the RAW buffer instead.
     */
    suspend fun capture(): StillCapture = controller.capture().also { it.raw?.close() }

    override fun close() {
        controller.stopBlocking()
        controller.release()
        sink.close()
        sinkThread.quitSafely()
    }

    private companion object {
        const val SINK_IMAGES = 4
    }
}

/** Summaries go to logcat under [TAG]; thresholds come from docs/product/mvp-acceptance.md. */
internal object Acceptance {
    const val TAG = "M1Acceptance"
    private const val P95 = 0.95

    /** Nearest-rank 95th percentile. */
    fun p95(values: List<Double>): Double = values.sorted()[ceil(P95 * values.size).toInt() - 1]

    fun report(name: String, valuesMs: List<Double>): Double {
        val sorted = valuesMs.sorted()
        val p95 = p95(valuesMs)
        Log.i(
            TAG,
            "$name n=${sorted.size} min=%.1f median=%.1f p95=%.1f max=%.1f ms".format(
                sorted.first(),
                sorted[sorted.size / 2],
                p95,
                sorted.last(),
            ),
        )
        return p95
    }
}
