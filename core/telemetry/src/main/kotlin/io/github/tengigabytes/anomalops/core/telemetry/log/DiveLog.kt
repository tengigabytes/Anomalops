// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.core.telemetry.log

import java.io.Closeable
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * `session.json` (ADR-0008): the one place that pairs UTC with `elapsedRealtimeNanos`; every other timestamp in
 * the session is elapsed nanoseconds.
 */
data class SessionInfo(
    val id: String,
    val startUtcMs: Long,
    val startElapsedNs: Long,
    val device: String,
    val appVersion: String,
) {
    fun toJson(): String = buildString {
        append("{\n")
        append("  \"id\": ").append(quote(id)).append(",\n")
        append("  \"start_utc\": ").append(quote(Instant.ofEpochMilli(startUtcMs).toString())).append(",\n")
        append("  \"start_utc_ms\": ").append(startUtcMs).append(",\n")
        append("  \"start_elapsed_ns\": ").append(startElapsedNs).append(",\n")
        append("  \"device\": ").append(quote(device)).append(",\n")
        append("  \"app_version\": ").append(quote(appVersion)).append("\n")
        append("}\n")
    }

    companion object {
        private val ID_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")

        /** Local wall-clock time of the start, like the photo names, so a session sorts next to its photos. */
        fun idAt(start: LocalDateTime): String = "DIVE_" + ID_FORMAT.format(start)

        private fun quote(text: String): String = buildString {
            append('"')
            text.forEach { c ->
                when {
                    c == '"' || c == '\\' -> append('\\').append(c)
                    c < ' ' -> append("\\u%04x".format(c.code))
                    else -> append(c)
                }
            }
            append('"')
        }
    }
}

/**
 * The files of one dive session, `<root>/dives/<id>/` (ADR-0008; root is the app's external files directory).
 * The touch and capture columns are defined by `:app`, which owns those events.
 */
class DiveLog private constructor(val dir: File, val sensors: CsvLog, val touches: CsvLog, val captures: CsvLog) :
    Closeable {
    override fun close() {
        sensors.close()
        touches.close()
        captures.close()
    }

    companion object {
        const val SESSION_FILE = "session.json"
        const val SENSORS_FILE = "sensors.csv"
        const val TOUCHES_FILE = "touches.csv"
        const val CAPTURES_FILE = "captures.csv"

        /** Opens the session, writing `session.json` only once so a restart keeps the original pairing. */
        fun open(root: File, info: SessionInfo, touchHeader: List<String>, captureHeader: List<String>): DiveLog {
            val dir = File(File(root, "dives"), info.id)
            check(dir.isDirectory || dir.mkdirs()) { "cannot create $dir" }
            val session = File(dir, SESSION_FILE)
            if (!session.exists()) session.writeText(info.toJson(), Charsets.UTF_8)
            return DiveLog(
                dir,
                CsvLog(File(dir, SENSORS_FILE), SensorSample.HEADER),
                CsvLog(File(dir, TOUCHES_FILE), touchHeader),
                CsvLog(File(dir, CAPTURES_FILE), captureHeader),
            )
        }
    }
}
