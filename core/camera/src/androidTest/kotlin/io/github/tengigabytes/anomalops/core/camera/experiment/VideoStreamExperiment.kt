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
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import android.view.Surface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import io.github.tengigabytes.anomalops.core.camera.session.openDevice
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.Executor
import kotlin.coroutines.resume

/**
 * ADR-0011 checks 4 and 5 on logical camera 0: can a 1920x1440 preview, a real video encoder surface and a
 * full-size still output configure together at 30 and 60 fps, and do stills taken while recording
 * (`TEMPLATE_VIDEO_SNAPSHOT`) drop encoded frames or make the exposure jump? The encoder output is counted and
 * discarded, stills are only sized in memory; nothing is written. Logs under [TAG].
 */
@RunWith(AndroidJUnit4::class)
class VideoStreamExperiment {
    @get:Rule
    val cameraPermission: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.CAMERA)

    private val manager = InstrumentationRegistry.getInstrumentation().targetContext
        .getSystemService(CameraManager::class.java)
    private val thread = HandlerThread("video-experiment").apply { start() }
    private val handler = Handler(thread.looper)
    private val executor = Executor { handler.post(it) }

    /** [stillFormat] null: preview and encoder only, to see whether the still stream is what caps the frame rate. */
    private class StreamSet(
        val name: String,
        val fps: Int,
        val stillFormat: Int?,
        val width: Int = 1920,
        val height: Int = 1440,
    )

    /** One frame of the repeating recording request; [snapshot] marks the video-snapshot requests. */
    private class Frame(val sensorNs: Long, val exposureNs: Long?, val iso: Int?, val snapshot: Boolean)

    @After
    fun tearDown() {
        thread.quitSafely()
    }

    @Test
    fun adr0011_recordingStreams() = runBlocking<Unit>(handler.asCoroutineDispatcher()) {
        listOf(
            StreamSet("30fps+JPEG_R", FPS_30, ImageFormat.JPEG_R),
            StreamSet("60fps+JPEG_R", FPS_60, ImageFormat.JPEG_R),
            StreamSet("60fps+JPEG", FPS_60, ImageFormat.JPEG),
            StreamSet("60fps 1440 no still", FPS_60, null),
            StreamSet("60fps 1080 no still", FPS_60, null, height = 1080),
        ).forEach { runSet(it) }
    }

    private suspend fun runSet(set: StreamSet) {
        val encoder = EncoderSink.create(set.width, set.height, set.fps, handler)
        if (encoder == null) {
            Log.i(TAG, "${set.name}: no hardware encoder for ${set.width}x${set.height}@${set.fps}")
            return
        }
        val preview = ImageReader.newInstance(
            set.width,
            set.height,
            ImageFormat.PRIVATE,
            SINK_IMAGES,
            HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE,
        ).apply { setOnImageAvailableListener({ it.acquireLatestImage()?.close() }, handler) }
        val still = set.stillFormat?.let(::stillReader)
        val device = manager.openDevice(LOGICAL_BACK, handler) { Log.e(TAG, "camera lost: $it") }
        try {
            val session = configure(device, set, preview.surface, encoder.surface, still?.surface)
            if (session == null) {
                Log.i(TAG, "${set.name}: no session (failed or timed out), encoder ${encoder.mime}")
                return
            }
            Log.i(
                TAG,
                "${set.name}: configured, encoder ${encoder.mime}, still ${still?.let { "${it.width}x${it.height}" }}",
            )
            record(set, device, session, listOf(preview.surface, encoder.surface), still, encoder)
            session.close()
        } finally {
            device.close()
            encoder.close()
            preview.close()
            still?.close()
            delay(SETTLE_MS)
        }
    }

    private suspend fun configure(
        device: CameraDevice,
        set: StreamSet,
        preview: Surface,
        video: Surface,
        still: Surface?,
    ): CameraCaptureSession? {
        fun output(surface: Surface, useCase: Int) =
            OutputConfiguration(surface).apply { setStreamUseCase(useCase.toLong()) }
        val outputs = listOfNotNull(
            output(preview, CameraCharacteristics.SCALER_AVAILABLE_STREAM_USE_CASES_PREVIEW),
            output(video, CameraCharacteristics.SCALER_AVAILABLE_STREAM_USE_CASES_VIDEO_RECORD),
            still?.let { output(it, CameraCharacteristics.SCALER_AVAILABLE_STREAM_USE_CASES_STILL_CAPTURE) },
        )
        // A failed configuration can restart the camera HAL without a callback (m2-stream-combos.md).
        return withTimeoutOrNull(CONFIGURE_TIMEOUT_MS) {
            suspendCancellableCoroutine<CameraCaptureSession?> { cont ->
                val callback = object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) = cont.resume(session)

                    override fun onConfigureFailed(session: CameraCaptureSession) = cont.resume(null)
                }
                val config = SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outputs, executor, callback)
                config.sessionParameters = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                    set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(set.fps, set.fps))
                }.build()
                device.createCaptureSession(config)
            }
        }
    }

    /** [RECORD_MS] of recording with [SNAPSHOTS] video snapshots spread through it; then the report. */
    private suspend fun record(
        set: StreamSet,
        device: CameraDevice,
        session: CameraCaptureSession,
        video: List<Surface>,
        still: ImageReader?,
        encoder: EncoderSink,
    ) {
        val frames = mutableListOf<Frame>()
        fun listener(snapshot: Boolean) = object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult) {
                val sensorNs = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: return
                frames += Frame(
                    sensorNs,
                    result.get(CaptureResult.SENSOR_EXPOSURE_TIME),
                    result.get(CaptureResult.SENSOR_SENSITIVITY),
                    snapshot,
                )
            }
        }
        fun request(template: Int, extra: Surface?) = device.createCaptureRequest(template).apply {
            (video + listOfNotNull(extra)).forEach(::addTarget)
            set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(set.fps, set.fps))
        }.build()
        session.setRepeatingRequest(request(CameraDevice.TEMPLATE_RECORD, null), listener(false), handler)
        delay(RECORD_MS / (SNAPSHOTS + 1))
        val sizes = List(if (still == null) 0 else SNAPSHOTS) {
            val image = CompletableDeferred<String>()
            still!!.setOnImageAvailableListener({ r ->
                r.acquireNextImage()?.use { image.complete("${it.planes[0].buffer.remaining()} B") }
            }, handler)
            session.capture(request(CameraDevice.TEMPLATE_VIDEO_SNAPSHOT, still.surface), listener(true), handler)
            val size = withTimeoutOrNull(STILL_TIMEOUT_MS) { image.await() } ?: "no image"
            delay(RECORD_MS / (SNAPSHOTS + 1))
            size
        }
        if (still == null) delay(RECORD_MS * SNAPSHOTS / (SNAPSHOTS + 1))
        session.stopRepeating()
        delay(DRAIN_MS)
        report(set, frames.sortedBy { it.sensorNs }, encoder.framesUs.toList(), sizes)
    }

    private fun report(set: StreamSet, frames: List<Frame>, encodedUs: List<Long>, sizes: List<String>) {
        val sensorGaps = frames.zipWithNext { a, b -> (b.sensorNs - a.sensorNs) / NS_PER_MS }.sorted()
        val encoded = encodedUs.sorted().distinct()
        val encodedGaps = encoded.zipWithNext { a, b -> (b - a) / US_PER_MS }
        val spanS = if (encoded.size > 1) (encoded.last() - encoded.first()) / US_PER_S else Double.NaN
        Log.i(
            TAG,
            "${set.name}: camera frames=${frames.size} gap median %.1f max %.1f ms; encoded frames=${encoded.size} "
                .format(sensorGaps.median(), sensorGaps.lastOrNull() ?: Double.NaN) +
                "over %.2f s = %.1f fps, gap max %.1f ms; stills=$sizes"
                    .format(spanS, (encoded.size - 1) / spanS, encodedGaps.maxOrNull() ?: Double.NaN),
        )
        frames.withIndex().filter { it.value.snapshot }.forEach { (i, snap) ->
            val before = frames.subList((i - AROUND).coerceAtLeast(0), i).filter { !it.snapshot }
            val after = frames.subList(i + 1, (i + 1 + AROUND).coerceAtMost(frames.size)).filter { !it.snapshot }
            Log.i(
                TAG,
                "${set.name}: snapshot exposure ${snap.exposureNs} ns iso ${snap.iso}; " +
                    "video around it: exposure ${before.map { it.exposureNs }} -> ${after.map { it.exposureNs }}, " +
                    "iso ${before.map { it.iso }} -> ${after.map { it.iso }}",
            )
        }
    }

    private fun List<Double>.median() = if (isEmpty()) Double.NaN else this[size / 2]

    private fun stillReader(format: Int): ImageReader {
        val size = manager.getCameraCharacteristics(
            LOGICAL_BACK,
        )[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP]!!
            .getOutputSizes(format).maxBy { it.width * it.height }
        return ImageReader.newInstance(size.width, size.height, format, STILL_IMAGES)
    }

    private companion object {
        const val TAG = "M7VideoExperiment"
        const val LOGICAL_BACK = "0"
        const val FPS_30 = 30
        const val FPS_60 = 60
        const val SINK_IMAGES = 4
        const val STILL_IMAGES = 2
        const val CONFIGURE_TIMEOUT_MS = 5_000L
        const val STILL_TIMEOUT_MS = 3_000L
        const val RECORD_MS = 8_000L
        const val SNAPSHOTS = 3
        const val DRAIN_MS = 500L
        const val SETTLE_MS = 1_000L
        const val AROUND = 3
        const val NS_PER_MS = 1e6
        const val US_PER_MS = 1e3
        const val US_PER_S = 1e6
    }
}
