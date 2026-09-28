// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.capture

import io.github.tengigabytes.anomalops.core.camera.session.CameraController
import io.github.tengigabytes.anomalops.core.camera.session.StillCapture
import io.github.tengigabytes.anomalops.core.store.media.SavedStill
import io.github.tengigabytes.anomalops.core.store.media.StillStore
import io.github.tengigabytes.anomalops.core.store.raw.RawKeeper

/** Shutter to storage: the still goes to MediaStore (ADR-0004), its RAW frame to the FR-62 buffer (ADR-0005). */
class ShotPipeline(
    private val controller: CameraController,
    private val store: StillStore,
    private val keeper: RawKeeper,
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

    /** FR-62: writes the DNG of [stem] if its RAW frame is still held; null when it is gone. */
    suspend fun keepRaw(stem: String): SavedStill? = keeper.keep(stem)
}
