// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.acceptance

import android.Manifest
import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import io.github.tengigabytes.anomalops.R
import io.github.tengigabytes.anomalops.conditions.ShootingConditions
import io.github.tengigabytes.anomalops.core.camera.request.ColorSpec
import io.github.tengigabytes.anomalops.core.profile.DepthBand
import io.github.tengigabytes.anomalops.core.profile.LensFilter
import io.github.tengigabytes.anomalops.core.profile.ScenePreset
import io.github.tengigabytes.anomalops.core.telemetry.depth.DepthZone
import io.github.tengigabytes.anomalops.core.telemetry.depth.ManualDepthSource
import io.github.tengigabytes.anomalops.dive.DiveActions
import io.github.tengigabytes.anomalops.dive.DiveDeps
import io.github.tengigabytes.anomalops.dive.DiveScreen
import io.github.tengigabytes.anomalops.dive.presetLabel
import io.github.tengigabytes.anomalops.layout.MIN_SHUTTER_DP
import io.github.tengigabytes.anomalops.settings.SettingsScreen
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * docs/test/m3-test-plan.md, section 3, on the device: the dive screen's controls measured from the Compose
 * semantics tree in millimetres (physical xdpi / ydpi) and dp. FR-12 presets on one long edge, FR-52 shutter
 * strip, FR-55 margin in normal mode and in dive lock, the depth-band and dive-light keys re-planning the preview,
 * and FR-24 (the filter from the settings page; no settings key in dive lock). The screen runs on the real camera
 * through [AppRig]; the host below mirrors MainActivity's switch to the settings page, and "locked" is set
 * directly instead of pinning the app. Takes no photos.
 */
