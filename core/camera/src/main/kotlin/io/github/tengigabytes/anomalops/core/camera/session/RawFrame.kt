// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.core.camera.session

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import android.media.Image
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One `RAW_SENSOR` frame held for a possible DNG (ADR-0005, FR-62). It keeps a camera buffer, not a copy, so the
 * owner must [close] it: after writing the DNG, or when it leaves the RAW buffer. [characteristics] and [result]
 * belong to the physical lens that produced it, as `DngCreator` needs.
 */
class RawFrame internal constructor(
    val image: Image,
    val characteristics: CameraCharacteristics,
    val result: CaptureResult,
    /** `SENSOR_ORIENTATION` of the lens, for the DNG orientation tag. */
    val sensorOrientation: Int,
    private val onClose: () -> Unit,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    /** Safe from any thread and more than once. */
    override fun close() {
        if (closed.compareAndSet(false, true)) {
            image.close()
            onClose()
        }
    }
}
