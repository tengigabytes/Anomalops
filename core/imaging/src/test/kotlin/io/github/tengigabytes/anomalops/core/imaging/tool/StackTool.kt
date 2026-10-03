// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.core.imaging.tool

import io.github.tengigabytes.anomalops.core.imaging.align.AlignOptions
import io.github.tengigabytes.anomalops.core.imaging.align.Alignment
import io.github.tengigabytes.anomalops.core.imaging.align.FrameAligner
import io.github.tengigabytes.anomalops.core.imaging.align.Plane
import io.github.tengigabytes.anomalops.core.imaging.develop.AutoLook
import io.github.tengigabytes.anomalops.core.imaging.develop.Demosaic
import io.github.tengigabytes.anomalops.core.imaging.develop.Render
import io.github.tengigabytes.anomalops.core.imaging.develop.RenderOptions
import io.github.tengigabytes.anomalops.core.imaging.develop.Rgb
import io.github.tengigabytes.anomalops.core.imaging.merge.LowLightMerge
import io.github.tengigabytes.anomalops.core.imaging.stack.ContrastSelectStack
import io.github.tengigabytes.anomalops.core.imaging.stack.FocusStack
import io.github.tengigabytes.anomalops.core.imaging.stack.GuidedWeightStack
import io.github.tengigabytes.anomalops.core.imaging.stack.LaplacianPyramidStack
import io.github.tengigabytes.anomalops.core.imaging.stack.StackGuard
import java.awt.image.BufferedImage
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.imageio.ImageIO
import kotlin.math.hypot
import kotlin.system.measureTimeMillis

/**
 * Desktop runner for T12 (docs/test/macro-stacking-test-plan.md): DNGs pulled from the phone, merged by each of
 * ADR-0015's three candidates (or by FR-17's low-light merge with `--lowlight`), each written as a PNG beside the
 * sharpest single frame, with the times and StackGuard's verdicts printed. Half-size demosaic unless `--full`.
 * The middle frame of a bracket is the reference; a low-light burst merges onto its sharpest frame, since a
 * blurred reference blurs the result. Not part of the app: it lives with the tests so that it runs
 * on the module's code without a module of its own.
 *
 * `--exposure=<gain>` renders with that linear gain (default 1); `--auto` lets `AutoLook` set the gain and the
 * sharpening from the reference frame; `--dump` also writes each picture's linear data.
 *
 * gradlew :core:imaging:stackTool --args="<out dir> <a.dng> <b.dng> ... [--full] [--lowlight] [--auto]"
 */
private const val EXPOSURE_FLAG = "--exposure="

fun main(args: Array<String>) {
    val flags = args.filter { it.startsWith("--") }.toSet()
    val paths = args.filterNot { it.startsWith("--") }
    require(paths.size >= 3) { "usage: <out dir> <dng> <dng> [...] [--full] [--lowlight]" }
    val out = File(paths[0]).apply { mkdirs() }
    val images = paths.drop(1).map { DngReader.read(File(it).readBytes()) }
    val raw = images.map { it.raw }
    require(raw.all { it.width == raw[0].width && it.height == raw[0].height && it.layout == raw[0].layout }) {
        "frames differ in size or colour layout"
    }
    val frames = raw.map { if ("--full" in flags) Demosaic.bilinear(it) else Demosaic.halfSize(it) }
    val luma = frames.map { it.luma() }
    val singles = luma.map { StackGuard.sharpness(it) }
    val best = singles.indices.maxBy { singles[it] }
    val reference = if ("--lowlight" in flags) best else frames.size / 2
    val exposure = flags.firstOrNull { it.startsWith(EXPOSURE_FLAG) }?.removePrefix(EXPOSURE_FLAG)?.toFloat() ?: 1f
    val base = RenderOptions(exposure = exposure)
    val render = Renderer(images[reference], out, base, "--dump" in flags, "--auto" in flags)
    val size = "${frames[0].width}x${frames[0].height}"
    println("${frames.size} frames $size, reference ${reference + 1}, shading map ${images[reference].shading != null}")
    println("sharpness per frame: ${singles.joinToString { "%.3g".format(it) }}; sharpest ${best + 1}")
    render.write("single-sharpest", frames[best].channels())
    if ("--lowlight" in flags) {
        lowLight(luma, frames, reference, render, "lowlight", AlignOptions())
        // Every tile untrusted: the whole-frame transform alone, to tell what the tile residuals add or spoil.
        lowLight(luma, frames, reference, render, "lowlight-global", AlignOptions(minTextureRatio = Float.MAX_VALUE))
        // No alignment and no weights: what a burst from a tripod should come out as.
        render.write("lowlight-mean", plainMean(frames.map { it.channels() }))
    } else {
        focusStack(luma, frames, reference, render)
    }
}

