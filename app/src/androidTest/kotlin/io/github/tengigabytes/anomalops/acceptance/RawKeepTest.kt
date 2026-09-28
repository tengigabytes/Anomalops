// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.acceptance

import android.Manifest
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import io.github.tengigabytes.anomalops.core.profile.ScenePreset
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** FR-62 and FR-64 (docs/product/mvp-acceptance.md): the RAW buffer and the DNGs it writes. */
@RunWith(AndroidJUnit4::class)
class RawKeepTest {
    @get:Rule
    val cameraPermission: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.CAMERA)

    private val rig = AppRig()

    @After
    fun tearDown() = rig.close()

    /** 20 stills kept 2, 5 or 9 s after the shot: 20 DNGs, each the RAW_SENSOR default size. */
    @Test
    fun fr62_fr64_keepTwoFiveOrNineSecondsAfterTheShot() = runBlocking<Unit> {
        rig.start()
        val rawSize = requireNotNull(rig.profile.cameraFor(ScenePreset.SNAPSHOT)).outputs.getValue("RAW_SENSOR").max
        val problems = mutableListOf<String>()
        val sizes = mutableListOf<Long>()
        repeat(KEEPS) { index ->
            val stem = rig.pipeline.shoot().saved.stem.also(rig::track)
            delay(KEEP_DELAYS_MS[index % KEEP_DELAYS_MS.size])
            val dng = rig.pipeline.keepRaw(stem)
            if (dng == null) {
                problems += "#$index $stem: no DNG"
            } else {
                sizes += dng.sizeBytes
                val size = Tiff.imageSize(rig.read(dng.uri))
                if (size != rawSize) problems += "#$index $stem: DNG $size, expected $rawSize"
            }
        }
        Log.i(TAG, "FR-62 kept ${sizes.size}/$KEEPS; FR-64 DNG bytes min=${sizes.minOrNull()} max=${sizes.maxOrNull()}")
        assertEquals(emptyList<String>(), problems)
    }

    /** 20 stills never kept: 10 s later none can be kept and no DNG exists. */
    @Test
    fun fr62_unkeptRawIsGoneAfterTenSeconds() = runBlocking<Unit> {
        rig.start()
        val stems = List(KEEPS) { rig.pipeline.shoot().saved.stem.also(rig::track) }
        delay(EXPIRY_MS)
        val keptLate = stems.count { rig.pipeline.keepRaw(it) != null }
        val dngs = stems.sumOf { stem -> rig.files(stem).count { (_, name) -> name.endsWith(".dng") } }
        Log.i(TAG, "FR-62 unkept: late keeps=$keptLate DNGs=$dngs")
        assertEquals(0, keptLate)
        assertEquals(0, dngs)
    }

    /** The sixth still pushes the first out of the five-frame buffer. */
    @Test
    fun fr62_sixthStillPushesOutTheFirst() = runBlocking<Unit> {
        rig.start()
        val stems = List(SIX) { rig.pipeline.shoot().saved.stem.also(rig::track) }
        assertNull(rig.pipeline.keepRaw(stems.first()))
        stems.drop(1).forEach { assertNotNull("$it", rig.pipeline.keepRaw(it)) }
        Log.i(TAG, "FR-62 sixth pushes out the first: ok")
    }

    private companion object {
        const val TAG = "M2Acceptance"
        const val KEEPS = 20
        const val SIX = 6
        val KEEP_DELAYS_MS = listOf(2_000L, 5_000L, 9_000L)
        const val EXPIRY_MS = 10_500L
    }
}
