// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.probe

import android.hardware.camera2.params.StreamConfigurationMap
import android.util.Size
import org.json.JSONObject

/** Output formats and sizes of a stream configuration map (ADR-0004 JPEG_R, ADR-0005 RAW size, FR-15 burst rate). */
internal object StreamProbe {
    fun describe(map: StreamConfigurationMap?): Any {
        map ?: return JSONObject.NULL
        val formats = JSONObject()
        map.outputFormats.forEach { format ->
            val sizes = map.getOutputSizes(format)?.toList().orEmpty()
            val highRes = map.getHighResolutionOutputSizes(format)?.toList().orEmpty()
            val largest = (sizes + highRes).maxByOrNull { it.area() }
            formats.put(ConstantNames.format(format), jsonOf(
                "code" to format,
                "max" to largest?.toString(),
                "maxMinFrameDurationNs" to largest?.let { safeDuration { map.getOutputMinFrameDuration(format, it) } },
                "maxStallDurationNs" to largest?.let { safeDuration { map.getOutputStallDuration(format, it) } },
                "sizes" to sizes.map { it.toString() }.toJsonArray(),
                "highResolutionSizes" to highRes.map { it.toString() }.toJsonArray(),
            ))
        }
        return formats
    }

    private fun safeDuration(block: () -> Long): Any = runCatching(block).getOrElse { it.javaClass.simpleName }
}

internal fun Size.area(): Long = width.toLong() * height