private fun lowLight(
    luma: List<Plane>,
    frames: List<Rgb>,
    reference: Int,
    render: Renderer,
    name: String,
    options: AlignOptions,
) {
    lateinit var result: LowLightMerge.Result
    val ms = measureTimeMillis {
        result = LowLightMerge(options).merge(luma, frames.map { it.channels() }, reference = reference)
    }
    println("FR-17 low-light merge ($name): $ms ms")
    result.alignments.forEachIndexed { k, a -> if (a != null) println("frame ${k + 1}: ${describe(a)}") }
    render.write(name, result.channels)
}

private fun plainMean(frames: List<List<Plane>>): List<Plane> = frames[0].indices.map { c ->
    val first = frames[0][c]
    val sum = FloatArray(first.data.size)
    frames.forEach { f -> for (i in sum.indices) sum[i] += f[c].data[i] }
    Plane(first.width, first.height, FloatArray(sum.size) { sum[it] / frames.size })
}

/** The whole-frame transform and how far the trusted tiles moved on top of it (level-0 pixels). */
private fun describe(a: Alignment): String {
    val t = a.tiles
    val moved = t.dx.indices.filter { t.trusted[it] }.map { hypot(t.dx[it], t.dy[it]) }.sorted()
    val global = "scale %.5f, shift %.2f, %.2f".format(a.global.scale, a.global.dx, a.global.dy)
    if (moved.isEmpty()) return "$global; no trusted tiles"
    val over = moved.count { it > 0.5f }
    return "$global; ${moved.size} of ${t.dx.size} tiles trusted, residual median %.2f, 90%% %.2f, max %.2f, ".format(
        moved[moved.size / 2],
        moved[moved.size * 9 / 10],
        moved.last(),
    ) + "$over over 0.5 px"
}

private fun focusStack(luma: List<Plane>, frames: List<Rgb>, reference: Int, render: Renderer) {
    val aligner = FrameAligner(luma[reference])
    lateinit var aligned: List<List<Plane>>
    val alignMs = measureTimeMillis {
        aligned = luma.indices.map { k ->
            val planes = frames[k].channels() + luma[k]
            if (k == reference) {
                planes
            } else {
                val global = aligner.align(luma[k]).global
                println("frame ${k + 1}: scale %.5f, shift %.2f, %.2f".format(global.scale, global.dx, global.dy))
                planes.map { aligner.warp(it, global) }
            }
        }
    }
    println("alignment: $alignMs ms")
    val candidates: List<Pair<String, FocusStack>> =
        listOf("A" to ContrastSelectStack(), "B" to LaplacianPyramidStack(), "C" to GuidedWeightStack())
    candidates.forEach { (key, stack) ->
        lateinit var result: StackGuard.Result
        val ms = measureTimeMillis {
            result = StackGuard(stack).merge(aligned.map { it.last() }, aligned.map { it.dropLast(1) }, reference)
        }
        val verdict = if (result.merged) "kept" else "fell back to frame ${result.bestFrame + 1}"
        val scores = "sharpness %.3g vs best single %.3g".format(result.sharpness, result.bestSingle)
        println("${stack.name}: $ms ms, $scores, $verdict")
        render.write("stack-$key", result.channels)
    }
}

