// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.lock

import android.content.Context
import android.os.Build
import android.os.SystemClock
import io.github.tengigabytes.anomalops.core.telemetry.log.DiveLog
import io.github.tengigabytes.anomalops.core.telemetry.log.SensorRecorder
import io.github.tengigabytes.anomalops.core.telemetry.log.SessionInfo
import io.github.tengigabytes.anomalops.touch.TouchVerdict
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

/**
 * One FR-45 dive session (ADR-0008): opened when dive lock starts, closed on unlock. Reopening the same ID after a
 * crash restart appends to the same files; `session.json` keeps its first pairing. Rows are written off the main
 * thread, in order, on one writer thread.
 */
class DiveSession private constructor(
    val id: String,
    private val log: DiveLog,
    private val recorder: SensorRecorder,
    private val scope: CoroutineScope,
) {
    private val writer = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

    // Main-thread events after close are dropped rather than sent to a closed writer.
    @Volatile
    private var closed = false

    fun touch(action: SessionRows.Action, x: Float, y: Float, verdict: TouchVerdict?) {
        if (closed) return
        val row = SessionRows.touch(SystemClock.elapsedRealtimeNanos(), action, x, y, verdict)
        scope.launch(writer) { log.touches.append(row) }
    }

    fun capture(capture: SessionRows.Capture) {
        if (closed) return
        scope.launch(writer) { log.captures.append(capture.row()) }
    }

    /** Stops the 1 Hz sampler and closes the files; pending rows are written first. */
    suspend fun close() {
        if (closed) return
        closed = true
        recorder.stop()
        withContext(writer) { log.close() }
        writer.close()
    }

    companion object {
        suspend fun open(context: Context, sessionId: String, scope: CoroutineScope): DiveSession =
            withContext(Dispatchers.IO) {
                val root = checkNotNull(context.getExternalFilesDir(null)) { "no app-specific external storage" }
                val version = context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
                val info = SessionInfo(
                    id = sessionId,
                    startUtcMs = System.currentTimeMillis(),
                    startElapsedNs = SystemClock.elapsedRealtimeNanos(),
                    device = Build.DEVICE,
                    appVersion = version,
                )
                val log = DiveLog.open(root, info, SessionRows.TOUCH_HEADER, SessionRows.CAPTURE_HEADER)
                val recorder = SensorRecorder(context, log.sensors)
                recorder.start(scope)
                DiveSession(sessionId, log, recorder, scope)
            }
    }
}
