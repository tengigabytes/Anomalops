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

    const val DNG_MIME_TYPE = "image/x-adobe-dng"

    private const val PREFIX = "ANM_"
    private val STAMP = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS", Locale.ROOT)

    /** Local capture time to the millisecond, e.g. `ANM_20260928_170241_051`; sorts by time. */
    fun stem(takenAt: ZonedDateTime): String = PREFIX + STAMP.format(takenAt)

    fun stillName(stem: String): String = "$stem.jpg"

    /** FR-17: the merged picture while it takes the place of its still, e.g. `ANM_20260928_170241_051_M.jpg`. */
    fun mergedName(stem: String): String = "${stem}_M.jpg"

    /** FR-68: burst frames share the burst's stem and count from 1, e.g. `ANM_20260928_170241_051_B001.jpg`. */
    fun burstName(stem: String, index: Int): String = "%s_B%03d.jpg".format(Locale.ROOT, stem, index + 1)

    /** ADR-0005: the DNG sits next to its still with the same stem. */
    fun dngName(stem: String): String = "$stem.dng"
}