/** Renders camera RGB planes with [image]'s white balance, colour and shading, and writes them as PNG. */
private class Renderer(
    private val image: DngImage,
    private val out: File,
    base: RenderOptions,
    private val dump: Boolean,
    auto: Boolean,
) {
    private val gains = image.asShotNeutral?.let { n -> FloatArray(3) { n[1] / n[it] } } ?: floatArrayOf(1f, 1f, 1f)
    private val matrix = Colour.cameraToSrgb(image.forwardMatrix)
    private val options = if (auto) {
        AutoLook.options(image.raw, gains, matrix, image.shading, base = base, postRawGain = image.postRawGain)
    } else {
        base
    }

    init {
        val used = "exposure %.2f, sharpen %.2f".format(options.exposure, options.sharpen)
        println("render: $used (the capture's post-RAW gain %.2f)".format(image.postRawGain))
    }

    fun write(name: String, channels: List<Plane>) {
        val rgb = Rgb(channels[0], channels[1], channels[2])
        val argb = Render.toArgb(
            rgb,
            gains,
            matrix,
            options,
            shading = image.shading,
            rawWidth = image.raw.width,
            rawHeight = image.raw.height,
        )
        val picture = BufferedImage(rgb.width, rgb.height, BufferedImage.TYPE_INT_RGB)
        picture.setRGB(0, 0, rgb.width, rgb.height, argb, 0, rgb.width)
        val file = File(out, "$name.png")
        ImageIO.write(picture, "png", file)
        println("wrote ${file.path}")
        if (dump) dump(name, channels)
    }

    /**
     * `<name>.f32` for trying looks outside this tool: little-endian float32, four per pixel, row by row: camera
     * R, G, B after the shading gains (no white balance yet), then the brightest channel before those gains (what
     * [Render] checks for clipping).
     */
    private fun dump(name: String, channels: List<Plane>) {
        val width = channels[0].width
        val height = channels[0].height
        val buffer = ByteBuffer.allocate(width * height * DUMP_FLOATS * Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        val shade = FloatArray(RGB) { 1f }
        val scaleX = image.raw.width.toFloat() / width
        val scaleY = image.raw.height.toFloat() / height
        for (y in 0 until height) {
            for (x in 0 until width) {
                val rawX = (x + HALF) * scaleX - HALF
                image.shading?.gainsAt(rawX, (y + HALF) * scaleY - HALF, image.raw.width, image.raw.height, shade)
                var peak = 0f
                for (c in 0 until RGB) {
                    val v = channels[c].data[y * width + x]
                    peak = maxOf(peak, v)
                    buffer.putFloat(v * shade[c])
                }
                buffer.putFloat(peak)
            }
        }
        File(out, "$name.f32").writeBytes(buffer.array())
    }

    private companion object {
        const val RGB = 3
        const val DUMP_FLOATS = 4
        const val HALF = 0.5f
    }
}

/** Colour conversion from a DNG's forward matrix. */
internal object Colour {
    /** XYZ (D50) to linear sRGB (D65), Bradford-adapted (IEC 61966-2-1 primaries). */
    private val XYZ_D50_TO_SRGB = floatArrayOf(
        3.1338561f, -1.6168667f, -0.4906146f,
        -0.9787684f, 1.9161415f, 0.0334540f,
        0.0719453f, -0.2289914f, 1.4052427f,
    )

    /** White-balanced camera RGB to linear sRGB; identity (camera colour as is) without a forward matrix. */
    fun cameraToSrgb(forward: FloatArray?): FloatArray {
        if (forward == null) return floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        return FloatArray(9) { i ->
            val row = i / 3
            val col = i % 3
            (0 until 3).fold(0f) { sum, k -> sum + XYZ_D50_TO_SRGB[row * 3 + k] * forward[k * 3 + col] }
        }
    }
}
