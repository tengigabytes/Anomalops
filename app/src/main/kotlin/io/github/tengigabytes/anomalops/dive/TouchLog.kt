// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.dive

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import io.github.tengigabytes.anomalops.lock.SessionRows
import io.github.tengigabytes.anomalops.touch.TouchDebouncer
import io.github.tengigabytes.anomalops.touch.TouchDown
import io.github.tengigabytes.anomalops.touch.TouchVerdict

/** Receives every press and release for `touches.csv`; the verdict is null for releases. */
fun interface TouchLog {
    fun record(action: SessionRows.Action, x: Float, y: Float, verdict: TouchVerdict?)
}

/**
 * NFR-6 for the whole screen: sits on the root and sees each press first (initial pass). A repeat is consumed, so
 * no key below reacts to it (tap and click detectors ignore consumed presses). Every press and release goes to
 * [log] with its verdict (ADR-0008).
 */
fun Modifier.touchFilter(debouncer: TouchDebouncer, log: TouchLog): Modifier = pointerInput(debouncer, log) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            event.changes.forEach { change ->
                val x = change.position.x
                val y = change.position.y
                if (change.changedToDown()) {
                    val verdict = debouncer.check(TouchDown(x, y, change.uptimeMillis))
                    log.record(SessionRows.Action.DOWN, x, y, verdict)
                    if (verdict == TouchVerdict.REPEAT) change.consume()
                } else if (change.changedToUp()) {
                    log.record(SessionRows.Action.UP, x, y, null)
                }
            }
        }
    }
}
