// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops

import android.Manifest
import android.graphics.ImageFormat
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import io.github.tengigabytes.anomalops.core.camera.session.CameraController
import io.github.tengigabytes.anomalops.core.profile.CalibrationKey
import io.github.tengigabytes.anomalops.core.profile.DepthBand
import io.github.tengigabytes.anomalops.core.profile.DeviceProfiles
import io.github.tengigabytes.anomalops.core.profile.LensFilter
import io.github.tengigabytes.anomalops.core.profile.ScenePreset
import io.github.tengigabytes.anomalops.core.store.media.StillStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.ceil

/**
 * NFR-7 (docs/product/mvp-acceptance.md): 300 stills written to MediaStore, from the finished capture to
 * `IS_PENDING = 0`, p95 at most 500 ms. The stills are deleted afterwards.
 */
@RunWith(AndroidJUnit4::class)
class StillWriteTest {
    @get:Rule
    val cameraPermission: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.CAMERA)

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val saved = mutableListOf<Uri>()
    private val sinkThread = HandlerThread("preview-sink").apply { start() }
    private val sink = ImageReader.newInstance(
        CameraController.PREVIEW_SIZE.width,
        CameraController.PREVIEW_SIZE.height,
        ImageFormat.PRIVATE,
        SINK_IMAGES,
        HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE,
    ).apply { setOnImageAvailableListener({ it.acquireLatestImage()?.close() }, Handler(sinkThread.looper)) }

    @After
    fun tearDown() {
        saved.forEach { context.contentResolver.delete(it, null, null) }
        sink.close()
        sinkThread.quitSafely()
    }

    @Test
    fun nfr7_threeHundredMediaStoreWrites() = runBlocking {
        val profile = requireNotNull(DeviceProfiles.load(Build.DEVICE)) { "no device profile for ${Build.DEVICE}" }
        val controller = CameraController(context, profile)
        val store = StillStore(context)
        val conditions = CalibrationKey(DepthBand.SHALLOW, LensFilter.NONE, diveLight = false)
        try {
            controller.start(sink.surface, ScenePreset.SNAPSHOT, conditions)
            val writeMs = List(WRITES) {
                val result = store.save(controller.capture())
                saved += result.uri
                result.writeMs.toDouble()
            }
            val sorted = writeMs.sorted()
            val p95 = sorted[ceil(P95 * sorted.size).toInt() - 1]
            Log.i(TAG, "NFR-7 n=${sorted.size} median=${sorted[sorted.size / 2]} p95=$p95 max=${sorted.last()} ms")
            assertTrue("NFR-7 p95 $p95 ms > $WRITE_P95_MS ms", p95 <= WRITE_P95_MS)
        } finally {
            controller.stopBlocking()
            controller.release()
        }
    }

    private companion object {
        const val TAG = "M1Acceptance"
        const val WRITES = 300
        const val WRITE_P95_MS = 500.0
        const val P95 = 0.95
        const val SINK_IMAGES = 4
    }
}
