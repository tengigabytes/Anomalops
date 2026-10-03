// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.core.imaging.align

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Random
import kotlin.math.exp

class FieldOfViewTest {
    /** A scene of Gaussian blobs given analytically, so any part of it renders at any size without resampling. */
    private class Scene(seed: Long) {
        private val random = Random(seed)
        private val blobs = List(BLOBS) {
            floatArrayOf(random.nextFloat(), random.nextFloat(), 0.01f + 0.03f * random.nextFloat(), random.nextFloat())
        }

        /** The part [left]..[right] by [top]..[bottom] of the scene (fractions) as a [WIDTH] x [HEIGHT] plane. */
        fun render(left: Float, top: Float, right: Float, bottom: Float): Plane {
            val out = Plane(WIDTH, HEIGHT)
            for (y in 0 until HEIGHT) {
                for (x in 0 until WIDTH) {
                    val u = left + (right - left) * (x + 0.5f) / WIDTH
                    val v = top + (bottom - top) * (y + 0.5f) / HEIGHT
                    out[x, y] = blobs.fold(0f) { sum, b ->
                        val d2 = (u - b[0]) * (u - b[0]) + (v - b[1]) * (v - b[1])
                        sum + b[3] * exp(-d2 / (2 * b[2] * b[2]))
                    }
                }
            }
            return out
        }
    }

    private fun Plane.toned(gain: Float, offset: Float, noise: Float, seed: Long): Plane {
        val random = Random(seed)
        return Plane(
            width,
            height,
            FloatArray(data.size) { gain * data[it] + offset + noise * random.nextGaussian().toFloat() },
        )
    }

    @Test
    fun aCentredCropIsFoundDespiteDifferentToneAndNoise() {
        val scene = Scene(1)
        val rendered = scene.render(0f, 0f, 1f, 1f).toned(1f, 0f, 0.02f, 2)
        // The camera's still: the middle 85 %, with more contrast, lifted, and its own noise.
        val camera = scene.render(0.075f, 0.075f, 0.925f, 0.925f).toned(1.5f, 0.3f, 0.02f, 3)
        val crop = checkNotNull(FieldOfView.crop(camera, rendered))
        assertEquals(0.075f, crop.left, TOLERANCE)
        assertEquals(0.075f, crop.top, TOLERANCE)
        assertEquals(0.925f, crop.right, TOLERANCE)
        assertEquals(0.925f, crop.bottom, TOLERANCE)
    }

    @Test
    fun aSmallerCropAndAnOffCentreOneAreFoundToo() {
        val scene = Scene(4)
        val rendered = scene.render(0f, 0f, 1f, 1f)
        val tight = checkNotNull(FieldOfView.crop(scene.render(0.03f, 0.03f, 0.97f, 0.97f), rendered))
        assertEquals(0.03f, tight.left, TOLERANCE)
        assertEquals(0.97f, tight.bottom, TOLERANCE)
        // 88 % of the frame, its centre 1 % right of and 0.5 % below the middle.
        val shifted = checkNotNull(FieldOfView.crop(scene.render(0.07f, 0.065f, 0.95f, 0.945f), rendered))
        assertEquals(0.07f, shifted.left, TOLERANCE)
        assertEquals(0.065f, shifted.top, TOLERANCE)
        assertEquals(0.95f, shifted.right, TOLERANCE)
        assertEquals(0.945f, shifted.bottom, TOLERANCE)
    }

    @Test
    fun theSamePictureIsTheWholeFrame() {
        val plane = Scene(5).render(0f, 0f, 1f, 1f)
        val crop = checkNotNull(FieldOfView.crop(plane, plane))
        assertEquals(0f, crop.left, TOLERANCE)
        assertEquals(1f, crop.right, TOLERANCE)
    }

    @Test
    fun noMatchForAFlatOrUnrelatedPicture() {
        val rendered = Scene(6).render(0f, 0f, 1f, 1f)
        assertNull(FieldOfView.crop(Plane(WIDTH, HEIGHT), rendered))
        val random = Random(7)
        val noise = Plane(WIDTH, HEIGHT, FloatArray(WIDTH * HEIGHT) { random.nextFloat() })
        assertNull(FieldOfView.crop(noise, rendered))
        assertNotNull(FieldOfView.crop(rendered, rendered))
    }

    private companion object {
        const val WIDTH = 252
        const val HEIGHT = 189
        const val BLOBS = 400

        /** Half a percent of the frame: about one pixel at this size. */
        const val TOLERANCE = 0.005f
    }
}
