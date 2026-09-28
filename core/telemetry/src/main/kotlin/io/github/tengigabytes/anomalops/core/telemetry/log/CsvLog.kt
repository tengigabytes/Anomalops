// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.core.telemetry.log

import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.Writer
import java.util.Locale

/** RFC 4180 rows with locale-independent numbers; null is an empty field (a sensor without a reading). */
object Csv {
    private const val NEEDS_QUOTES = ",\"\n\r"

    fun row(values: List<Any?>): String = values.joinToString(",") { field(it) }

    private fun field(value: Any?): String {
        val text = when (value) {
            null -> ""
            is Float -> if (value.isFinite()) String.format(Locale.ROOT, "%.3f", value) else ""
            is Double -> if (value.isFinite()) String.format(Locale.ROOT, "%.3f", value) else ""
            else -> value.toString()
        }
        return if (text.any { it in NEEDS_QUOTES }) {
            "\"" + text.replace("\"", "\"\"") + "\""
        } else {
            text
        }
    }
}

/**
 * One CSV file of a dive session (ADR-0008). The header is written when the file is new; an existing file is
 * appended to, so a restart after a crash (ADR-0006) continues the same session. Every row is flushed, because
 * the app can be killed at any time and the rows are the experiment's data.
 */
class CsvLog(file: File, private val header: List<String>) : Closeable {
    private val writer: Writer

    init {
        val fresh = !file.exists() || file.length() == 0L
        writer = FileOutputStream(file, true).bufferedWriter(Charsets.UTF_8)
        if (fresh) write(header)
    }

    @Synchronized
    fun append(values: List<Any?>) {
        require(values.size == header.size) { "expected ${header.size} fields, got ${values.size}" }
        write(values)
    }

    private fun write(values: List<Any?>) {
        writer.write(Csv.row(values))
        writer.write("\n")
        writer.flush()
    }

    @Synchronized
    override fun close() = writer.close()
}
