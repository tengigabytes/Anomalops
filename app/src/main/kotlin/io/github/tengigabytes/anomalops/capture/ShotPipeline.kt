// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.capture

import android.os.SystemClock
import android.util.Log
import io.github.tengigabytes.anomalops.core.camera.request.RequestSpec
import io.github.tengigabytes.anomalops.core.camera.session.BurstFrame
import io.github.tengigabytes.anomalops.core.camera.session.CameraController
import io.github.tengigabytes.anomalops.core.camera.session.MultiFrameCapture
import io.github.tengigabytes.anomalops.core.camera.session.StillCapture
import io.github.tengigabytes.anomalops.core.store.media.SavedStill
import io.github.tengigabytes.anomalops.core.store.media.StillStore
import io.github.tengigabytes.anomalops.core.store.raw.RawKeeper
import io.github.tengigabytes.anomalops.core.store.stack.BurstStacks
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Shutter to storage: the still goes to MediaStore (ADR-0004), its RAW frame to the FR-62 buffer (ADR-0005).
 *
 * FR-17, by the dive screen's merge switch ([multiFrame], off at first): with [lowLight] given and the switch on,
 * the still of any mode is followed by [EXTRA_FRAMES] RAW frames. The camera's own still is stored at once and
 * [shoot] returns; the merge runs in [background] and its picture then takes the still's place (maintainer,
 * 2026-10-03). When the merge fails or takes too long the camera's still stays, so a shot always leaves a picture.
 * One merge runs at a time: a shot taken while one is running is the camera's single still, so that only one
 * burst of RAW frames (about 100 MB) is ever waiting.
 */
