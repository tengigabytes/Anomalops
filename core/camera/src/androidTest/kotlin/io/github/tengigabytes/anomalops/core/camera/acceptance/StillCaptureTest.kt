// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.core.camera.acceptance

import android.Manifest
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import io.github.tengigabytes.anomalops.core.camera.request.StillFormat
import io.github.tengigabytes.anomalops.core.profile.ScenePreset
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The shutter half of NFR-4 and FR-61a (docs/product/mvp-acceptance.md). */
@RunWith(AndroidJUnit4::class)
class StillCaptureTest {
    @get:Rule
    val cameraPermission: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.CAMERA)

    private lateinit var rig: CameraRig

    @Before
    fun setUp() {
        rig = CameraRig()
    }

    @After
    fun tearDown() = rig.close()

    /** From the capture call to the start of the still's exposure (`SENSOR_TIMESTAMP`, REALTIME time base). */
    @Test
    fun nfr4_shutterToExposureStart() = runBlocking<Unit> {
        rig.start(ScenePreset.SNAPSHOT)
        repeat(WARM_UP) { rig.controller.capture() }
        val latencyMs = List(SHOTS) {
            val pressedAt = SystemClock.elapsedRealtimeNanos()
            val still = rig.controller.capture()
            (still.sensorTimestampNs - pressedAt) / NS_PER_MS
        }
        val p95 = Acceptance.report("NFR-4 shutter", latencyMs)
        assertTrue("NFR-4 shutter p95 $p95 ms > $SHUTTER_P95_MS ms", p95 <= SHUTTER_P95_MS)
    }

    /** 20 stills over all presets: JPEG_R with a gain map, 6 MB average at most. */
    @Test
    fun fr61a_ultraHdrStillsCarryGainMaps() = runBlocking<Unit> {
        rig.start(ScenePreset.SNAPSHOT)
        val shots = List(ULTRA_HDR_SHOTS) { index ->
            val preset = ScenePreset.entries[index % ScenePreset.entries.size]
            rig.controller.select(preset, rig.conditions)
            rig.controller.capture()
        }
        val options = BitmapFactory.Options().apply { inSampleSize = DECODE_SAMPLE }
        val withGainMap = shots.count { shot ->
            BitmapFactory.decodeByteArray(shot.bytes, 0, shot.bytes.size, options)?.hasGainmap() == true
        }
        val meanMb = shots.map { it.bytes.size }.average() / BYTES_PER_MB
        Log.i(Acceptance.TAG, "FR-61a gain maps $withGainMap/${shots.size}, mean %.2f MB".format(meanMb))
        assertEquals(List(shots.size) { StillFormat.JPEG_R }, shots.map { it.format })
        assertEquals(shots.size, withGainMap)
        assertTrue("FR-61a mean $meanMb MB > $MAX_MEAN_MB MB", meanMb <= MAX_MEAN_MB)
    }

    private companion object {
        const val WARM_UP = 3
        const val SHOTS = 50
        const val SHUTTER_P95_MS = 150.0
        const val ULTRA_HDR_SHOTS = 20
        const val DECODE_SAMPLE = 8
        const val MAX_MEAN_MB = 6.0
        const val BYTES_PER_MB = 1_048_576.0
        const val NS_PER_MS = 1e6
    }
}
