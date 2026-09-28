// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.capture

import android.os.SystemClock
import io.github.tengigabytes.anomalops.core.camera.request.RequestSpec
import io.github.tengigabytes.anomalops.core.camera.session.BurstFrame
import io.github.tengigabytes.anomalops.core.camera.session.CameraController
import io.github.tengigabytes.anomalops.core.camera.session.StillCapture
import io.github.tengigabytes.anomalops.core.store.media.SavedStill
import io.github.tengigabytes.anomalops.core.store.media.StillStore
import io.github.tengigabytes.anomalops.core.store.raw.RawKeeper
import io.github.tengigabytes.anomalops.core.store.stack.BurstStacks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Shutter to storage: the still goes to MediaStore (ADR-0004), its RAW frame to the FR-62 buffer (ADR-0005). */
class ShotPipeline(
    private val controller: CameraController,
    private val store: StillStore,
    private val keeper: RawKeeper,
    private val stacks: BurstStacks,
) {
    class Shot(val capture: StillCapture, val saved: SavedStill)

    suspend fun shoot(): Shot {
        val capture = controller.capture()
        var handedOver = false
        try {
            val saved = store.save(capture)
            capture.raw?.let { keeper.offer(saved, it) }
            handedOver = true
            return Shot(capture, saved)
        } finally {
            // A still that could not be saved has no stem for its DNG; give the camera buffer back.
            if (!handedOver) capture.raw?.close()
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
    }
}
