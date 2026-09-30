// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.core.store.library

enum class FileKind {
    /** The finished photo (JPEG_R in v1.0, HEIC from FR-61). */
    PHOTO,

    /** A kept RAW; it shares the photo's file stem (ADR-0005). */
    DNG,

    VIDEO,
}

/**
 * One file of the app's library as the space rules see it (FR-66, FR-70, FR-73). Times are wall-clock
 * milliseconds; [trashedAtMs] is set while the file is in the app's recycle bin.
 */
data class LibraryFile(
    val id: Long,
    val stem: String,
    val kind: FileKind,
    val bytes: Long,
    val capturedAtMs: Long,
    val starred: Boolean = false,
    /** FR-65: develop settings saved in the DNG. */
    val edited: Boolean = false,
    val trashedAtMs: Long? = null,
) {
    val inTrash: Boolean get() = trashedAtMs != null
}

/** Files a rule proposes, with the space they would free. */
data class Proposal(val files: List<LibraryFile>) {
    val bytes: Long = files.sumOf { it.bytes }
}

internal const val MS_PER_DAY = 24L * 60 * 60 * 1000
