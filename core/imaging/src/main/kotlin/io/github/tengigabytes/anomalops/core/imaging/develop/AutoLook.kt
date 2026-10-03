// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.core.imaging.develop

import kotlin.math.sqrt

/**
 * How bright and how sharp [AutoLook] makes a picture. All proposed, from the maintainer's side-by-side choices of
 * 2026-10-03 on two indoor scenes (too few to be more than a starting point):
 *
 * - [targetMedian]: the picture's median luminance (display-linear) after the gain; 0.15, 0.19 and 0.24 were
 *   tried, with no steady preference between them.
 * - [maxGain]: the most the exposure is raised when the camera's own gain is not known; a dark subject (a black
 *   keyboard filling the frame) would otherwise be pulled up 16 times.
 * - [brightSceneMaxGain]: when the camera's gain is known, the median may raise the exposure above it only up to
 *   this much. 1, so the camera's gain is used as it is: at 2.5 a dark subject in fair light (a black keyboard,
 *   camera gain 1) came out grey, its darkest parts lifted from code 11 to 50, and the maintainer preferred the
 *   camera's own still. One well-lit scene had been preferred at about 2.3, so a rule that tells a dark subject
 *   from a dim scene could raise this again. In dim scenes the camera's gain (5 to 7) was as good as anything
 *   brighter.
 * - [sharpen]: [Sharpen]'s amount in good light; 0.5 was chosen over 1.0 every time.
 * - [sharpenFullGain], [sharpenZeroGain]: the amount fades linearly to nothing between these gains, since
 *   sharpening a picture that was raised a lot sharpens its noise (at a gain of 11 it was not preferred). The two
 *   ends are guesses.
 * - [chromaPasses]: [ChromaDenoise]'s passes; three were preferred to none in a bright scene and two dim ones
 *   (once each).
 * - [chromaOffNoise], [chromaFullNoise]: the merge's predicted colour noise (`NoiseProfile.chromaSigma` over the
 *   root of the frame count, in codes) up to which [ChromaDenoise] is left out, and from which it runs with its
 *   full tolerance of 40 codes; between them the tolerance rises linearly. On 2026-10-03 the full tolerance
 *   erased a faint red stamp on paper in good light (ISO 117, predicted noise 1.5), which the maintainer noticed,
 *   and there no filter was preferred even to a tolerance of 3.7; in two dim bursts (predicted 5.9 and 7.0) the
 *   full tolerance was preferred to 15 and 17. Both ends are guesses between those points; in a bright scene
 *   between them (predicted 1.9) the tolerance they give, 6.6, was preferred both to none and to the full one.
 * - [saturation]: [Saturation]'s factor. 1.25 was preferred to 1.5 in all three modes on 2026-10-03 (once each),
 *   and 1.5 to none in two of them; 1.25 against none was not shown, and in the third mode none beat 1.5.
 */
data class LookOptions(
    val targetMedian: Float = 0.19f,
    val maxGain: Float = 8f,
    val brightSceneMaxGain: Float = 1f,
    val sharpen: Float = 0.5f,
    val sharpenFullGain: Float = 3f,
    val sharpenZeroGain: Float = 8f,
    val chromaPasses: Int = ChromaDenoise.MAX_PASSES,
    val saturation: Float = 1.25f,
    val chromaOffNoise: Float = 1.5f,
    val chromaFullNoise: Float = 4f,
) {
    init {
        require(targetMedian > 0f && maxGain >= 1f && brightSceneMaxGain >= 1f) { "target $targetMedian, gains" }
        require(sharpen in 0f..Sharpen.MAX_AMOUNT && sharpenZeroGain > sharpenFullGain) { "sharpen $sharpen" }
        require(chromaOffNoise >= 0f && chromaFullNoise > chromaOffNoise) { "chroma noise ends" }
    }
}

/** The sensor's noise model for a burst's reference frame and how many [frames] the merge averages. */
class BurstNoise(val profile: NoiseProfile, val frames: Int) {
    init {
        require(frames >= 1) { "frames $frames" }
    }
}

