// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.core.camera.experiment

import android.Manifest
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.DngCreator
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.LensShadingMap
import android.media.Image
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import io.github.tengigabytes.anomalops.core.camera.experiment.RawRig.Companion.TAG
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

/**
 * One RAW per lens written as a DNG by `DngCreator`, with the lens shading map requested, to check `:core:imaging`'s
 * `DngReader` and `StackTool` on real files and to see each lens's shading map (`ShadingMap`'s UNVERIFIED(G0)).
 * Any scene. Logs under [TAG]; the DNGs go to the test app's external files directory (pull with adb) and stay out
 * of the repo. `-e lenses 2,9` picks the lenses (default the six back lenses).
 */
@RunWith(AndroidJUnit4::class)
class DngCaptureExperiment {
    @get:Rule
    val cameraPermission: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.CAMERA)

    private val args = InstrumentationRegistry.getArguments()
    private val lenses = args.getString("lenses")?.split(',')?.map { it.trim() } ?: listOf("2", "3", "4", "5", "6", "9")
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val manager = context.getSystemService(CameraManager::class.java)
    private val thread = HandlerThread("dng-experiment").apply { start() }
    private val handler = Handler(thread.looper)
    private val rig = RawRig(manager, handler)
    private val dir = File(context.getExternalFilesDir(null), "dng").apply { mkdirs() }

    @After
    fun tearDown() {
        rig.close()
        thread.quitSafely()
    }

    @Test
    fun oneDngPerLens() = runBlocking<Unit>(handler.asCoroutineDispatcher()) {
        Log.i(TAG, "DNG dir=${dir.absolutePath}")
        lenses.forEach { id ->
            runCatching { capture(id) }
                .onFailure { Log.e(TAG, "DNG lens=$id FAIL ${it.javaClass.simpleName}: ${it.message}", it) }
        }
    }

    private suspend fun capture(id: String) {
        val focus = rig.lens(id).hyperfocalDiopters
        rig.withLens(id, RAW_IMAGES) { session ->
            session.capture.setRepeatingRequest(
                session.request(focus, withRaw = false, shadingMap = true),
                null,
                handler,
            )
            delay(AE_SETTLE_MS)
            val image = CompletableDeferred<Image>()
            val result = CompletableDeferred<TotalCaptureResult>()
            session.raw.setOnImageAvailableListener({ r ->
                r.acquireNextImage()?.let { if (!image.complete(it)) it.close() }
            }, handler)
            val callback = object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, total: TotalCaptureResult) {
                    result.complete(total)
                }
            }
            session.capture.capture(
                session.request(focus, withRaw = true, aeLock = true, shadingMap = true),
                callback,
                handler,
            )
            val raw = withTimeout(TIMEOUT_MS) { image.await() }
            val total = withTimeout(TIMEOUT_MS) { result.await() }
            raw.use { write(id, it, total.physicalCameraTotalResults[id] ?: total) }
        }
    }

    private fun write(id: String, raw: Image, result: CaptureResult) {
        val characteristics = manager.getCameraCharacteristics(id)
        val map = result.get(CaptureResult.STATISTICS_LENS_SHADING_CORRECTION_MAP)
        Log.i(
            TAG,
            "DNG lens=$id raw=${raw.width}x${raw.height} " +
                "mapMode=${result.get(CaptureResult.STATISTICS_LENS_SHADING_MAP_MODE)} " +
                "shadingMode=${result.get(CaptureResult.SHADING_MODE)} map=${map?.let { describe(it) }} " +
                "black=${result.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL)?.joinToString(",")} " +
                "white=${result.get(CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL)} " +
                "gains=${result.get(CaptureResult.COLOR_CORRECTION_GAINS)} " +
                "iso=${result.get(CaptureResult.SENSOR_SENSITIVITY)} " +
                "exposureNs=${result.get(CaptureResult.SENSOR_EXPOSURE_TIME)} " +
                "activeArray=${characteristics[CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE]} " +
                "pixelArray=${characteristics[CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE]}",
        )
        val file = File(dir, "lens-$id.dng")
        file.outputStream().use { out -> DngCreator(characteristics, result).use { it.writeImage(out, raw) } }
        Log.i(TAG, "DNG lens=$id wrote ${file.absolutePath} ${file.length()} bytes")
    }

    /** Grid size and, per channel (R, G even, G odd, B), the smallest and largest gain and the centre's. */
    private fun describe(map: LensShadingMap): String {
        val cols = map.columnCount
        val rows = map.rowCount
        val channels = (0 until CHANNELS).joinToString(" ") { c ->
            val all = (0 until rows).flatMap { r -> (0 until cols).map { map.getGainFactor(c, it, r) } }
            "c$c=%.3f..%.3f@centre%.3f".format(all.min(), all.max(), map.getGainFactor(c, cols / 2, rows / 2))
        }
        return "${cols}x$rows $channels"
    }

    private companion object {
        const val RAW_IMAGES = 2
        const val AE_SETTLE_MS = 1_500L
        const val TIMEOUT_MS = 5_000L
        const val CHANNELS = 4
    }
}
