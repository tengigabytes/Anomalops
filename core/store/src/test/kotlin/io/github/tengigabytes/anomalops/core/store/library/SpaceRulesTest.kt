// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.core.store.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** FR-66, FR-70, FR-73 (docs/product/requirements/05-6-storage.md). */
class SpaceRulesTest {
    private val day = MS_PER_DAY
    private val now = 100 * day

    private fun file(id: Long, kind: FileKind, ageDays: Long, bytes: Long = 10) =
        LibraryFile(id, stem = "s$id", kind = kind, bytes = bytes, capturedAtMs = now - ageDays * day)

    @Test
    fun fr66_offersUnstarredUneditedRawsFromThirtyDays() {
        val files = listOf(
            file(1, FileKind.DNG, 30, bytes = 25),
            file(2, FileKind.DNG, 29),
            file(3, FileKind.DNG, 40).copy(starred = true),
            file(4, FileKind.DNG, 40).copy(edited = true),
            file(5, FileKind.PHOTO, 40),
            file(6, FileKind.DNG, 40, bytes = 26).copy(trashedAtMs = now),
            file(7, FileKind.DNG, 31, bytes = 30),
        )
        val proposal = Reclaim.staleRaws(files, now)
        assertEquals(listOf(1L, 7L), proposal.files.map { it.id })
        assertEquals(55L, proposal.bytes)
    }

    @Test
    fun fr66_finalizableRawsHaveTheirPhoto() {
        val edited = file(1, FileKind.DNG, 1).copy(edited = true)
        val orphan = file(2, FileKind.DNG, 1).copy(edited = true)
        val files = listOf(edited, edited.copy(id = 9, kind = FileKind.PHOTO), orphan, file(3, FileKind.DNG, 1))
        assertEquals(listOf(1L), Reclaim.finalizable(files).files.map { it.id })
    }

    @Test
    fun fr73_binEmptiesAfterSevenDays() {
        val files = listOf(
            file(1, FileKind.PHOTO, 20).copy(trashedAtMs = now - 7 * day),
            file(2, FileKind.PHOTO, 20).copy(trashedAtMs = now - 7 * day + 1),
            file(3, FileKind.PHOTO, 20),
        )
        assertEquals(listOf(1L), RecycleBin.expired(files, now).files.map { it.id })
        assertFalse(RecycleBin.restorable(files[0], now))
        assertTrue(RecycleBin.restorable(files[1], now))
        assertFalse(RecycleBin.restorable(files[2], now))
    }

    @Test
    fun fr73_freesSpaceFromTheBinOnlyOldestFirst() {
        val files = listOf(
            file(1, FileKind.PHOTO, 5, bytes = 100),
            file(2, FileKind.DNG, 5, bytes = 30).copy(trashedAtMs = now - 1 * day),
            file(3, FileKind.PHOTO, 5, bytes = 20).copy(trashedAtMs = now - 3 * day),
            file(4, FileKind.PHOTO, 5, bytes = 20).copy(trashedAtMs = now - 2 * day),
        )
        assertEquals(listOf(3L, 4L), RecycleBin.toFree(files, 40).files.map { it.id })
        val all = RecycleBin.toFree(files, 1_000)
        assertEquals(listOf(3L, 4L, 2L), all.files.map { it.id })
        assertEquals(70L, all.bytes)
        assertTrue(RecycleBin.toFree(files, 0).files.isEmpty())
    }

    @Test
    fun fr70_keepsTheBestNAndTrashesTheRest() {
        val members = listOf(0.2, 0.9, null, 0.9, 0.5).mapIndexed { i, score ->
            StackMember(file(i.toLong(), FileKind.PHOTO, 1).copy(capturedAtMs = i.toLong()), score)
        }
        val one = KeepBest.split(members)
        assertEquals(listOf(1L), one.keep.map { it.id })
        assertEquals(listOf(3L, 4L, 0L, 2L), one.trash.map { it.id })
        assertEquals(listOf(1L, 3L, 4L, 0L, 2L), KeepBest.split(members, 5).keep.map { it.id })
    }

    @Test
    fun fr70_starredFramesStayOnTopOfN() {
        val members = listOf(
            StackMember(file(1, FileKind.PHOTO, 1).copy(starred = true), 0.1),
            StackMember(file(2, FileKind.PHOTO, 1), 0.8),
            StackMember(file(3, FileKind.PHOTO, 1), 0.3),
        )
        val split = KeepBest.split(members, 1)
        assertEquals(listOf(1L, 2L), split.keep.map { it.id })
        assertEquals(listOf(3L), split.trash.map { it.id })
    }

    @Test
    fun fr70_keepIsOneToFive() {
        assertThrows(IllegalArgumentException::class.java) { KeepBest.split(emptyList(), 0) }
        assertThrows(IllegalArgumentException::class.java) { KeepBest.split(emptyList(), 6) }
    }
}
