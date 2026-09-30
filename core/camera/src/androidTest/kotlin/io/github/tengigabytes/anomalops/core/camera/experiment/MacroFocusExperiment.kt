// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.core.camera.experiment

import android.Manifest
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureResult
import android.media.Image
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
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
import kotlin.math.abs

/**
 * Macro focus stacking, land checks on the device (docs/test/macro-stacking-test-plan.md):
 * - T1: can the tele lenses (4, 6) stream behind logical camera 0 and follow a hand-set focus distance?
 * - T9: how long does a focus bracket take when every frame of a `captureBurst` asks for another distance?
 *
 * Name the scene with `-e scene <name>` and the target distance with `-e targetCm <cm>`. Logs under [TAG]; RAW
 * frames are only measured in memory.
 */
@RunWith(AndroidJUnit4::class)
class MacroFocusExperiment {
    @get:Rule
    val cameraPermission: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.CAMERA)

    private val args = InstrumentationRegistry.getArguments()
    private val scene = "scene=${args.getString("scene", "unnamed")} targetCm=${args.getString("targetCm", "-")}"
    private val manager = InstrumentationRegistry.getInstrumentation().targetContext
        .getSystemService(CameraManager::class.java)
    private val thread = HandlerThread("macro-experiment").apply { start() }
    private val handler = Handler(thread.looper)
    private val rig = RawRig(manager, handler)

    @After
    fun tearDown() {
        rig.close()
        thread.quitSafely()
    }

    /**
     * No tape measure: the target distance as each lens's AF finds it. The focus distances are APPROXIMATE on the
     * Pixel 10 Pro, so this is a rough estimate, not T3.
     */
    @Test
    fun t0_targetDistanceByAf() = runBlocking<Unit>(handler.asCoroutineDispatcher()) {
        AF_LENSES.forEach { id ->
            runCatching {
                rig.withLens(id, 1) { session ->
                    val frames = mutableListOf<RawRig.Frame>()
                    session.capture.setRepeatingRequest(session.autoFocus(trigger = false), null, handler)
                    delay(SETTLE_MS)
                    repeat(AF_TRIES) {
                        frames.clear()
                        session.capture.capture(
                            session.autoFocus(trigger = true),
                            session.frames { frames += it },
                            handler,
                        )
                        session.capture.setRepeatingRequest(
                            session.autoFocus(trigger = false),
                            session.frames { frames += it },
                            handler,
                        )
                        delay(AF_WATCH_MS)
                        val last = frames.lastOrNull()
                        Log.i(
                            TAG,
                            "T0 $scene lens=$id try=${it + 1} af=${last?.afState} focus=%s D (%s cm)".format(
                                last?.diopters?.let { d -> "%.3f".format(d) },
                                last?.diopters?.takeIf { d -> d > 0f }?.let { d -> "%.1f".format(CM_PER_M / d) },
                            ),
                        )
                    }
                }
            }.onFailure { Log.e(TAG, "T0 lens=$id FAIL ${it.javaClass.simpleName}: ${it.message}", it) }
        }
    }

    @Test
    fun t1_teleFollowsHandSetFocus() = runBlocking<Unit>(handler.asCoroutineDispatcher()) {
        TELE.forEach { id ->
            val lens = rig.lens(id)
            Log.i(
                TAG,
                "T1 $scene lens=$id min=${lens.minDiopters} D hyperfocal=${lens.hyperfocalDiopters} D " +
                    "calibration=${lens.calibration}",
            )
            val steps = List(T1_STEPS) { lens.minDiopters * it / (T1_STEPS - 1) }
            runCatching { rig.withLens(id, T1_RAW_IMAGES) { sweep("T1", it, lens, steps) } }
                .onFailure { Log.e(TAG, "T1 lens=$id FAIL ${it.javaClass.simpleName}: ${it.message}", it) }
        }
    }

    /**
     * T3 at one point: with the target at `-e targetCm`, fine steps around its distance; the sharpest step against
     * the measured distance shows how far off each lens's APPROXIMATE diopters are there.
     */
    @Test
    fun t3_fineSweepAroundTarget() = runBlocking<Unit>(handler.asCoroutineDispatcher()) {
        val targetCm = requireNotNull(args.getString("targetCm")?.toFloatOrNull()) { "needs -e targetCm <cm>" }
        val center = CM_PER_M / targetCm
        FINE_LENSES.forEach { id ->
            val lens = rig.lens(id)
            val step = minOf(FINE_MAX_STEP, lens.hyperfocalDiopters)
            val count = (2 * FINE_HALF_SPAN / step).toInt() + 1
            val steps = List(count) { center - FINE_HALF_SPAN + it * step }.filter { it in 0f..lens.minDiopters }
            Log.i(TAG, "T3 $scene lens=$id target=%.3f D step=%.4f D steps=${steps.size}".format(center, step))
            runCatching { rig.withLens(id, T1_RAW_IMAGES) { sweep("T3", it, lens, steps) } }
                .onFailure { Log.e(TAG, "T3 lens=$id FAIL ${it.javaClass.simpleName}: ${it.message}", it) }
        }
    }

    @Test
    fun t9_focusBracketTiming() = runBlocking<Unit>(handler.asCoroutineDispatcher()) {
        BRACKET_LENSES.forEach { id ->
            val lens = rig.lens(id)
            val step = BRACKET_OVERLAP * 2 * lens.hyperfocalDiopters
            val distances = List(BRACKET_FRAMES) { (lens.minDiopters - it * step).coerceAtLeast(0f) }
            Log.i(TAG, "T9 $scene lens=$id step=%.4f D distances=%s".format(step, distances.fmt()))
            runCatching {
                rig.withLens(id, BRACKET_FRAMES + 1) { session ->
                    repeat(BRACKET_RUNS) { run ->
                        Log.i(TAG, "T9 lens=$id run=${run + 1} ${bracket(session, distances)}")
                    }
                }
            }.onFailure { Log.e(TAG, "T9 lens=$id FAIL ${it.javaClass.simpleName}: ${it.message}", it) }
        }
    }

    /** Per step of [steps]: the settled report and one RAW's sharpness; then the worst error and the sharpest step. */
    private suspend fun sweep(label: String, session: RawRig.Session, lens: RawRig.Lens, steps: List<Float>) {
        var worst = 0f
        var peak = 0f to -1.0
        // Let AE settle once, then hold it: a gain change moves the noise floor of the sharpness score (T3, 39.5 cm).
        session.capture.setRepeatingRequest(session.request(steps.first(), withRaw = false), null, handler)
        delay(AE_SETTLE_MS)
        steps.forEachIndexed { i, wanted ->
            val frames = mutableListOf<RawRig.Frame>()
            session.capture.setRepeatingRequest(
                session.request(wanted, withRaw = false, aeLock = true),
                session.frames { frames += it },
                handler,
            )
            delay(SETTLE_MS)
            val settled = frames.takeLast(SETTLED_FRAMES)
            val reported = settled.mapNotNull { it.diopters }.sorted().let { it.getOrNull(it.size / 2) }
            val sharp = sharpness(session, wanted)
            reported?.let { worst = maxOf(worst, abs(it - wanted)) }
            if (sharp > peak.second) peak = wanted to sharp
            Log.i(
                TAG,
                "$label lens=${lens.id} step=$i req=%.3f rep=%s state=%s active=%s sharp=%.1f".format(
                    wanted,
                    reported?.let { "%.3f".format(it) } ?: "null",
                    settled.lastOrNull()?.lensState.stateName(),
                    settled.lastOrNull()?.activeId,
                    sharp,
                ),
            )
        }
        Log.i(TAG, "$label lens=${lens.id} summary maxError=%.3f D sharpestAt=%.3f D".format(worst, peak.first))
    }

    private suspend fun sharpness(session: RawRig.Session, diopters: Float): Double {
        val image = CompletableDeferred<Image>()
        session.raw.setOnImageAvailableListener({ it.acquireNextImage()?.let(image::complete) }, handler)
        session.capture.capture(session.request(diopters, withRaw = true, aeLock = true), null, handler)
        return withTimeout(IMAGE_TIMEOUT_MS) { image.await() }.use { RawRig.sharpness(it) }
    }

    /** T9: one burst over [distances]; returns the time span, per-frame reports and the lens state. */
    private suspend fun bracket(session: RawRig.Session, distances: List<Float>): String {
        session.capture.setRepeatingRequest(session.request(distances.first(), withRaw = false), null, handler)
        delay(SETTLE_MS)
        val frames = mutableListOf<RawRig.Frame>()
        val done = CompletableDeferred<Unit>()
        session.raw.setOnImageAvailableListener({ it.acquireNextImage()?.close() }, handler)
        val callback = session.frames {
            frames += it
            if (frames.size == distances.size) done.complete(Unit)
        }
        val startNs = SystemClock.elapsedRealtimeNanos()
        session.capture.stopRepeating()
        session.capture.captureBurst(distances.map { session.request(it, withRaw = true) }, callback, handler)
        withTimeout(BURST_TIMEOUT_MS) { done.await() }
        val appMs = (SystemClock.elapsedRealtimeNanos() - startNs) / NS_PER_MS
        val stamps = frames.mapNotNull { it.sensorNs }
        val spanMs = if (stamps.size > 1) (stamps.max() - stamps.min()) / NS_PER_MS else Double.NaN
        val moving = frames.count { it.lensState == CaptureResult.LENS_STATE_MOVING }
        val reports = frames.joinToString(" ") { "%.3f/%s".format(it.diopters, it.lensState.stateName()) }
        return "frames=${frames.size} sensorSpanMs=%.0f appMs=%.0f moving=$moving | %s".format(spanMs, appMs, reports)
    }

    private fun Int?.stateName() = when (this) {
        CaptureResult.LENS_STATE_MOVING -> "MOV"
        null -> "null"
        else -> "STA"
    }

    private fun List<Float>.fmt() = joinToString(",") { "%.3f".format(it) }

    private companion object {
        const val T1_STEPS = 10
        const val T1_RAW_IMAGES = 2
        const val SETTLE_MS = 700L
        const val AE_SETTLE_MS = 1_500L
        const val SETTLED_FRAMES = 5
        const val IMAGE_TIMEOUT_MS = 3_000L
        const val BURST_TIMEOUT_MS = 5_000L
        const val BRACKET_FRAMES = 6
        const val BRACKET_RUNS = 3

        // ADR-0014: step = overlap x depth of field, and depth of field ~ 2 x hyperfocal diopters (proposed 0.7).
        const val BRACKET_OVERLAP = 0.7f
        const val NS_PER_MS = 1e6

        const val FINE_HALF_SPAN = 0.5f
        const val FINE_MAX_STEP = 0.05f
        const val AF_TRIES = 3
        const val AF_WATCH_MS = 2_500L
        const val CM_PER_M = 100f

        // Main and tele: their AF ranges both cover a target a little beyond the tele's closest focus.
        val AF_LENSES = listOf("2", "4")
        val TELE = listOf("4", "6")
        val FINE_LENSES = listOf("2", "4", "6")

        // Tele, tele 2x crop, ultra-wide, ultra-wide 2x crop (the v1.0 macro lens), main.
        val BRACKET_LENSES = listOf("4", "6", "3", "9", "2")
    }
}
