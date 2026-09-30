// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.acceptance

import android.Manifest
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import io.github.tengigabytes.anomalops.conditions.ConditionsFollower
import io.github.tengigabytes.anomalops.conditions.ShootingConditions
import io.github.tengigabytes.anomalops.core.camera.request.ColorSpec
import io.github.tengigabytes.anomalops.core.camera.session.CameraStatus
import io.github.tengigabytes.anomalops.core.profile.ScenePreset
import io.github.tengigabytes.anomalops.core.telemetry.depth.DepthZone
import io.github.tengigabytes.anomalops.core.telemetry.depth.ManualDepthSource
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.ceil

/**
 * FR-84 / FR-21 on the device: switching the manual depth zone re-plans the live preview through
 * [ShootingConditions] and [ConditionsFollower], the same way the capture screen follows it. Lens 2 has a shallow calibration and no deep
 * one (docs/test/m1-pipeline-calibration.md), so the preview alternates between manual colour and approximate
 * auto, on the same lens and without a failure. Takes no photos.
 */
@RunWith(AndroidJUnit4::class)
class ConditionsSwitchTest {
    @get:Rule
    val cameraPermission: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.CAMERA)

    private val rig = AppRig()

    @After
    fun tearDown() = rig.close()

    @Test
    fun fr84_manualDepthSwitchReplansTheLivePreview() = runBlocking<Unit> {
        val depth = ManualDepthSource(DepthZone.SHALLOW)
        val follower = ConditionsFollower(ShootingConditions(depth))
        rig.start(PRESET, follower.send())
        val initial = withTimeout(TIMEOUT_MS) { rig.controller.previewFrames.first() }
        assertTrue("shallow on lens 2 is calibrated: ${initial.spec.color}", initial.spec.color is ColorSpec.Manual)
        // As the dive screen does: every new key re-plans the preview on the current preset.
        val following = launch { follower.changes.collect { rig.controller.select(PRESET, it) } }
        val latenciesMs = List(SWITCHES) { i ->
            val zone = if (i % 2 == 0) DepthZone.DEEP else DepthZone.SHALLOW
            switch(depth, zone)
        }.sorted()
        following.cancel()
        val p95 = latenciesMs[ceil(P95 * latenciesMs.size).toInt() - 1]
        Log.i(
            TAG,
            "FR-84 depth switches n=${latenciesMs.size} median=%.1f p95=%.1f max=%.1f ms".format(
                latenciesMs[latenciesMs.size / 2],
                p95,
                latenciesMs.last(),
            ),
        )
        assertEquals(CameraStatus.PREVIEWING, rig.controller.state.value.status)
    }

    /** Milliseconds from the switch to the first preview frame carrying the new colour. */
    private suspend fun switch(depth: ManualDepthSource, zone: DepthZone): Double = coroutineScope {
        val approximate = zone == DepthZone.DEEP
        val firstFrame = async(start = CoroutineStart.UNDISPATCHED) {
            rig.controller.previewFrames.first { (it.spec.color == ColorSpec.AutoApproximate) == approximate }
        }
        val switchedAt = SystemClock.elapsedRealtimeNanos()
        depth.select(zone)
        val frame = withTimeout(TIMEOUT_MS) { firstFrame.await() }
        val state = withTimeout(TIMEOUT_MS) { rig.controller.state.first { it.colorApproximate == approximate } }
        assertEquals("same lens, no reconfiguration", LENS, frame.spec.physicalId)
        assertEquals(LENS, state.physicalId)
        (frame.arrivedAtNs - switchedAt) / NS_PER_MS
    }

    private companion object {
        const val TAG = "M4Acceptance"
        val PRESET = ScenePreset.SNAPSHOT
        const val LENS = "2"
        const val SWITCHES = 20
        const val TIMEOUT_MS = 3_000L
        const val P95 = 0.95
        const val NS_PER_MS = 1e6
    }
}
