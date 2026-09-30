// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.core.store.library

/**
 * FR-66: which DNGs to offer for deletion. The rules only propose; the diver confirms with one press, and the
 * files go to the recycle bin ([RecycleBin]), not straight to deletion.
 */
object Reclaim {
    const val STALE_DAYS = 30

    /** Unstarred, unedited DNGs at least [STALE_DAYS] old, with the space they hold. */
    fun staleRaws(files: List<LibraryFile>, nowMs: Long): Proposal = Proposal(
        files.filter {
            it.kind == FileKind.DNG && !it.inTrash && !it.starred && !it.edited &&
                nowMs - it.capturedAtMs >= STALE_DAYS * MS_PER_DAY
        },
    )

    /**
     * Edited DNGs that can be finalised: the photo with the same stem already carries the edit (FR-65), so the
     * DNG may go. The diver picks which of these to finalise.
     */
    fun finalizable(files: List<LibraryFile>): Proposal {
        val photos = files.filter { it.kind == FileKind.PHOTO && !it.inTrash }.mapTo(HashSet()) { it.stem }
        return Proposal(files.filter { it.kind == FileKind.DNG && it.edited && !it.inTrash && it.stem in photos })
    }
}

/** FR-73 and FR-70's undo window: the app's recycle bin. Every rule here looks at trashed files only. */
object RecycleBin {
    const val KEEP_DAYS = 7

    /** Trashed files whose [KEEP_DAYS] have passed; these are deleted for good. */
    fun expired(files: List<LibraryFile>, nowMs: Long): Proposal = Proposal(
        files.filter { file -> file.trashedAtMs?.let { nowMs - it >= KEEP_DAYS * MS_PER_DAY } == true },
    )

    /** FR-70: a trashed file can come back until it expires. */
    fun restorable(file: LibraryFile, nowMs: Long): Boolean =
        file.trashedAtMs?.let { nowMs - it < KEEP_DAYS * MS_PER_DAY } == true

    /**
     * FR-73: when space runs short, the oldest-trashed files go first until [neededBytes] are freed. Returns fewer
     * bytes than needed when the bin runs out; it never reaches outside the bin.
     */
    fun toFree(files: List<LibraryFile>, neededBytes: Long): Proposal {
        val picked = mutableListOf<LibraryFile>()
        var freed = 0L
        for (file in files.filter { it.inTrash }.sortedBy { it.trashedAtMs }) {
            if (freed >= neededBytes) break
            picked += file
            freed += file.bytes
        }
        return Proposal(picked)
    }
}

/** FR-70: a scored member of a burst stack; a higher [score] is better, null when it was not scored. */
data class StackMember(val file: LibraryFile, val score: Double?)

/** FR-70: keep the best N of a stack, send the rest to the recycle bin. */
object KeepBest {
    const val DEFAULT_KEEP = 1
    val KEEP_RANGE = 1..5

    data class Split(val keep: List<LibraryFile>, val trash: List<LibraryFile>)

    /** Ranks by score, unscored last, ties in capture order; starred frames are always kept on top of the N. */
    fun split(members: List<StackMember>, keep: Int = DEFAULT_KEEP): Split {
        require(keep in KEEP_RANGE) { "keep must be in $KEEP_RANGE, was $keep" }
        val (starred, others) = members.partition { it.file.starred }
        val ranked = others.sortedWith(
            compareBy<StackMember> { it.score == null }
                .thenByDescending { it.score ?: 0.0 }
                .thenBy { it.file.capturedAtMs },
        )
        val kept = ranked.take(keep).map { it.file }
        return Split(keep = starred.map { it.file } + kept, trash = ranked.drop(keep).map { it.file })
    }
}
