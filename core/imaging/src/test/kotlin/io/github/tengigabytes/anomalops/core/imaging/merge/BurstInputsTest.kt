// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.core.imaging.merge

import io.github.tengigabytes.anomalops.core.imaging.develop.CfaLayout
import io.github.tengigabytes.anomalops.core.imaging.develop.Demosaic
import io.github.tengigabytes.anomalops.core.imaging.develop.NoiseProfile
import io.github.tengigabytes.anomalops.core.imaging.develop.RawFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.sin
import kotlin.math.sqrt

/** What a burst needs before merging: its reference frame ([BurstReference]) and its noise ([NoiseProfile]). */
class BurstInputsTest {
    /** A 128 x 96 GBRG frame of [level] at each photosite (0..1 of the range above black 64, white 4095). */
    private fun frame(level: (x: Int, y: Int) -> Float): RawFrame {
        val samples = ShortArray(WIDTH * HEIGHT) {
            (BLACK + level(it % WIDTH, it / WIDTH).coerceIn(0f, 1f) * (WHITE - BLACK) + 0.5f).toInt().toShort()
        }
        return RawFrame(samples, WIDTH, HEIGHT, WIDTH, CfaLayout.GBRG, FloatArray(4) { BLACK }, WHITE)
    }

    /** Stripes of [period] photosites; a longer period stands for the same scene blurred. */
    private fun stripes(period: Float, contrast: Float) =
        frame { x, _ -> 0.3f + contrast * sin(2 * Math.PI.toFloat() * x / period) }

    @Test
    fun theSharpestFrameIsTheReference() {
        val sharp = stripes(period = 12f, contrast = 0.2f)
        val soft = stripes(period = 12f, contrast = 0.08f)
        val softer = stripes(period = 12f, contrast = 0.03f)
        assertTrue(BurstReference.sharpness(sharp) > BurstReference.sharpness(soft))
        assertEquals(2, BurstReference.sharpest(listOf(softer, soft, sharp, soft)))
        assertEquals(0, BurstReference.sharpest(listOf(soft, soft)))
        assertEquals(0.0, BurstReference.sharpness(frame { _, _ -> 0.4f }), 1e-12)
    }

    @Test
    fun aCellIsWhatHalfSizeDemosaicGives() {
        val raw = frame { x, y -> 0.1f + 0.003f * x + 0.002f * y }
        val rgb = Demosaic.halfSize(raw)
        val out = FloatArray(3)
        raw.cell(20, 14, out)
        assertEquals(rgb.red[10, 7], out[0], 0f)
        assertEquals(rgb.green[10, 7], out[1], 0f)
        assertEquals(rgb.blue[10, 7], out[2], 0f)
    }

    @Test
    fun lumaSigmaFollowsTheProfile() {
        // Flat at 0.2 with variance 1e-4 x + 1e-6 per photosite, the same for every colour: 2.1e-5.
        val profile = NoiseProfile(FloatArray(3) { 1e-4f }, FloatArray(3) { 1e-6f })
        val flat = frame { _, _ -> 0.2f }
        val photosite = 2.1e-5
        val expected = sqrt(photosite * (0.2126 * 0.2126 + 0.7152 * 0.7152 / 2 + 0.0722 * 0.0722))
        assertEquals(expected.toFloat(), profile.lumaSigma(flat), expected.toFloat() * 0.01f)
        // Half the frame bright: the mean variance, not the variance at the median level.
        val half = frame { x, _ -> if (x < WIDTH / 2) 0.2f else 0.8f }
        val mixed = sqrt((2.1e-5 + 8.1e-5) / 2 * (0.2126 * 0.2126 + 0.7152 * 0.7152 / 2 + 0.0722 * 0.0722))
        assertEquals(mixed.toFloat(), profile.lumaSigma(half), mixed.toFloat() * 0.02f)
    }

    @Test
    fun lumaSigmaMatchesNoiseAddedWithThatProfile() {
        // Photosites at 0.25 with Gaussian noise of the profile's variance; the luma's spread is what it predicts.
        val scale = 2e-4f
        val offset = 3e-7f
        val sigma = sqrt(scale * 0.25f + offset)
        val random = Random(4)
        val noisy = frame { _, _ -> 0.25f + sigma * random.nextGaussian().toFloat() }
        val luma = Demosaic.halfSize(noisy).luma().data
        val mean = luma.average()
        val measured = sqrt(luma.sumOf { (it - mean) * (it - mean) } / luma.size)
        val predicted = NoiseProfile(FloatArray(3) { scale }, FloatArray(3) { offset }).lumaSigma(noisy)
        assertEquals(measured.toFloat(), predicted, predicted * 0.08f)
    }

    private companion object {
        const val WIDTH = 128
        const val HEIGHT = 96
        const val BLACK = 64f
        const val WHITE = 4095f
    }
}
