// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.core.store.media

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Where and under which names stills are stored. A DNG kept later shares the still's stem (ADR-0005). */
object StillNames {
    /** ADR-0004. */
    const val RELATIVE_PATH = "Pictures/Anomalops"

    /** JPEG_R (Ultra HDR) is a regular JPEG file with an embedded gain map, so both formats share the type. */
    const val MIME_TYPE = "image/jpeg"

    private const val PREFIX = "ANM_"
    private val STAMP = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS", Locale.ROOT)

    /** Local capture time to the millisecond, e.g. `ANM_20260928_170241_051`; sorts by time. */
    fun stem(takenAt: ZonedDateTime): String = PREFIX + STAMP.format(takenAt)

    fun stillName(stem: String): String = "$stem.jpg"
}