@RunWith(AndroidJUnit4::class)
class DiveScreenTest {
    @get:Rule(order = 0)
    val cameraPermission: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.CAMERA)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val rig = AppRig()
    private val depth = ManualDepthSource(DepthZone.SHALLOW)
    private val conditions = ShootingConditions(depth)
    private var showing by mutableStateOf(true)
    private var locked by mutableStateOf(false)
    private var settingsOpen by mutableStateOf(false)
    private var filter by mutableStateOf(LensFilter.NONE)
    private var lockPresses = 0
    private val isButton = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)

    @Before
    fun setUp() {
        val deps = DiveDeps(rig.controller, rig.pipeline, conditions, depth)
        val actions = DiveActions({ lockPresses++ }, {}, { settingsOpen = true }, { null })
        compose.setContent {
            MaterialTheme {
                when {
                    !showing -> Unit

                    settingsOpen && !locked -> SettingsScreen(
                        filter = filter,
                        onFilter = {
                            filter = it
                            conditions.mount(it)
                        },
                        onBack = { settingsOpen = false },
                    )

                    else -> DiveScreen(deps, actions, locked)
                }
            }
        }
        awaitColor(approximate = false)
    }

    @After
    fun tearDown() {
        // The preview's surface goes first, so the camera stops before the rig releases it.
        showing = false
        compose.waitForIdle()
        rig.close()
    }

    @Test
    fun fr12_fr52_fr55_normalModeLayout() {
        val geometry = ScreenGeometry(compose.activity)
        assertTrue("the host window must cover the display", geometry.coversDisplay)
        val controls = controls()
        val presets = ScenePreset.entries.map { bounds(text(presetLabel(it))) }
        report("normal", geometry, controls)
        assertEquals(emptyList<String>(), geometry.problems(controls, text(R.string.shutter), MIN_SHUTTER_DP))
        // FR-12: one column on the right long edge, all on screen, none scrolled away.
        assertTrue("presets not in one column: $presets", presets.all { it.left == presets.first().left })
        assertTrue("presets not on the right half", presets.first().left > geometry.widthPx / 2)
        assertEquals(ScenePreset.entries.size + NORMAL_EXTRAS, controls.size)
    }

    @Test
    fun fr24_fr55_diveLockLayout() {
        val lockKey = bounds(text(R.string.lock_enter))
        locked = true
        compose.waitForIdle()
        val geometry = ScreenGeometry(compose.activity)
        val controls = controls()
        report("locked", geometry, controls)
        assertEquals(emptyList<String>(), geometry.problems(controls, text(R.string.shutter), MIN_SHUTTER_DP))
        // FR-24: no settings key in dive lock; the unlock key takes the lock key's place, so nothing moves.
        assertTrue(compose.onAllNodes(key(text(R.string.settings)), true).fetchSemanticsNodes().isEmpty())
        assertEquals(lockKey, bounds(text(R.string.unlock)))
    }

    @Test
    fun fr12_oneTapSelectsEachPreset() {
        val latenciesMs = ScenePreset.entries.reversed().map { preset ->
            val tappedAt = SystemClock.elapsedRealtime()
            tap(text(presetLabel(preset)))
            runBlocking { withTimeout(TIMEOUT_MS) { rig.controller.state.first { it.preset == preset } } }
            SystemClock.elapsedRealtime() - tappedAt
        }
        Log.i(TAG, "FR-12 one tap each, select latencies $latenciesMs ms")
    }

    @Test
    fun depthAndLightKeysReplanThePreview() {
        // Only shallow, no filter, no light is calibrated on this device (M1), so each change shows in the colour.
        tapAndAwait(text(R.string.depth_label), approximate = true)
        assertEquals(DepthBand.MID, conditions.current.depthBand)
        tap(text(R.string.depth_label))
        compose.waitUntil(TIMEOUT_MS) { conditions.current.depthBand == DepthBand.DEEP }
        tapAndAwait(text(R.string.depth_label), approximate = false)
        assertEquals(DepthBand.SHALLOW, conditions.current.depthBand)
        tapAndAwait(text(R.string.light_label), approximate = true)
        assertTrue(conditions.current.diveLight)
        tapAndAwait(text(R.string.light_label), approximate = false)
        Log.i(TAG, "depth and light keys: shallow -> mid -> deep -> shallow, light on -> off, preview followed")
    }

    @Test
    fun fr24_filterFromTheSettingsPage() {
        tap(text(R.string.lock_enter))
        assertEquals(1, lockPresses)
        tap(text(R.string.settings))
        tap(text(R.string.filter_red))
        tapAndAwait(text(R.string.back), approximate = true)
        assertEquals(LensFilter.RED, conditions.current.filter)
        val status = compose.onAllNodes(hasText(text(R.string.filter_short_red), substring = true), true)
        assertTrue("status band shows the filter", status.fetchSemanticsNodes().isNotEmpty())
        Log.i(TAG, "FR-24 red filter from settings: preview falls back to approximate (no calibration on land)")
    }

    /** Every clickable control, named by its first text (the thumbnail has none). */
    private fun controls(): Map<String, Rect> =
        compose.onAllNodes(isButton or hasClickAction(), true).fetchSemanticsNodes().associate { node ->
            val texts = node.children.flatMap { it.config.getOrElse(SemanticsProperties.Text) { emptyList() } }
            (texts.firstOrNull()?.text ?: "thumbnail") to node.boundsInWindow
        }

    private fun report(mode: String, geometry: ScreenGeometry, controls: Map<String, Rect>) {
        val shutter = controls.getValue(text(R.string.shutter))
        val keys = controls.filterKeys { it != text(R.string.shutter) }.values
        Log.i(
            TAG,
            (
                "FR-55 $mode: screen %.1f x %.1f mm, %d controls, nearest edge %.2f mm; " +
                    "keys %.1f-%.1f dp; shutter %.1f dp wide"
                ).format(
                geometry.widthMm,
                geometry.heightMm,
                controls.size,
                controls.values.minOf { geometry.edgeMm(it) },
                keys.minOf { minOf(geometry.widthDp(it), geometry.heightDp(it)) },
                keys.maxOf { maxOf(geometry.widthDp(it), geometry.heightDp(it)) },
                geometry.widthDp(shutter),
            ),
        )
    }

    private fun key(label: String) = isButton and hasAnyDescendant(hasText(label))

    private fun bounds(label: String): Rect = compose.onNode(key(label), true).fetchSemanticsNode().boundsInWindow

    /**
     * A real touch at the key's centre (keys use tap detectors, not click actions), spaced past NFR-6's 200 ms. The
     * injected events carry the test's main clock, not the wall clock, so that clock is advanced as well; otherwise
     * a second tap on the same key counts as a repeat and is dropped.
     */
    private fun tap(label: String) {
        SystemClock.sleep(TAP_GAP_MS)
        compose.mainClock.advanceTimeBy(TAP_GAP_MS)
        compose.onNode(key(label), true).performTouchInput { click() }
        compose.waitForIdle()
    }

    private fun tapAndAwait(label: String, approximate: Boolean) = runBlocking {
        val frame = async(start = CoroutineStart.UNDISPATCHED) { awaitFrame(approximate) }
        tap(label)
        frame.await()
    }

    private fun awaitColor(approximate: Boolean) = runBlocking { awaitFrame(approximate) }

    private suspend fun awaitFrame(approximate: Boolean) = withTimeout(TIMEOUT_MS) {
        rig.controller.previewFrames.first { (it.spec.color == ColorSpec.AutoApproximate) == approximate }
    }

    private fun text(id: Int): String = compose.activity.getString(id)

    private companion object {
        const val TAG = "M3Acceptance"
        const val TIMEOUT_MS = 5_000L
        const val TAP_GAP_MS = 300L

        // Lock, settings, depth, light, thumbnail, shutter.
        const val NORMAL_EXTRAS = 6
    }
}
