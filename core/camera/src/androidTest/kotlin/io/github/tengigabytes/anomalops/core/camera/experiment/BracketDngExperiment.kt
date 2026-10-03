// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.core.camera.experiment

import android.Manifest
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.DngCreator
import android.hardware.camera2.TotalCaptureResult
import android.media.Image
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Real RAW sets for `StackTool` on the desktop (docs/test/macro-stacking-test-plan.md, T12; FR-17), written as DNGs
 * by `DngCreator` to the test app's external files directory, `dng/<set>/` (pull with adb; they stay out of the
 * repo). Two modes, chosen with `-e mode`:
 *
 * - `lowlight`: [frames] RAW frames of one exposure in one `captureBurst`, as fast as the lens gives them, for FR-17
 *   (hand-held, the hand's shake is what alignment must undo). Focus at `-e focusCm`; without it one AF scan
 *   decides, and the hyperfocal distance when that fails.
 * - `focus`: a focus bracket for FR-33, [frames] steps evenly in diopters from `-e nearCm` to `-e farCm`; at each
 *   step the preview holds the focus until the lens reports it stationary at the request, then one RAW is taken.
 *
 * Exposure is locked after [AE_SETTLE_MS] so every frame has the same gain. `-e lens` picks the lens (default
 * [MAIN_LENS], the main lens), `-e set` names the folder, `-e frames` the count. Logs `BRACKET` lines under [TAG]:
 * per frame the focus distance asked and reported, the lens state, ISO, exposure and time.
 */
@RunWith(AndroidJUnit4::class)
class BracketDngExperiment {
    @get:Rule
    val cameraPermission: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.CAMERA)

    private val args = InstrumentationRegistry.getArguments()
    private val mode = args.getString("mode") ?: "focus"
    private val lens = args.getString("lens") ?: MAIN_LENS
    private val frames = args.getString("frames")?.toInt() ?: DEFAULT_FRAMES
    private val set = args.getString("set") ?: mode
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val manager = context.getSystemService(CameraManager::class.java)
    private val thread = HandlerThread("bracket-experiment").apply { start() }
    private val handler = Handler(thread.looper)
    private val rig = RawRig(manager, handler)
    private val dir = File(context.getExternalFilesDir(null), "dng/$set").apply {
        deleteRecursively()
        mkdirs()
    }

    @After
    fun tearDown() {
        rig.close()
        thread.quitSafely()
    }

    @Test
    fun captureSet() = runBlocking<Unit>(handler.asCoroutineDispatcher()) {
        Log.i(TAG, "BRACKET mode=$mode lens=$lens frames=$frames dir=${dir.absolutePath}")
        when (mode) {
            "lowlight" -> lowLight()
            "focus" -> focusBracket()
            else -> error("mode $mode: lowlight or focus")
        }
        Log.i(TAG, "BRACKET done: ${dir.listFiles()?.size} files")
    }

    private suspend fun lowLight() {
        val asked = args.getString("focusCm")?.let { CM_PER_M / it.toFloat() }
        rig.withLens(lens, frames + 1) { session ->
            val found = if (asked == null) autoFocus(session) else null
            val focus = asked ?: found ?: rig.lens(lens).hyperfocalDiopters
            val source = when {
                asked != null -> "asked"
                found != null -> "autofocus"
                else -> "hyperfocal, AF failed"
            }
            note("BRACKET lowlight focus %.3f D ($source)".format(focus))
            settle(session, focus)
            val images = ConcurrentHashMap<Long, Image>()
            session.raw.setOnImageAvailableListener(
                { r -> r.acquireNextImage()?.let { images[it.timestamp] = it } },
                handler,
            )
            val results = mutableListOf<TotalCaptureResult>()
            val done = CompletableDeferred<Unit>()
            val callback = object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, total: TotalCaptureResult) {
                    results += total
                    if (results.size == frames) done.complete(Unit)
                }
            }
            val request = session.request(focus, withRaw = true, aeLock = true, shadingMap = true)
            session.capture.captureBurst(List(frames) { request }, callback, handler)
            withTimeout(TIMEOUT_MS * frames) { done.await() }
            delay(IMAGE_WAIT_MS)
            results.forEachIndexed { k, total -> write(k, focus, images.remove(timestamp(total)), total) }
            images.values.forEach { it.close() }
        }
    }

    private suspend fun focusBracket() {
        val near = CM_PER_M / requireNotNull(args.getString("nearCm")) { "focus mode needs -e nearCm" }.toFloat()
        val far = CM_PER_M / requireNotNull(args.getString("farCm")) { "focus mode needs -e farCm" }.toFloat()
        rig.withLens(lens, 2) { session ->
            settle(session, near)
            for (k in 0 until frames) {
                val diopters = near + (far - near) * k / (frames - 1).coerceAtLeast(1)
                holdFocus(session, diopters)
                val image = CompletableDeferred<Image>()
                val result = CompletableDeferred<TotalCaptureResult>()
                session.raw.setOnImageAvailableListener({ r ->
                    r.acquireNextImage()?.let { if (!image.complete(it)) it.close() }
                }, handler)
                val callback = object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(
                        s: CameraCaptureSession,
                        r: CaptureRequest,
                        total: TotalCaptureResult,
                    ) {
                        result.complete(total)
                    }
                }
                session.capture.capture(
                    session.request(diopters, withRaw = true, aeLock = true, shadingMap = true),
                    callback,
                    handler,
                )
                write(
                    k,
                    diopters,
                    withTimeout(TIMEOUT_MS) { image.await() },
                    withTimeout(TIMEOUT_MS) { result.await() },
                )
            }
        }
    }

    /** Preview at [diopters] with AE running, then locked, so the set shares one exposure. */
    private suspend fun settle(session: RawRig.Session, diopters: Float) {
        session.capture.setRepeatingRequest(
            session.request(diopters, withRaw = false, shadingMap = true),
            null,
            handler,
        )
        delay(AE_SETTLE_MS)
        session.capture.setRepeatingRequest(
            session.request(diopters, withRaw = false, aeLock = true, shadingMap = true),
            null,
            handler,
        )
        delay(LOCK_SETTLE_MS)
    }

    /** One AF scan on the preview: the distance it locks at, or null when it fails or does not finish in time. */
    private suspend fun autoFocus(session: RawRig.Session): Float? {
        val locked = CompletableDeferred<Float?>()
        val callback = session.frames { f ->
            when (f.afState) {
                CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED -> locked.complete(f.diopters)
                CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED -> locked.complete(null)
            }
        }
        session.capture.setRepeatingRequest(session.autoFocus(trigger = false), callback, handler)
        delay(AE_SETTLE_MS)
        session.capture.capture(session.autoFocus(trigger = true), callback, handler)
        return runCatching { withTimeout(AF_TIMEOUT_MS) { locked.await() } }.getOrNull()
    }

    /** Logs [line] and keeps it beside the DNGs (`frames.txt`): `DngCreator` stores no exposure data here. */
    private fun note(line: String) {
        Log.i(TAG, line)
        File(dir, "frames.txt").appendText(line + "\n")
    }

    /** Preview at [diopters] until a frame reports the lens stationary within [FOCUS_TOLERANCE] of it. */
    private suspend fun holdFocus(session: RawRig.Session, diopters: Float) {
        val reached = CompletableDeferred<Unit>()
        val callback = session.frames { f ->
            val near = f.diopters?.let { kotlin.math.abs(it - diopters) <= FOCUS_TOLERANCE } ?: false
            if (near && f.lensState == CaptureResult.LENS_STATE_STATIONARY) reached.complete(Unit)
        }
        session.capture.setRepeatingRequest(
            session.request(diopters, withRaw = false, aeLock = true, shadingMap = true),
            callback,
            handler,
        )
        val ok = runCatching { withTimeout(FOCUS_TIMEOUT_MS) { reached.await() } }.isSuccess
        if (!ok) Log.w(TAG, "BRACKET focus %.3f D not reported stationary within $FOCUS_TIMEOUT_MS ms".format(diopters))
    }

    private fun timestamp(total: TotalCaptureResult): Long? =
        (total.physicalCameraTotalResults[lens] ?: total).get(CaptureResult.SENSOR_TIMESTAMP)

    private fun write(k: Int, asked: Float, raw: Image?, total: TotalCaptureResult) {
        val result: CaptureResult = total.physicalCameraTotalResults[lens] ?: total
        if (raw == null) {
            Log.e(TAG, "BRACKET frame $k: no RAW image")
            return
        }
        raw.use {
            val file = File(dir, "frame-%02d.dng".format(k))
            file.outputStream().use { out ->
                DngCreator(manager.getCameraCharacteristics(lens), result).use { dng -> dng.writeImage(out, it) }
            }
            // The boost is the gain (in hundredths) the HAL applies after the RAW: what a RAW frame still lacks.
            val boost = result.get(CaptureResult.CONTROL_POST_RAW_SENSITIVITY_BOOST)
            note(
                "BRACKET frame $k: asked %.3f D, reported ${result.get(
                    CaptureResult.LENS_FOCUS_DISTANCE,
                )} D, ".format(asked) +
                    "lensState=${result.get(
                        CaptureResult.LENS_STATE,
                    )} iso=${result.get(CaptureResult.SENSOR_SENSITIVITY)} postRawBoost=$boost " +
                    "exposureNs=${result.get(CaptureResult.SENSOR_EXPOSURE_TIME)} t=${it.timestamp} " +
                    "-> ${file.name} ${file.length()} bytes",
            )
        }
    }

    private companion object {
        const val TAG = RawRig.TAG
        const val MAIN_LENS = "2"
        const val DEFAULT_FRAMES = 6
        const val CM_PER_M = 100f
        const val AE_SETTLE_MS = 1_500L
        const val LOCK_SETTLE_MS = 300L
        const val IMAGE_WAIT_MS = 1_000L
        const val TIMEOUT_MS = 5_000L
        const val FOCUS_TIMEOUT_MS = 2_000L
        const val AF_TIMEOUT_MS = 5_000L
        const val FOCUS_TOLERANCE = 0.05f
    }
}