/**
 * The exposure gain and sharpening for one burst, measured on its reference RAW frame: on the CPU for the CPU and
 * the GPU pipelines alike, so both render with the same numbers. The gain never darkens (FR-17's frames are
 * under-exposed on purpose).
 *
 * Above the sensor's highest analog sensitivity (`SENSOR_MAX_ANALOG_SENSITIVITY`, ISO 333 on the Pixel 10 Pro's
 * main lens) the camera's auto-exposure asks for the rest as a gain applied after the RAW, reported as
 * `CONTROL_POST_RAW_SENSITIVITY_BOOST` (hundredths): a RAW frame lacks exactly that much. Seen 2026-10-03: ISO 333
 * with a boost of 711 where the stock camera's JPEG of the scene said ISO 2300. That gain is the exposure the
 * camera metered, so it is the floor here; the median only adds to it in bright scenes.
 */
object AutoLook {
    private const val CHANNELS = 3
    private const val STEP = 8
    private const val HALF = 0.5f
    private val lumaWeights = floatArrayOf(0.2126f, 0.7152f, 0.0722f)

    /**
     * [base] with its exposure and sharpening set for [raw]; [gains], [matrix] and [shading] as for [Render].
     * [postRawGain] is the capture's post-RAW boost as a factor (boost / 100), null when it is not known.
     * [noise] is the sensor's noise model and the number of frames merged; without it the colour denoising keeps
     * its fixed tolerance.
     */
    @Suppress("LongParameterList") // Render's inputs, the capture's gain and the two option sets.
    fun options(
        raw: RawFrame,
        gains: FloatArray,
        matrix: FloatArray,
        shading: ShadingMap? = null,
        look: LookOptions = LookOptions(),
        base: RenderOptions = RenderOptions(),
        postRawGain: Float? = null,
        noise: BurstNoise? = null,
    ): RenderOptions {
        val median = medianLuminance(raw, gains, matrix, shading)
        val floor = postRawGain?.coerceAtLeast(1f) ?: 1f
        val ceiling = if (postRawGain == null) look.maxGain else maxOf(floor, look.brightSceneMaxGain)
        val gain = if (median > 0f) (look.targetMedian / median).coerceIn(floor, ceiling) else ceiling
        val fade = (look.sharpenZeroGain - gain) / (look.sharpenZeroGain - look.sharpenFullGain)
        val sharpen = look.sharpen * fade.coerceIn(0f, 1f)
        val sigma = noise?.let { it.profile.chromaSigma(raw, gains, matrix, shading, gain) / sqrt(it.frames.toFloat()) }
        val share = sigma?.let { (it - look.chromaOffNoise) / (look.chromaFullNoise - look.chromaOffNoise) } ?: 1f
        val tolerance = ChromaDenoise.DEFAULT_TOLERANCE * share.coerceIn(0f, 1f)
        val filtered = tolerance >= ChromaDenoise.MIN_TOLERANCE
        return base.copy(
            exposure = gain,
            sharpen = sharpen,
            chromaPasses = if (filtered) look.chromaPasses else 0,
            saturation = look.saturation,
            chromaTolerance = if (filtered) tolerance else ChromaDenoise.DEFAULT_TOLERANCE,
        )
    }

    /**
     * The median, over every [STEP]th 2 x 2 cell each way, of the luminance [Render] would give that cell at
     * exposure 1 before its highlight blend and tone curve.
     */
    fun medianLuminance(raw: RawFrame, gains: FloatArray, matrix: FloatArray, shading: ShadingMap? = null): Float {
        require(gains.size == CHANNELS && matrix.size == CHANNELS * CHANNELS) { "gains, matrix" }
        val columns = (raw.width / 2 + STEP - 1) / STEP
        val rows = (raw.height / 2 + STEP - 1) / STEP
        val values = FloatArray(columns * rows)
        val shade = FloatArray(CHANNELS) { 1f }
        val v = FloatArray(CHANNELS)
        for (row in 0 until rows) {
            for (column in 0 until columns) {
                val x = 2 * column * STEP
                val y = 2 * row * STEP
                raw.cell(x, y, v)
                shading?.gainsAt(x + HALF, y + HALF, raw.width, raw.height, shade)
                for (c in 0 until CHANNELS) v[c] *= shade[c] * gains[c]
                var luminance = 0f
                for (r in 0 until CHANNELS) {
                    var sum = 0f
                    for (c in 0 until CHANNELS) sum += matrix[r * CHANNELS + c] * v[c]
                    luminance += lumaWeights[r] * sum
                }
                values[row * columns + column] = luminance
            }
        }
        values.sort()
        return values[values.size / 2]
    }
}
