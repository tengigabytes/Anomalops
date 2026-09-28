// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.core.camera.acceptance

import android.Manifest
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import io.github.tengigabytes.anomalops.core.profile.ScenePreset
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.random.Random

/** FR-11 and the preset-switch half of NFR-4 (docs/product/mvp-acceptance.md). */
@RunWith(AndroidJUnit4::class)
class PresetSwitchTest {
    @get:Rule
    val cameraPermission: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.CAMERA)

    private lateinit var rig: CameraRig

    @Before
    fun setUp() {
        rig = CameraRig()
    }

    @After
    fun tearDown() = rig.close()

    /**
     * 50 random switches, each to a different preset; a still after every switch is checked against FR-11. NFR-4
     * has one threshold for switches on the same physical lens and one for switches that rebuild the session.
     */
    @Test
    fun fr11_nfr4_fiftyRandomPresetSwitches() = runBlocking<Unit> {
        rig.start(ScenePreset.SNAPSHOT)
        val frameIntervalMs = medianFrameInterval()
        val random = Random(SEED)
        var current = ScenePreset.SNAPSHOT
        val sameLensMs = mutableListOf<Double>()
        val lensChangeMs = mutableListOf<Double>()
        val problems = mutableListOf<String>()
        repeat(SWITCHES) { index ->
            val next = ScenePreset.entries.filter { it != current }.random(random)
            val ms = timeSwitch(next)
            val lensChange = rig.profile.cameraFor(current)?.id != rig.profile.cameraFor(next)?.id
            if (lensChange) lensChangeMs += ms else sameLensMs += ms
            Log.i(Acceptance.TAG, "switch #$index $current -> $next lensChange=$lensChange %.1f ms".format(ms))
            val still = rig.capture()
            problems += PresetCheck.problems(rig.profile, rig.conditions, next, still).map { "#$index $next: $it" }
            current = next
        }
        problems.forEach { Log.e(Acceptance.TAG, "FR-11 $it") }
        Log.i(Acceptance.TAG, "FR-11 ${SWITCHES - problems.map { it.substringBefore(' ') }.distinct().size}/$SWITCHES")
        val sameLensP95 = Acceptance.report("NFR-4 switch, same lens", sameLensMs)
        val lensChangeP95 = Acceptance.report("NFR-4 switch, lens change", lensChangeMs)
        assertEquals(emptyList<String>(), problems)
        if (frameIntervalMs > BRIGHT_MAX_INTERVAL_MS) {
            Log.w(Acceptance.TAG, "NFR-4 not judged: preview at %.1f ms per frame, too dark".format(frameIntervalMs))
            return@runBlocking
        }
        assertTrue("NFR-4 same-lens p95 $sameLensP95 ms > $SAME_LENS_P95_MS ms", sameLensP95 <= SAME_LENS_P95_MS)
        assertTrue(
            "NFR-4 lens-change p95 $lensChangeP95 ms > $LENS_CHANGE_P95_MS ms",
            lensChangeP95 <= LENS_CHANGE_P95_MS,
        )
    }

    /**
     * Switch latency follows the preview frame duration, which auto-exposure stretches to 66 ms in the dark, so
     * NFR-4 is judged only at 30 fps (docs/product/mvp-acceptance.md).
     */
    private suspend fun medianFrameInterval(): Double {
        val timestamps = rig.controller.previewFrames.take(INTERVAL_FRAMES).toList().map { it.sensorTimestampNs }
        val intervalsMs = timestamps.zipWithNext { a, b -> (b - a) / NS_PER_MS }.sorted()
        val median = intervalsMs[intervalsMs.size / 2]
        Log.i(Acceptance.TAG, "preview frame interval median %.1f ms".format(median))
        return median
    }

    /** From the select call to the first preview result produced by the new preset's request. */
    private suspend fun timeSwitch(next: ScenePreset): Double = coroutineScope {
        val firstFrame = async(start = CoroutineStart.UNDISPATCHED) {
            rig.controller.previewFrames.first { it.spec.preset == next }
        }
        val pressedAt = SystemClock.elapsedRealtimeNanos()
        rig.controller.select(next, rig.conditions)
        val frame = withTimeout(SWITCH_TIMEOUT_MS) { firstFrame.await() }
        (frame.arrivedAtNs - pressedAt) / NS_PER_MS
    }

    private companion object {
        const val SEED = 20260928
        const val SWITCHES = 50
        const val SAME_LENS_P95_MS = 300.0
        const val LENS_CHANGE_P95_MS = 450.0
        const val BRIGHT_MAX_INTERVAL_MS = 40.0
        const val SWITCH_TIMEOUT_MS = 3_000L
        const val NS_PER_MS = 1e6
        const val INTERVAL_FRAMES = 31
    }
}
