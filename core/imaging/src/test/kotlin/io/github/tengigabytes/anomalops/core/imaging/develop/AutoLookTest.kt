// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.core.imaging.develop

import org.junit.Assert.assertEquals
import org.junit.Test

class AutoLookTest {
    private val identity = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
    private val unity = floatArrayOf(1f, 1f, 1f)

    /** A 64 x 48 GBRG frame, every photosite at [level] of the range (black 64, white 4095). */
    private fun grey(level: Float, levelFor: (x: Int, y: Int) -> Float = { _, _ -> level }): RawFrame {
        val samples = ShortArray(64 * 48) {
            (BLACK + levelFor(it % 64, it / 64) * (WHITE - BLACK) + 0.5f).toInt().toShort()
        }
        return RawFrame(samples, 64, 48, 64, CfaLayout.GBRG, FloatArray(4) { BLACK }, WHITE)
    }

    @Test
    fun theGainBringsTheMedianToTheTarget() {
        val options = AutoLook.options(grey(0.038f), unity, identity)
        assertEquals(0.19f / 0.038f, options.exposure, 0.01f)
        // Gain 5 of the 3..8 fade: 0.5 * (8 - 5) / (8 - 3).
        assertEquals(0.3f, options.sharpen, 0.002f)
    }

    @Test
    fun aBrightFrameIsNotDarkenedAndGetsTheFullSharpening() {
        val options = AutoLook.options(grey(0.5f), unity, identity)
        assertEquals(1f, options.exposure, 0f)
        assertEquals(0.5f, options.sharpen, 0f)
    }

    @Test
    fun aDarkFrameStopsAtTheMaximumGainWithoutSharpening() {
        val options = AutoLook.options(grey(0.004f), unity, identity)
        assertEquals(8f, options.exposure, 0f)
        assertEquals(0f, options.sharpen, 0f)
        val black = AutoLook.options(grey(0f), unity, identity)
        assertEquals(8f, black.exposure, 0f)
    }

    @Test
    fun theMedianFollowsGainsMatrixAndShading() {
        val raw = grey(0.1f)
        assertEquals(0.1f, AutoLook.medianLuminance(raw, unity, identity), 1e-3f)
        // Red doubled: luminance 0.1 * (2 * 0.2126 + 0.7152 + 0.0722).
        assertEquals(0.12126f, AutoLook.medianLuminance(raw, floatArrayOf(2f, 1f, 1f), identity), 1e-3f)
        // A matrix that sends green to every output: luminance is the green value.
        val green = floatArrayOf(0f, 1f, 0f, 0f, 1f, 0f, 0f, 1f, 0f)
        assertEquals(0.3f, AutoLook.medianLuminance(raw, floatArrayOf(1f, 3f, 1f), green), 3e-3f)
        val doubled = ShadingMap(2, 2, FloatArray(16) { 2f })
        assertEquals(0.2f, AutoLook.medianLuminance(raw, unity, identity, doubled), 2e-3f)
    }

    @Test
    fun theMedianIgnoresASmallBrightPart() {
        // A quarter of the frame is a lamp; the median stays with the rest.
        val raw = grey(0.05f) { x, y -> if (x < 32 && y < 24) 0.9f else 0.05f }
        assertEquals(0.05f, AutoLook.medianLuminance(raw, unity, identity), 1e-3f)
    }

    @Test
    fun theCamerasPostRawGainIsTheFloorAndOnlyBrightScenesGoAboveIt() {
        val lifting = LookOptions(brightSceneMaxGain = 2.5f)
        fun gain(level: Float, postRaw: Float) =
            AutoLook.options(grey(level), unity, identity, look = lifting, postRawGain = postRaw).exposure
        // Dim: the median alone would ask for 19; the camera metered 6.
        assertEquals(6f, gain(0.01f, 6f), 0f)
        // The camera's gain holds even when the median would ask for less.
        assertEquals(4f, gain(0.5f, 4f), 0f)
        // Bright (no post-RAW gain): the median raises it, up to 2.5.
        assertEquals(1.9f, gain(0.1f, 1f), 0.01f)
        assertEquals(2.5f, gain(0.05f, 1f), 0f)
        // By default the camera's gain is used as it is, however dark the frame.
        assertEquals(1f, AutoLook.options(grey(0.05f), unity, identity, postRawGain = 1f).exposure, 0f)
        // Sharpening fades with the gain actually used: 0.5 * (8 - 6) / (8 - 3).
        assertEquals(0.2f, AutoLook.options(grey(0.01f), unity, identity, postRawGain = 6f).sharpen, 1e-6f)
    }

    @Test
    fun otherRenderOptionsAreKept() {
        val base = RenderOptions(shoulder = 0.8f, white = 3f)
        val look = LookOptions(targetMedian = 0.3f)
        val options = AutoLook.options(grey(0.1f), unity, identity, base = base, look = look)
        assertEquals(3f, options.exposure, 0.03f)
        assertEquals(0.8f, options.shoulder, 0f)
        assertEquals(3f, options.white, 0f)
    }

    private companion object {
        const val BLACK = 64f
        const val WHITE = 4095f
    }
}
