// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.acceptance

import android.Manifest
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * FR-62 / NFR-1 (docs/test/m4-af-timeline.md): with the lens's RAW reader full (5 frames in the FR-62 buffer and
 * 2 more held, as by DNGs still being written), a shutter press saves the still without RAW instead of crashing
 * the app; once frames are returned, stills carry their own RAW again. The files are deleted afterwards.
 */
@RunWith(AndroidJUnit4::class)
class RawFullTest {
    @get:Rule
    val cameraPermission: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.CAMERA)

    private val rig = AppRig()

    @After
    fun tearDown() = rig.close()

    @Test
    fun fr62_aFullRawReaderSavesTheStillWithoutRaw() = runBlocking<Unit> {
        rig.start()
        repeat(BUFFERED) { rig.track(rig.pipeline.shoot().saved.stem) }
        val held = List(HELD) { rig.controller.capture() }
        assertTrue("the held stills carry RAW", held.all { it.raw != null })

        val full = rig.pipeline.shoot()
        rig.track(full.saved.stem)
        assertNull("no RAW with $MAX_RAW frames out", full.capture.raw)
        assertTrue("the still is saved", full.saved.sizeBytes > 0)
        assertNull("nothing to keep", rig.pipeline.keepRaw(full.saved.stem))

        held.forEach { it.raw?.close() }
        val next = rig.controller.capture()
        val raw = requireNotNull(next.raw) { "RAW is back once frames are returned" }
        val rawTimestampNs = raw.image.timestamp
        raw.close()
        assertEquals("the RAW is this still's own frame", next.sensorTimestampNs, rawTimestampNs)
        val after = rig.pipeline.shoot()
        rig.track(after.saved.stem)
        assertNotNull(after.capture.raw)
        Log.i(
            TAG,
            "FR-62 full reader: still ${full.saved.displayName} ${full.saved.sizeBytes} B without RAW; " +
                "next RAW timestamp matches=${next.sensorTimestampNs == rawTimestampNs}",
        )
    }

    private companion object {
        const val TAG = "M4Acceptance"
        const val BUFFERED = 5
        const val HELD = 2
        const val MAX_RAW = BUFFERED + HELD
    }
}
