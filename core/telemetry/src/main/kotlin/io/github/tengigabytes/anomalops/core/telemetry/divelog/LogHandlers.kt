// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.core.telemetry.divelog

import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/** Element path and the text of the current element, shared by the two log readers. */
internal abstract class LogHandler : DefaultHandler() {
    protected val path = ArrayList<String>()
    private val text = StringBuilder()
    val dives = mutableListOf<DiveProfile>()

    final override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes) {
        val name = name(localName, qName)
        path += name
        text.setLength(0)
        start(name, attributes)
    }

    final override fun endElement(uri: String?, localName: String?, qName: String?) {
        end(name(localName, qName), text.toString().trim())
        path.removeAt(path.lastIndex)
        text.setLength(0)
    }

    final override fun characters(ch: CharArray, start: Int, length: Int) {
        text.appendRange(ch, start, start + length)
    }

    protected open fun start(name: String, attributes: Attributes) = Unit

    protected open fun end(name: String, text: String) = Unit

    /** The element that contains the current one. */
    protected fun parent(): String? = path.getOrNull(path.lastIndex - 1)

    private fun name(localName: String?, qName: String?): String =
        localName?.takeIf { it.isNotEmpty() } ?: qName.orEmpty().substringAfter(':')
}

/**
 * UDDF (`uddf/profiledata/repetitiongroup/dive`): the start is `informationbeforedive/datetime`, each `waypoint`
 * has `divetime` in seconds, `depth` in metres and `temperature` in kelvin, as the format uses SI units.
 */
internal class UddfHandler : LogHandler() {
    private var inDive = false
    private var start: LocalDateTime? = null
    private val points = mutableListOf<ProfilePoint>()
    private var time: Double? = null
    private var depth: Double? = null
    private var temp: Double? = null

    override fun start(name: String, attributes: Attributes) {
        if (name == "dive" && "profiledata" in path) {
            inDive = true
            start = null
            points.clear()
        } else if (name == "waypoint") {
            time = null
            depth = null
            temp = null
        }
    }

    override fun end(name: String, text: String) {
        if (!inDive) return
        val inWaypoint = parent() == "waypoint"
        when {
            name == "datetime" && parent() == "informationbeforedive" -> start = parseLogDateTime(text)

            name == "divetime" && inWaypoint -> time = number(text)

            name == "depth" && inWaypoint -> depth = number(text)

            name == "temperature" && inWaypoint -> temp = number(text) - KELVIN_AT_0C

            name == "waypoint" -> time?.let { points += ProfilePoint(it, depth, temp) }

            name == "dive" -> {
                endDive()
                inDive = false
            }
        }
    }

    /** A dive with samples needs a start; an empty placeholder (some logbooks write one) is skipped. */
    private fun endDive() {
        val begin = start
        if (begin != null) {
            dives += DiveProfile(begin, points.sortedBy { it.offsetS })
        } else if (points.isNotEmpty()) {
            throw DiveLogFormatException("UDDF dive with samples but no informationbeforedive/datetime")
        }
    }

    private companion object {
        const val KELVIN_AT_0C = 273.15
    }
}

/**
 * Subsurface XML (`divelog` or `dives`, dives optionally inside `trip`): the start is the `dive`'s `date` and
 * `time` attributes; samples sit in the first `divecomputer`, or directly in `dive` in older files. Sample values
 * carry their unit (`time='1:30 min'`, `depth='5.2 m'`, `temp='27.0 C'`).
 */
internal class SubsurfaceHandler : LogHandler() {
    private var start: LocalDateTime? = null
    private val points = mutableListOf<ProfilePoint>()
    private var computers = 0

    override fun start(name: String, attributes: Attributes) {
        when (name) {
            "dive" -> {
                start = diveStart(attributes)
                points.clear()
                computers = 0
            }

            "divecomputer" -> if (parent() == "dive") computers++

            "sample" -> if (start != null && takesSamples()) points += sample(attributes)
        }
    }

    override fun end(name: String, text: String) {
        if (name != "dive") return
        start?.let { dives += DiveProfile(it, points.sortedBy { point -> point.offsetS }) }
        start = null
    }

    private fun takesSamples(): Boolean = when (parent()) {
        "dive" -> true
        "divecomputer" -> computers == 1
        else -> false
    }

    private fun diveStart(attributes: Attributes): LocalDateTime {
        val date = attributes.getValue("date")
        val time = attributes.getValue("time")
        if (date == null || time == null) throw DiveLogFormatException("Subsurface dive without date and time")
        return try {
            LocalDateTime.of(LocalDate.parse(date, CALENDAR), LocalTime.parse(time, CLOCK))
        } catch (e: DateTimeParseException) {
            throw DiveLogFormatException("Subsurface dive date '$date' time '$time': ${e.message}")
        }
    }

    private fun sample(attributes: Attributes): ProfilePoint {
        val time = attributes.getValue("time") ?: throw DiveLogFormatException("Subsurface sample without time")
        return ProfilePoint(
            offsetS = minutesSeconds(time),
            depthM = attributes.getValue("depth")?.let { measure(it, "m") },
            tempC = attributes.getValue("temp")?.let { measure(it, "C") },
        )
    }

    private fun minutesSeconds(text: String): Double {
        val match = SAMPLE_TIME.matchEntire(text) ?: throw DiveLogFormatException("Subsurface sample time '$text'")
        val (minutes, seconds) = match.destructured
        return minutes.toDouble() * SECONDS_PER_MINUTE + seconds.toDouble()
    }

    private companion object {
        /** Minutes can exceed 59 ("112:30 min"); Subsurface writes no hours. */
        val SAMPLE_TIME = Regex("""(\d+):(\d{2}) min""")

        /** Older files write the month, day and hour without a leading zero ("2014-4-1", "6:00:00"). */
        val CALENDAR: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuu-M-d")
        val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("H:mm[:ss]")
        const val SECONDS_PER_MINUTE = 60
    }
}

private val ZONE_SUFFIX = Regex("""(Z|[+-]\d{2}:?\d{2})$""")

/** An ISO date and time; a zone suffix is dropped, since the computer's wall clock is what the offset corrects. */
internal fun parseLogDateTime(text: String): LocalDateTime = try {
    LocalDateTime.parse(text.replace(ZONE_SUFFIX, ""))
} catch (e: DateTimeParseException) {
    throw DiveLogFormatException("date and time '$text': ${e.message}")
}

internal fun number(text: String): Double = text.toDoubleOrNull() ?: throw DiveLogFormatException("number '$text'")

/** "5.2 m" with [unit] "m"; any other unit is refused rather than guessed. */
internal fun measure(text: String, unit: String): Double {
    val parts = text.trim().split(' ')
    if (parts.size != 2 || parts[1] != unit) throw DiveLogFormatException("expected '<number> $unit', got '$text'")
    return number(parts[0])
}
