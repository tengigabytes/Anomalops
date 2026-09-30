// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.orientation

import kotlin.math.sqrt

/**
 * FR-92: flips the portrait UI by 180° when the phone is held upside down (camera end down, for macro close to the
 * seabed), from the accelerometer's gravity in the screen plane. Device axes as `SensorEvent`: +y points to the
 * top of the screen, so upright portrait reads y ≈ +g and upside down y ≈ −g.
 *
 * Hysteresis and a hold time keep it from flapping: it flips only after the new side has held for [holdMs], it
 * keeps its state while the phone lies nearly flat (gravity mostly on z) or sideways, and [locked] freezes it.
 * UNVERIFIED(G3): thresholds and hold time are proposals for the pool test. Not thread-safe.
 */
class InversionDetector(private val holdMs: Long = HOLD_MS) {
    var inverted: Boolean = false
        private set

    /** FR-92's lock: while set, the UI keeps its current side whatever the phone does. */
    var locked: Boolean = false

    private var pendingSinceMs: Long? = null

    /** One accelerometer sample in m/s² at [timeMs]; returns [inverted]. */
    fun onGravity(x: Float, y: Float, z: Float, timeMs: Long): Boolean {
        val wants = wantsInverted(x, y, z)
        if (locked || wants == null || wants == inverted) {
            pendingSinceMs = null
        } else {
            val since = pendingSinceMs ?: timeMs.also { pendingSinceMs = it }
            if (timeMs - since >= holdMs) {
                inverted = wants
                pendingSinceMs = null
            }
        }
        return inverted
    }

    /** True or false when the phone clearly points one way; null when flat or sideways. */
    private fun wantsInverted(x: Float, y: Float, z: Float): Boolean? {
        val inPlane = sqrt(x * x + y * y)
        val total = sqrt(x * x + y * y + z * z)
        if (total == 0f || inPlane / total < MIN_IN_PLANE) return null
        val upness = y / inPlane
        return when {
            upness <= -FLIP_COS -> true
            upness >= FLIP_COS -> false
            else -> null
        }
    }

    companion object {
        /** Proposed: 0.5 s, long enough to ignore a swinging arm. */
        const val HOLD_MS = 500L

        /** Proposed: tilted at least about 20° out of flat (sin 20° ≈ 0.34). */
        const val MIN_IN_PLANE = 0.34f

        /** Proposed: within 60° of straight up or straight down (cos 60° = 0.5). */
        const val FLIP_COS = 0.5f

        private const val FULL_TURN = 360
        private const val HALF_TURN = 180

        /**
         * FR-92 "photo orientation correct": `JPEG_ORIENTATION` for the back camera, from its
         * `SENSOR_ORIENTATION` and the device held upright (0°) or upside down (180°).
         */
        fun jpegOrientation(sensorOrientation: Int, inverted: Boolean): Int =
            (sensorOrientation + if (inverted) HALF_TURN else 0) % FULL_TURN
    }
}
