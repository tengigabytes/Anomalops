// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.core.camera.acceptance

import android.Manifest
import android.graphics.BitmapFactory
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import io.github.tengigabytes.anomalops.core.camera.focus.ScanOutcome
import io.github.tengigabytes.anomalops.core.camera.request.FocusSpec
import io.github.tengigabytes.anomalops.core.camera.session.FocusScan
import io.github.tengigabytes.anomalops.core.profile.ScenePreset
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * FR-35 / FR-31 on the device (docs/test/m4-af-timeline.md): entering the macro preset sends one AUTO trigger,
 * whose search settles within 0.5 s; staying on the preset does not scan again; a still right after entering
 * waits for the scan, and its output has at least 12 MP (FR-31). Place a printed page about 5 cm from the
 * lenses. Stills stay in memory, nothing is saved.
 */
@RunWith(AndroidJUnit4::class)
class MacroFocusTest {
    @get:Rule
    val cameraPermission: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.CAMERA)

    private val rig = CameraRig()

    @After
    fun tearDown() = rig.close()

    @Test
    fun fr35_enteringMacroScansOnceWithinHalfASecond() = runBlocking<Unit> {
        rig.start(ScenePreset.SNAPSHOT)
        val scans = List(VISITS) {
            val scan = enterMacro()
            Log.i(TAG, "FR-35 scan $scan")
            assertNull("staying on macro scans no more", withTimeoutOrNull(QUIET_MS) { nextScanAfter { reselect() } })
            rig.controller.select(ScenePreset.SNAPSHOT, rig.conditions)
            scan
        }
        val locked = scans.count { it.outcome == ScanOutcome.LOCKED }
        Log.i(TAG, "FR-35 visits=$VISITS locked=$locked fallbacks=${scans.count { it.fallbackDiopters != null }}")
        Acceptance.report("FR-35 search", scans.map { it.searchMs })
        Acceptance.report("FR-31 trigger to outcome", scans.map { it.totalMs })
        scans.forEach { scan ->
            assertEquals(MACRO_LENS, scan.physicalId)
            assertTrue("FR-35 search ${scan.searchMs} ms > $SEARCH_MS", scan.searchMs <= SEARCH_MS)
            assertTrue("FR-31 lock ${scan.totalMs} ms > $LOCK_MS", scan.totalMs <= LOCK_MS)
        }
    }

    @Test
    fun fr35_aStillRightAfterEnteringMacroWaitsForTheScan() = runBlocking<Unit> {
        rig.start(ScenePreset.SNAPSHOT)
        val scan = async(start = CoroutineStart.UNDISPATCHED) { rig.controller.focusScans.first() }
        rig.controller.select(ScenePreset.MACRO, rig.conditions)
        val still = rig.capture()
        assertTrue("the scan settled before the still", scan.isCompleted)
        val settled = scan.await()
        val expected = settled.fallbackDiopters?.let { FocusSpec.Fixed(it) } ?: FocusSpec.Auto
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(still.bytes, 0, still.bytes.size, bounds)
        val pixels = bounds.outWidth.toLong() * bounds.outHeight
        Log.i(TAG, "FR-35 still after entering: $settled still focus=${still.spec.focus} af=${still.reported.afMode}")
        Log.i(TAG, "FR-31 macro still ${still.format} ${bounds.outWidth}x${bounds.outHeight} = $pixels px")
        assertEquals(expected, still.spec.focus)
        assertTrue("FR-31 output $pixels px < $MIN_PIXELS", pixels >= MIN_PIXELS)
    }

    private suspend fun enterMacro(): FocusScan =
        withTimeout(SCAN_TIMEOUT_MS) { nextScanAfter { rig.controller.select(ScenePreset.MACRO, rig.conditions) } }

    private suspend fun reselect() = rig.controller.select(ScenePreset.MACRO, rig.conditions)

    private suspend fun nextScanAfter(action: suspend () -> Unit): FocusScan = coroutineScope {
        val next = async(start = CoroutineStart.UNDISPATCHED) { rig.controller.focusScans.first() }
        action()
        next.await()
    }

    private companion object {
        const val TAG = "M4Acceptance"
        const val MACRO_LENS = "9"
        const val VISITS = 10
        const val SEARCH_MS = 500.0
        const val LOCK_MS = 2_000.0
        const val SCAN_TIMEOUT_MS = 3_000L
        const val QUIET_MS = 1_500L
        const val MIN_PIXELS = 12_000_000L
    }
}
