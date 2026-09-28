// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.acceptance

import android.Manifest
import android.graphics.BitmapFactory
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * FR-15 and FR-68 (docs/product/mvp-acceptance.md) at the pipeline level. The 400 ms hold threshold of the
 * shutter gesture is checked by hand (docs/test/m2-burst.md); here a 3 s hold is its 2.6 s burst part.
 */
@RunWith(AndroidJUnit4::class)
class BurstTest {
    @get:Rule
    val cameraPermission: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.CAMERA)

    private val rig = AppRig()

    @After
    fun tearDown() = rig.close()

    @Test
    fun fr15_threeSecondHoldGivesThirtyFramesOrMore() = runBlocking<Unit> {
        rig.start()
        val burst = rig.pipeline.burst(until = { delay(HOLD_MS - THRESHOLD_MS) })
        rig.track(burst.stem)
        val gaps = burst.gapsMs.sorted()
        val median = gaps[gaps.size / 2]
        Log.i(TAG, "FR-15 frames=${burst.frames} saved=${burst.saved.size} medianGap=%.1f ms".format(median))
        assertTrue("FR-15 ${burst.frames} frames < $MIN_FRAMES", burst.frames >= MIN_FRAMES)
        assertEquals(burst.frames, burst.saved.size)
        assertTrue("FR-15 median gap $median ms > $MAX_GAP_MS ms", median <= MAX_GAP_MS)
    }

    /** Ten bursts: ten stacks, no DNG, only plain JPEG, the first frame as cover. */
    @Test
    fun fr68_tenBurstsMakeTenStacks() = runBlocking<Unit> {
        rig.start()
        val stems = List(BURSTS) {
            requireNotNull(rig.pipeline.burst(until = { delay(SHORT_BURST_MS) }).stem).also(rig::track)
        }
        val stacks = rig.stacks.stacks().filter { it.stem in stems }
        val problems = stems.flatMap { stem -> stackProblems(stem, stacks.firstOrNull { it.stem == stem }) }
        Log.i(
            TAG,
            "FR-68 stacks=${stacks.size}/$BURSTS frames=${stacks.sumOf { it.frameCount }} problems=${problems.size}",
        )
        problems.forEach { Log.e(TAG, "FR-68 $it") }
        assertEquals(emptyList<String>(), problems)
    }

    private suspend fun stackProblems(
        stem: String,
        stack: io.github.tengigabytes.anomalops.core.store.stack.BurstStackEntity?,
    ) = buildList {
        if (stack == null) {
            add("$stem: no stack")
            return@buildList
        }
        val frames = rig.stacks.frames(stem)
        if (stack.coverName != "${stem}_B001.jpg" || frames.first().displayName != stack.coverName) {
            add("$stem: cover ${stack.coverName}")
        }
        val files = rig.files(stem)
        if (files.any { (_, name) -> name.endsWith(".dng") }) add("$stem: has a DNG")
        if (files.size != frames.size) add("$stem: ${files.size} files for ${frames.size} frames")
        files.forEach { (uri, name) ->
            val bytes = rig.read(uri)
            if (isUltraHdr(bytes)) add("$stem: $name is JPEG_R")
        }
        val cover = files.first { it.second == stack.coverName }.first
        val options = BitmapFactory.Options().apply { inSampleSize = DECODE_SAMPLE }
        val decoded = rig.read(cover).let { BitmapFactory.decodeByteArray(it, 0, it.size, options) }
        if (decoded == null || decoded.hasGainmap()) add("$stem: cover not a plain JPEG")
    }

    /** An Ultra HDR JPEG carries a second JPEG through an MPF index and `hdrgm` metadata. */
    private fun isUltraHdr(bytes: ByteArray): Boolean = String(
        bytes,
        Charsets.ISO_8859_1,
    ).let { "MPF" in it && "hdrgm" in it }

    private companion object {
        const val TAG = "M2Acceptance"
        const val HOLD_MS = 3_000L
        const val THRESHOLD_MS = 400L
        const val MIN_FRAMES = 30
        const val MAX_GAP_MS = 100.0
        const val BURSTS = 10
        const val SHORT_BURST_MS = 1_000L
        const val DECODE_SAMPLE = 8
    }
}
