// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.dive

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.tengigabytes.anomalops.lock.HoldToUnlock
import io.github.tengigabytes.anomalops.theme.DivePalette

/** A 0xRRGGBB palette entry as an opaque Compose colour. */
fun rgb(value: Int): Color = Color(value or OPAQUE)

private const val OPAQUE = 0xFF shl 24
private val KEY_SHAPE = RoundedCornerShape(8.dp)
private val BORDER = 1.dp
private val PROGRESS_BAR = 6.dp

/**
 * One 11 mm key (07-housing): opaque fill so NFR-5 contrast holds over any preview, a main label and an optional
 * small caption. Selected keys are amber with black text.
 */
@Composable
fun Key(
    label: String,
    modifier: Modifier = Modifier,
    caption: String? = null,
    selected: Boolean = false,
    onClick: () -> Unit,
) {
    val tap by rememberUpdatedState(onClick)
    val fill = rgb(if (selected) DivePalette.SELECTED_FILL else DivePalette.KEY_FILL)
    val text = rgb(if (selected) DivePalette.SELECTED_TEXT else DivePalette.TEXT)
    Column(
        modifier = modifier
            .clip(KEY_SHAPE)
            .background(fill)
            .border(BORDER, rgb(DivePalette.SECONDARY), KEY_SHAPE)
            .semantics { role = Role.Button }
            .pointerInput(Unit) { detectTapGestures(onTap = { tap() }) },
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        caption?.let { Text(it, color = rgb(DivePalette.SECONDARY), fontSize = 12.sp, textAlign = TextAlign.Center) }
        Text(label, color = text, fontSize = 16.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
    }
}

/**
 * FR-51: the unlock key. Holding it grows a bar along the bottom edge over 3 s (the label stays on the dark fill
 * for NFR-5); letting go earlier empties it and nothing happens. [onUnlock] runs once when the hold completes.
 */
@Composable
fun UnlockKey(label: String, hint: String, onUnlock: () -> Unit, modifier: Modifier = Modifier) {
    val unlock by rememberUpdatedState(onUnlock)
    val hold = remember { HoldToUnlock() }
    var pressed by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(pressed) {
        while (pressed) {
            withFrameMillis { }
            val now = SystemClock.uptimeMillis()
            progress = hold.progress(now)
            if (hold.poll(now)) unlock()
        }
        progress = 0f
    }
    Box(
        modifier = modifier
            .clip(KEY_SHAPE)
            .background(rgb(DivePalette.KEY_FILL))
            .border(BORDER, rgb(DivePalette.ALERT), KEY_SHAPE)
            .semantics { role = Role.Button }
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    hold.press(SystemClock.uptimeMillis())
                    pressed = true
                    tryAwaitRelease()
                    hold.release()
                    pressed = false
                })
            },
        contentAlignment = Alignment.BottomStart,
    ) {
        Box(Modifier.fillMaxWidth(progress).height(PROGRESS_BAR).background(rgb(DivePalette.ALERT)))
        Column(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(label, color = rgb(DivePalette.TEXT), fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text(hint, color = rgb(DivePalette.SECONDARY), fontSize = 11.sp, textAlign = TextAlign.Center)
        }
    }
}