class ShotPipeline(
    private val controller: CameraController,
    private val store: StillStore,
    private val keeper: RawKeeper,
    private val stacks: BurstStacks,
    private val lowLight: LowLightRenderer? = null,
    private val background: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val multiFrame: () -> Boolean = { false },
) {
    private val merging = AtomicBoolean(false)

    /**
     * How FR-17 went for one shot. [gain] is the exposure gain the merged
     * picture was rendered with (`AutoLook`), 0 when it fell back to the camera's still. [keptWidth] is the share of
     * the RAW frame's width left after cropping to the camera's field of view (1 without a crop), and
     * [cropMatched] whether that crop was found on this shot.
     */
    class Merge(
        val merged: Boolean,
        val frames: Int,
        val captureMs: Long,
        val mergeMs: Long,
        val encodeMs: Long,
        val gain: Float,
        val keptWidth: Float = 1f,
        val cropMatched: Boolean = false,
    )

    /** What the background merge of one shot left: the file now stored for it, and how the merge went. */
    class Merged(val saved: SavedStill, val merge: Merge)

    /**
     * One shot as stored when [shoot] returns: the camera's still. [merging] is the FR-17 merge still running for
     * it, null when none was started (switch off, or another shot's merge was running).
     */
    class Shot(val capture: StillCapture, val saved: SavedStill, val merging: Deferred<Merged>? = null)

    suspend fun shoot(): Shot {
        val renderer = lowLight?.takeIf { multiFrame() && merging.compareAndSet(false, true) }
        var started = false
        try {
            val began = SystemClock.elapsedRealtime()
            val multi = renderer?.let { controller.captureMultiFrame(EXTRA_FRAMES) }
            val capture = multi?.still ?: controller.capture()
            val captureMs = SystemClock.elapsedRealtime() - began
            val saved = stored(capture)
            if (renderer == null || multi == null) return Shot(capture, saved)
            val job = background.async { finished(renderer, multi, saved, captureMs) }
            started = true
            job.invokeOnCompletion { merging.set(false) }
            return Shot(capture, saved, job)
        } finally {
            if (renderer != null && !started) merging.set(false)
        }
    }

    /** The camera's still in MediaStore and its RAW frame in the FR-62 buffer. */
    private suspend fun stored(capture: StillCapture): SavedStill {
        var handedOver = false
        try {
            val saved = store.save(capture)
            capture.raw?.let { keeper.offer(saved, it) }
            handedOver = true
            return saved
        } finally {
            // A still that could not be saved has no stem for its DNG; give the camera buffer back.
            if (!handedOver) capture.raw?.close()
        }
    }

    /** The merge of [multi] put in the place of [saved]; [saved] itself when it could not be. */
    private suspend fun finished(
        renderer: LowLightRenderer,
        multi: MultiFrameCapture,
        saved: SavedStill,
        captureMs: Long,
    ): Merged {
        val rendered = merged(renderer, multi)
        val replaced = try {
            rendered?.let { store.replace(saved, it.jpeg) }
        } catch (e: IOException) {
            Log.w(TAG, "FR-17 merged picture not stored; the camera's still stays", e)
            null
        }
        return Merged(replaced ?: saved, merge(multi, rendered.takeIf { replaced != null }, captureMs))
    }

    private fun merge(multi: MultiFrameCapture, rendered: LowLightRenderer.Rendered?, captureMs: Long): Merge {
        if (rendered == null) return Merge(false, multi.frames.size, captureMs, 0, 0, 0f)
        val kept = rendered.crop?.let { it.right - it.left } ?: 1f
        return Merge(
            merged = true,
            frames = multi.frames.size,
            captureMs = captureMs,
            mergeMs = rendered.mergeMs,
            encodeMs = rendered.matchMs + rendered.encodeMs,
            gain = rendered.picture.options.exposure,
            keptWidth = kept,
            cropMatched = rendered.cropMatched,
        )
    }

    /** The merged JPEG, or null when the burst cannot be merged, the merge fails or it overruns. */
    @Suppress("TooGenericExceptionCaught") // Whatever goes wrong on the GPU, the camera's still is stored instead.
    private suspend fun merged(renderer: LowLightRenderer, multi: MultiFrameCapture): LowLightRenderer.Rendered? {
        val burst = LowLightFrames.burst(multi) ?: return null
        val rotation = multi.still.raw?.sensorOrientation ?: 0
        return try {
            val still = multi.still
            val rendered = withTimeoutOrNull(MERGE_TIMEOUT_MS) {
                renderer.render(burst, rotation, still.bytes, still.spec.physicalId)
            }
            if (rendered == null) Log.w(TAG, "FR-17 merge overran $MERGE_TIMEOUT_MS ms; the camera's still stays")
            rendered
        } catch (e: CancellationException) {
            throw e
        } catch (e: RuntimeException) {
            Log.w(TAG, "FR-17 merge failed; the camera's still stays", e)
            null
        }
    }

    /**
     * The result of one burst: frames delivered by the camera, frames saved, the largest backlog of frames waiting
     * to be written, the sensor-timestamp gaps (FR-15), and the time from release to the first preview frame of
     * the restored single session. [stem] is null when no frame arrived.
     */
    class Burst(
        val stem: String?,
        val frames: Int,
        val saved: List<SavedStill>,
        val maxBacklog: Int,
        val gapsMs: List<Double>,
        val resumeMs: Double?,
    )

    /**
     * FR-15, FR-68: bursts until [until] returns. Frames go through a queue to [WRITERS] parallel MediaStore
     * writers, because one writer (about 60 ms per JPEG, docs/test/m1-instrumented.md) is slower than the burst.
     */
    suspend fun burst(until: suspend () -> Unit): Burst = coroutineScope {
        val stem = AtomicReference<String>()
        val firstSpec = AtomicReference<RequestSpec>()
        val queue = Channel<BurstFrame>(Channel.UNLIMITED)
        val backlog = AtomicInteger()
        val maxBacklog = AtomicInteger()
        val timestamps = Collections.synchronizedList(mutableListOf<Long>())
        val saved = Collections.synchronizedList(mutableListOf<SavedStill>())
        var releasedAtNs = Long.MAX_VALUE
        val writers = List(WRITERS) {
            launch(Dispatchers.IO) {
                for (frame in queue) {
                    saved += store.saveBurstFrame(stem.get(), frame)
                    backlog.decrementAndGet()
                }
            }
        }
        val frames = try {
            controller.burst(BURST_FPS, onFrame = { frame ->
                // Frame 0 arrives first, on the camera thread, before any writer can take a frame.
                if (frame.index == 0) {
                    stem.set(store.burstStem(frame))
                    firstSpec.set(frame.spec)
                }
                timestamps += frame.sensorTimestampNs
                maxBacklog.accumulateAndGet(backlog.incrementAndGet(), ::maxOf)
                queue.trySend(frame)
            }, until = {
                until()
                releasedAtNs = SystemClock.elapsedRealtimeNanos()
            })
        } finally {
            queue.close()
        }
        val resumed = withTimeoutOrNull(RESUME_TIMEOUT_MS) {
            controller.previewFrames.first { it.arrivedAtNs > releasedAtNs }
        }
        writers.joinAll()
        val ordered = saved.sortedBy { it.displayName }
        firstSpec.get()?.let { stacks.record(stem.get(), ordered, it.physicalId, it.preset.name) }
        val gaps = timestamps.zipWithNext { a, b -> (b - a).toDouble() / NS_PER_MS }
        val resumeMs = resumed?.let { (it.arrivedAtNs - releasedAtNs).toDouble() / NS_PER_MS }
        Burst(stem.get(), frames, ordered, maxBacklog.get(), gaps, resumeMs)
    }

    /** FR-62: writes the DNG of [stem] if its RAW frame is still held; null when it is gone. */
    suspend fun keepRaw(stem: String): SavedStill? = keeper.keep(stem)

    private companion object {
        /** FR-15 asks for at least 10 fps; the camera can do 30 (docs/test/m2-stream-combos.md). */
        const val BURST_FPS = 30
        const val WRITERS = 3
        const val NS_PER_MS = 1_000_000L
        const val RESUME_TIMEOUT_MS = 3_000L
        const val TAG = "ShotPipeline"

        /** FR-17 asks for 4 to 6 frames; with the still's own frame this makes 5. */
        const val EXTRA_FRAMES = 4

        /** FR-17's limit is 3 s; beyond this the shot is not worth waiting for. Proposed. */
        const val MERGE_TIMEOUT_MS = 10_000L
    }
}
