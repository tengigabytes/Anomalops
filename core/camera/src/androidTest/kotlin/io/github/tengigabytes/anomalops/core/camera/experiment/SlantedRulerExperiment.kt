// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.core.camera.experiment

import android.Manifest
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureResult
import android.media.Image
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import io.github.tengigabytes.anomalops.core.camera.experiment.RawRig.Companion.TAG
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A ruler slanting away from the camera, its middle at `-e targetCm` (docs/test/macro-stacking-test-plan.md,
 * section 4). Each RAW frame is cut into [BANDS] horizontal bands; the sharpest band shows which part of the
 * ruler, so which distance, is in focus.
 *
 * - T7 / T10: hold each focus distance (target ±0.5 D) and log which bands are sharp (depth of field) and, between
 *   the nearest and farthest step, the scale of the row profile (focus breathing).
 * - T9 recheck: a 6-frame bracket around the target, sent one distance per frame, then each distance held for 2 and
 *   3 frames; for every kept frame, the sharp band, to compare with the held mapping.
 *
 * `-e lenses 4,6` picks the lenses (default main and both tele). Logs under [TAG]; nothing is saved;
 * `scripts/ruler_report.py` turns the log into tables.
 */
@RunWith(AndroidJUnit4::class)
class SlantedRulerExperiment {
    @get:Rule
    val cameraPermission: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.CAMERA)

    private val args = InstrumentationRegistry.getArguments()
    private val targetCm = requireNotNull(args.getString("targetCm")?.toFloatOrNull()) { "needs -e targetCm <cm>" }
    private val lenses = args.getString("lenses")?.split(',')?.map { it.trim() } ?: listOf("2", "4", "6")
    private val manager = InstrumentationRegistry.getInstrumentation().targetContext
        .getSystemService(CameraManager::class.java)
    private val thread = HandlerThread("ruler-experiment").apply { start() }
    private val handler = Handler(thread.looper)
    private val rig = RawRig(manager, handler)

    @After
    fun tearDown() {
        rig.close()
        thread.quitSafely()
    }

    @Test
    fun t7_t10_heldFocus() = runBlocking<Unit>(handler.asCoroutineDispatcher()) {
        lenses.forEach { id ->
            val lens = rig.lens(id)
            val centre = CM_PER_M / targetCm
            val step = minOf(MAX_STEP, lens.hyperfocalDiopters)
            val steps = generateSequence(centre - HALF_SPAN) { it + step }.takeWhile { it <= centre + HALF_SPAN }
                .filter { it in 0f..lens.minDiopters }.toList()
            Log.i(
                TAG,
                "T7 targetCm=$targetCm lens=$id step=%.4f hyperfocal=%.4f steps=${steps.size}".format(
                    step,
                    lens.hyperfocalDiopters,
                ),
            )
            if (steps.isEmpty()) return@forEach
            runCatching {
                rig.withLens(id, RAW_IMAGES) { session ->
                    lockExposure(session, steps.first())
                    var nearRows: FloatArray? = null
                    var farRows: FloatArray? = null
                    steps.forEachIndexed { i, d ->
                        session.capture.setRepeatingRequest(
                            session.request(d, withRaw = false, aeLock = true),
                            null,
                            handler,
                        )
                        delay(SETTLE_MS)
                        val (bands, rows) = still(session, d) { RulerProfile.bands(it, BANDS) to RulerProfile.rows(it) }
                        if (i == 0) farRows = rows
                        if (i == steps.lastIndex) nearRows = rows
                        val (best, lo, hi) = RulerProfile.peak(bands)
                        Log.i(TAG, "T7 lens=$id req=%.3f peak=$best sharp=$lo..$hi bands=%s".format(d, bands.fmt()))
                    }
                    val (scale, shift) = RulerProfile.scaleBetween(farRows!!, nearRows!!)
                    Log.i(
                        TAG,
                        "T10 lens=$id far %.3f D -> near %.3f D: scale %.4f shift %.1f rows".format(
                            steps.first(),
                            steps.last(),
                            scale,
                            shift,
                        ),
                    )
                }
            }.onFailure { Log.e(TAG, "T7 lens=$id FAIL ${it.javaClass.simpleName}: ${it.message}", it) }
        }
    }

    @Test
    fun t9_bracketModes() = runBlocking<Unit>(handler.asCoroutineDispatcher()) {
        lenses.forEach { id ->
            val lens = rig.lens(id)
            val centre = (CM_PER_M / targetCm).coerceAtMost(lens.minDiopters)
            val step = OVERLAP * 2 * lens.hyperfocalDiopters
            val distances = List(FRAMES) { centre + (FRAMES - 1) * step / 2 - it * step }
                .map { it.coerceIn(0f, lens.minDiopters) }
            Log.i(TAG, "T9b targetCm=$targetCm lens=$id distances=%s".format(distances.fmt()))
            runCatching {
                rig.withLens(id, BURST_RAW_IMAGES) { session ->
                    lockExposure(session, distances.first())
                    HOLDS.forEach { hold -> Log.i(TAG, "T9b lens=$id hold=$hold ${bracket(session, distances, hold)}") }
                }
            }.onFailure { Log.e(TAG, "T9b lens=$id FAIL ${it.javaClass.simpleName}: ${it.message}", it) }
        }
    }

    private suspend fun lockExposure(session: RawRig.Session, diopters: Float) {
        session.capture.setRepeatingRequest(session.request(diopters, withRaw = false), null, handler)
        delay(AE_SETTLE_MS)
    }

    /** One RAW at [diopters] (exposure held), measured by [measure] before the image is released. */
    private suspend fun <T> still(session: RawRig.Session, diopters: Float, measure: (Image) -> T): T {
        val result = CompletableDeferred<T>()
        session.raw.setOnImageAvailableListener({ r ->
            r.acquireNextImage()?.use { if (!result.isCompleted) result.complete(measure(it)) }
        }, handler)
        session.capture.capture(session.request(diopters, withRaw = true, aeLock = true), null, handler)
        return withTimeout(IMAGE_TIMEOUT_MS) { result.await() }
    }

    /** A burst with each distance repeated [hold] times; the last frame of each hold is measured. */
    private suspend fun bracket(session: RawRig.Session, distances: List<Float>, hold: Int): String {
        session.capture.setRepeatingRequest(
            session.request(distances.first(), withRaw = false, aeLock = true),
            null,
            handler,
        )
        delay(SETTLE_MS)
        val total = distances.size * hold
        val peaks = mutableListOf<Int>()
        val reports = mutableListOf<String>()
        val done = CompletableDeferred<Unit>()
        var arrived = 0
        session.raw.setOnImageAvailableListener({ r ->
            r.acquireNextImage()?.use {
                arrived++
                if (arrived % hold == 0) peaks += RulerProfile.peak(RulerProfile.bands(it, BANDS)).first
                if (arrived == total) done.complete(Unit)
            }
        }, handler)
        val callback = session.frames { f ->
            reports += "%.3f/%s".format(
                f.diopters,
                if (f.lensState == CaptureResult.LENS_STATE_MOVING) "MOV" else "STA",
            )
        }
        session.capture.stopRepeating()
        val requests = distances.flatMap { d -> List(hold) { session.request(d, withRaw = true, aeLock = true) } }
        session.capture.captureBurst(requests, callback, handler)
        withTimeout(BURST_TIMEOUT_MS) { done.await() }
        return "peaks=$peaks reports=${reports.joinToString(" ")}"
    }

    private fun List<Float>.fmt() = joinToString(",") { "%.3f".format(it) }

    private fun DoubleArray.fmt() = joinToString(",") { "%.0f".format(it) }

    private companion object {
        const val BANDS = 16
        const val FRAMES = 6
        val HOLDS = listOf(1, 2, 3)
        const val OVERLAP = 0.7f
        const val HALF_SPAN = 0.5f
        const val MAX_STEP = 0.05f
        const val RAW_IMAGES = 2

        // Each image is measured and released as it arrives; a few buffers in flight are enough.
        const val BURST_RAW_IMAGES = 8
        const val SETTLE_MS = 700L
        const val AE_SETTLE_MS = 1_500L
        const val IMAGE_TIMEOUT_MS = 3_000L
        const val BURST_TIMEOUT_MS = 8_000L
        const val CM_PER_M = 100f
    }
}
