// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.core.camera.acceptance

import android.Manifest
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import io.github.tengigabytes.anomalops.core.profile.ScenePreset
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * FR-81 (docs/product/mvp-acceptance.md): in the dark no preset may fire the flash. Run it with the lenses covered
 * or in a dark room, on its own: `-Pandroid.testInstrumentationRunnerArguments.class=<this class>`.
 */
@RunWith(AndroidJUnit4::class)
class DarkRoomFlashTest {
    @get:Rule
    val cameraPermission: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.CAMERA)

    private lateinit var rig: CameraRig

    @Before
    fun setUp() {
        rig = CameraRig()
    }

    @After
    fun tearDown() = rig.close()

    @Test
    fun fr81_noFlashInTheDark() = runBlocking<Unit> {
        rig.start(ScenePreset.SNAPSHOT)
        val firedPerPreset = ScenePreset.entries.associateWith { preset ->
            rig.controller.select(preset, rig.conditions)
            val shots = List(SHOTS_PER_PRESET) { rig.capture() }
            val fired = shots.count { it.flashFired }
            val clamped = shots.count { it.spec.exposure?.isoClamped == true }
            val meanIso = shots.mapNotNull { it.reported.iso }.average()
            Log.i(Acceptance.TAG, "FR-81 $preset fired=$fired isoClamped=$clamped meanIso=%.0f".format(meanIso))
            fired
        }
        assertEquals(ScenePreset.entries.associateWith { 0 }, firedPerPreset)
    }

    private companion object {
        const val SHOTS_PER_PRESET = 20
    }
}
