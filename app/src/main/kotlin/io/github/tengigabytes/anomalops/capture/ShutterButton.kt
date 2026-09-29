// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.capture

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.tengigabytes.anomalops.R
import io.github.tengigabytes.anomalops.dive.rgb
import io.github.tengigabytes.anomalops.theme.DivePalette
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.withTimeoutOrNull

/** FR-15: holding longer than this turns a press into a burst. */
private const val HOLD_TO_BURST_MS = 400L

/**
 * FR-52: the shutter strip across the short edge; the caller sizes it (docs/product/dive-lock-layout.md).
 * [onPress] fires at once (NFR-4, the first shot on press). If the finger stays down longer than
 * [HOLD_TO_BURST_MS], [onHold] starts a burst that should stop when the given deferred completes on release.
 * ADR-0006: the strip is excluded from system edge gestures.
 */
@Composable
fun ShutterButton(onPress: () -> Unit, onHold: (release: Deferred<Unit>) -> Unit, modifier: Modifier = Modifier) {
    val press by rememberUpdatedState(onPress)
    val hold by rememberUpdatedState(onHold)
    Column(
        modifier = modifier
            .systemGestureExclusion()
            .clip(RoundedCornerShape(12.dp))
            .background(rgb(DivePalette.SHUTTER_FILL))
            .semantics { role = Role.Button }
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    press()
                    val released = withTimeoutOrNull(HOLD_TO_BURST_MS) { tryAwaitRelease() }
                    if (released == null) {
                        val release = CompletableDeferred<Unit>()
                        hold(release)
                        tryAwaitRelease()
                        release.complete(Unit)
                    }
                })
            },
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val ink = rgb(DivePalette.SELECTED_TEXT)
        Text(stringResource(R.string.shutter), color = ink, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(stringResource(R.string.shutter_hint), color = ink, fontSize = 13.sp)
    }
}
