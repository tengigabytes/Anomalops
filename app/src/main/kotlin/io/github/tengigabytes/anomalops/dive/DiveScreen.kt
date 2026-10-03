// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.dive

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.tengigabytes.anomalops.R
import io.github.tengigabytes.anomalops.capture.CameraPreview
import io.github.tengigabytes.anomalops.capture.MergeSwitch
import io.github.tengigabytes.anomalops.capture.ShotPipeline
import io.github.tengigabytes.anomalops.conditions.ConditionsFollower
import io.github.tengigabytes.anomalops.conditions.ShootingConditions
import io.github.tengigabytes.anomalops.core.camera.session.CameraController
import io.github.tengigabytes.anomalops.core.camera.session.CameraStatus
import io.github.tengigabytes.anomalops.core.telemetry.depth.ManualDepthSource
import io.github.tengigabytes.anomalops.layout.DiveLockLayout
import io.github.tengigabytes.anomalops.layout.SAFE_MARGIN_MM
import io.github.tengigabytes.anomalops.lock.DiveSession
import io.github.tengigabytes.anomalops.theme.DivePalette
import io.github.tengigabytes.anomalops.touch.DEBOUNCE_RADIUS_DP
import io.github.tengigabytes.anomalops.touch.TouchDebouncer
import kotlinx.coroutines.delay

private const val TAG = "DiveScreen"
private const val NOTE_MS = 3_000L

/** Status text keeps clear of the rounded corners; about half the safe margin. */
private const val STATUS_SIDE_MM = SAFE_MARGIN_MM / 2

/** What the dive screen drives. */
class DiveDeps(
    val controller: CameraController,
    val pipeline: ShotPipeline,
    val conditions: ShootingConditions,
    val depth: ManualDepthSource,
    val merge: MergeSwitch,
)

/** What the dive screen asks of the activity. [session] is the open FR-45 session, if any. */
class DiveActions(
    val onLock: () -> Unit,
    val onUnlock: () -> Unit,
    val onSettings: () -> Unit,
    val session: () -> DiveSession?,
)

/**
 * The one camera screen of docs/product/dive-lock-layout.md, used in normal mode and in dive lock; only the top
 * two left keys and the status band differ. Every press passes the NFR-6 filter and is logged to FR-45.
 */
@Composable
fun DiveScreen(deps: DiveDeps, actions: DiveActions, locked: Boolean) {
    val scale = rememberMmScale()
    val radiusPx = with(LocalDensity.current) { DEBOUNCE_RADIUS_DP.dp.toPx() }
    val debouncer = remember(radiusPx) { TouchDebouncer(radiusPx) }
    val touchLog = remember(actions) { TouchLog { a, x, y, v -> actions.session()?.touch(a, x, y, v) } }
    BoxWithConstraints(
        Modifier.fillMaxSize().background(rgb(DivePalette.BACKGROUND)).touchFilter(debouncer, touchLog),
    ) {
        val layout = remember(constraints.maxWidth, constraints.maxHeight, scale) {
            DiveLockLayout(scale.widthMm(constraints.maxWidth), scale.heightMm(constraints.maxHeight)).also {
                it.violations(scale.mmPerDp).forEach { problem -> Log.w(TAG, "layout: $problem") }
            }
        }
        DiveContent(deps, actions, locked, layout, scale)
    }
}

@Composable
private fun DiveContent(deps: DiveDeps, actions: DiveActions, locked: Boolean, layout: DiveLockLayout, scale: MmScale) {
    val camera by deps.controller.state.collectAsState()
    val key by deps.conditions.keys.collectAsState(initial = deps.conditions.current)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val shots = remember(deps, actions) { ShotControls(deps, actions, context, scope) }
    LaunchedEffect(shots.note) {
        if (shots.note != null) {
            delay(NOTE_MS)
            shots.note = null
        }
    }
    // FR-21/24/25: a new depth zone, filter or dive light re-plans the preview on the current lens.
    val follower = remember(deps.conditions) { ConditionsFollower(deps.conditions) }
    LaunchedEffect(follower) {
        follower.changes.collect { k -> shots.launchSafely { deps.controller.select(shots.preset, k) } }
    }
    Box(Modifier.fillMaxSize()) {
        CameraPreview(
            onSurfaceReady = { surface ->
                shots.launchSafely { deps.controller.start(surface, shots.preset, follower.send()) }
            },
            onSurfaceGone = {
                follower.closed()
                deps.controller.stopBlocking()
            },
            modifier = Modifier.place(layout.preview, scale),
        )
        LeftKeys(layout, scale, locked, key, actions, deps)
        ModeKeys(layout, scale, shots) { follower.send() }
        LowerKeys(layout, scale, shots)
        val failed = camera.status == CameraStatus.FAILED
        StatusBand(
            readings = rememberReadings(),
            locked = locked,
            conditions = conditionsLine(key, camera.colorApproximate),
            note = if (failed) stringResource(R.string.camera_failed, camera.error.orEmpty()) else shots.note,
            sidePadding = scale.xDp(STATUS_SIDE_MM),
            modifier = Modifier.place(layout.statusBand, scale),
        )
    }
}
