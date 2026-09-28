// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.capture

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import io.github.tengigabytes.anomalops.R
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.withTimeoutOrNull

val SHUTTER_SIZE = 96.dp

/** FR-15: holding longer than this turns a press into a burst. */
private const val HOLD_TO_BURST_MS = 400L

/**
 * The shutter: [onPress] fires at once (NFR-4, the first shot on press). If the finger stays down longer than
 * [HOLD_TO_BURST_MS], [onHold] starts a burst that should stop when the given deferred completes on release.
 */
@Composable
fun ShutterButton(onPress: () -> Unit, onHold: (release: Deferred<Unit>) -> Unit, modifier: Modifier = Modifier) {
    val press by rememberUpdatedState(onPress)
    val hold by rememberUpdatedState(onHold)
    Box(
        modifier = modifier
            .size(SHUTTER_SIZE)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary)
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
        contentAlignment = Alignment.Center,
    ) {
        Text(stringResource(R.string.shutter), color = MaterialTheme.colorScheme.onPrimary)
    }
}
