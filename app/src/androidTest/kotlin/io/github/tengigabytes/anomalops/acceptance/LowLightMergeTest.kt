// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.acceptance

import android.Manifest
import android.graphics.BitmapFactory
import android.hardware.camera2.CaptureResult
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import io.github.tengigabytes.anomalops.capture.LowLightRenderer
import io.github.tengigabytes.anomalops.capture.ShootingMode
import io.github.tengigabytes.anomalops.capture.ShotPipeline
import io.github.tengigabytes.anomalops.core.store.media.StillStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * FR-17 through the app's shot pipeline on the real camera, merge switch on: in each of the dive screen's modes a
 * shot is a still plus four RAW frames, merged on the GPU and stored as a JPEG of half the lens's RAW size cropped
 * to the camera's field of view, upright. Logs the times, and what the camera reported for the frames, under [TAG]. With the switch off the
 * camera's own still is stored. The files are deleted afterwards; with `-e keep true` each merged picture and the
 * camera's still of the same moment are also written to the app's external files directory, under `fr17/`.
 */
@RunWith(AndroidJUnit4::class)
class LowLightMergeTest {
    @get:Rule
    val cameraPermission: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.CAMERA)

    private val rig = AppRig()
    private val renderer = LowLightRenderer()
    private var enabled = true
    private val pipeline =
        ShotPipeline(rig.controller, StillStore(rig.context), rig.keeper, rig.stacks, renderer) { enabled }
    private val keep = InstrumentationRegistry.getArguments().getString("keep") == "true"

    @After
    fun tearDown() {
        renderer.close()
        rig.close()
    }

    @Test
    fun fr17_everyModeMergesAndStores() = runBlocking<Unit> {
        ShootingMode.entries.forEachIndexed { i, mode ->
            val preset = mode.preset(merge = true)
            if (i == 0) rig.start(preset) else rig.controller.select(preset, rig.conditions)
            delay(SETTLE_MS)
            val raw = requireNotNull(rig.profile.cameraFor(preset)).outputs.getValue("RAW_SENSOR").max
            val (rawWidth, rawHeight) = raw.split('x').map(String::toInt)
            repeat(SHOTS) { k -> mergedShot("$mode ($preset) shot $k", rawWidth / 2, rawHeight / 2) }
        }
    }

    @Test
    fun theSwitchOffKeepsTheCamerasStill() = runBlocking<Unit> {
        enabled = false
        rig.start(ShootingMode.AUTO.preset(merge = false))
        delay(SETTLE_MS)
        val off = pipeline.shoot()
        rig.track(off.saved.stem)
        assertNull("switch off must not merge", off.merge)
        assertEquals(off.capture.bytes.size.toLong(), off.saved.sizeBytes)
    }

    private suspend fun mergedShot(name: String, halfWidth: Int, halfHeight: Int) {
        val shot = pipeline.shoot()
        rig.track(shot.saved.stem)
        val merge = checkNotNull(shot.merge) { "$name: FR-17 was not tried" }
        val result = shot.capture.raw?.result
        Log.i(
            TAG,
            "FR-17 $name: merged=${merge.merged} frames=${merge.frames} capture=${merge.captureMs} ms " +
                "merge=${merge.mergeMs} ms encode=${merge.encodeMs} ms write=${shot.saved.writeMs} ms; asked " +
                "${shot.capture.spec.exposure?.exposure}, reported iso=" +
                "${result?.get(CaptureResult.SENSOR_SENSITIVITY)} postRawBoost=" +
                "${result?.get(CaptureResult.CONTROL_POST_RAW_SENSITIVITY_BOOST)}",
        )
        Log.i(
            TAG,
            "FR-17 $name: crop region ${result?.get(CaptureResult.SCALER_CROP_REGION)} zoom " +
                "${result?.get(
                    CaptureResult.CONTROL_ZOOM_RATIO,
                )} focus ${result?.get(CaptureResult.LENS_FOCUS_DISTANCE)} D " +
                "distortion mode ${result?.get(CaptureResult.DISTORTION_CORRECTION_MODE)}",
        )
        assertTrue("$name fell back to the camera's still", merge.merged)
        assertEquals("$name frames", FRAMES, merge.frames)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val bytes = rig.read(shot.saved.uri)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        Log.i(
            TAG,
            "FR-17 $name: stored ${bounds.outWidth}x${bounds.outHeight} ${bounds.outMimeType}, gain ${merge.gain}, " +
                "mean level merged ${meanLevel(bytes)} against the camera's still ${meanLevel(shot.capture.bytes)}",
        )
        if (keep) {
            // Both pictures of the same moment, side by side for a look: <external files>/fr17/.
            val dir = File(rig.context.getExternalFilesDir(null), "fr17").apply { mkdirs() }
            val stem = name.replace(Regex("[^A-Za-z0-9]+"), "-")
            File(dir, "$stem-merged.jpg").writeBytes(bytes)
            File(dir, "$stem-camera.jpg").writeBytes(shot.capture.bytes)
        }
        Log.i(TAG, "FR-17 $name: kept ${merge.keptWidth} of the RAW frame's width, matched=${merge.cropMatched}")
        // Half the RAW size cropped to the camera's field of view, turned upright for the portrait-locked screen.
        val short = minOf(halfWidth, halfHeight) * merge.keptWidth
        val long = maxOf(halfWidth, halfHeight) * merge.keptWidth
        assertEquals("$name short side", short, minOf(bounds.outWidth, bounds.outHeight).toFloat(), SIZE_TOLERANCE)
        assertEquals("$name long side", long, maxOf(bounds.outWidth, bounds.outHeight).toFloat(), SIZE_TOLERANCE)
        // The camera's stills are a centred crop of the RAW frame: 85 to 95 % on this phone's lenses.
        assertTrue("$name kept ${merge.keptWidth}", merge.keptWidth in MIN_KEPT..1f)
    }

    /** The mean of the green channel (0..255) of an encoded picture, decoded at an eighth of its size. */
    private fun meanLevel(encoded: ByteArray): Int {
        val small = BitmapFactory.Options().apply { inSampleSize = SAMPLE }
        val bitmap = BitmapFactory.decodeByteArray(encoded, 0, encoded.size, small)
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        bitmap.recycle()
        return pixels.sumOf { (it shr GREEN_SHIFT and CODE).toLong() }.div(pixels.size).toInt()
    }

    private companion object {
        const val TAG = "LowLightMergeTest"
        const val SHOTS = 2
        const val SIZE_TOLERANCE = 3f
        const val MIN_KEPT = 0.75f
        const val FRAMES = 5
        const val SETTLE_MS = 1_500L
        const val SAMPLE = 8
        const val GREEN_SHIFT = 8
        const val CODE = 0xFF
    }
}
