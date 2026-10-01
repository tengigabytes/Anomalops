// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.core.imaging.tool

import io.github.tengigabytes.anomalops.core.imaging.align.FrameAligner
import io.github.tengigabytes.anomalops.core.imaging.align.Plane
import io.github.tengigabytes.anomalops.core.imaging.develop.Demosaic
import io.github.tengigabytes.anomalops.core.imaging.develop.Render
import io.github.tengigabytes.anomalops.core.imaging.develop.Rgb
import io.github.tengigabytes.anomalops.core.imaging.merge.LowLightMerge
import io.github.tengigabytes.anomalops.core.imaging.stack.ContrastSelectStack
import io.github.tengigabytes.anomalops.core.imaging.stack.FocusStack
import io.github.tengigabytes.anomalops.core.imaging.stack.GuidedWeightStack
import io.github.tengigabytes.anomalops.core.imaging.stack.LaplacianPyramidStack
import io.github.tengigabytes.anomalops.core.imaging.stack.StackGuard
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.system.measureTimeMillis

/**
 * Desktop runner for T12 (docs/test/macro-stacking-test-plan.md): DNGs pulled from the phone, merged by each of
 * ADR-0015's three candidates (or by FR-17's low-light merge with `--lowlight`), each written as a PNG beside the
 * sharpest single frame, with the times and StackGuard's verdicts printed. Half-size demosaic unless `--full`.
 * The middle frame of the bracket is the reference. Not part of the app: it lives with the tests so that it runs
 * on the module's code without a module of its own.
 *
 * gradlew :core:imaging:stackTool --args="<out dir> <a.dng> <b.dng> ... [--full] [--lowlight]"
 */
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
    val reference = frames.size / 2
    val render = Renderer(images[reference], out)
    val luma = frames.map { it.luma() }
    val size = "${frames[0].width}x${frames[0].height}"
    println("${frames.size} frames $size, reference ${reference + 1}, shading map ${images[reference].shading != null}")
    val singles = luma.map { StackGuard.sharpness(it) }
    val best = singles.indices.maxBy { singles[it] }
    println("sharpness per frame: ${singles.joinToString { "%.3g".format(it) }}; sharpest ${best + 1}")
    render.write("single-sharpest", frames[best].channels())
    if ("--lowlight" in flags) {
        lowLight(luma, frames, reference, render)
    } else {
        focusStack(luma, frames, reference, render)
    }
}

private fun lowLight(luma: List<Plane>, frames: List<Rgb>, reference: Int, render: Renderer) {
    lateinit var result: LowLightMerge.Result
    val ms = measureTimeMillis {
        result = LowLightMerge().merge(luma, frames.map { it.channels() }, reference = reference)
    }
    println("FR-17 low-light merge: $ms ms")
    render.write("lowlight", result.channels)
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
private class Renderer(private val image: DngImage, private val out: File) {
    private val gains = image.asShotNeutral?.let { n -> FloatArray(3) { n[1] / n[it] } } ?: floatArrayOf(1f, 1f, 1f)
    private val matrix = Colour.cameraToSrgb(image.forwardMatrix)

    fun write(name: String, channels: List<Plane>) {
        val rgb = Rgb(channels[0], channels[1], channels[2])
        val argb = Render.toArgb(
            rgb,
            gains,
            matrix,
            shading = image.shading,
            rawWidth = image.raw.width,
            rawHeight = image.raw.height,
        )
        val picture = BufferedImage(rgb.width, rgb.height, BufferedImage.TYPE_INT_RGB)
        picture.setRGB(0, 0, rgb.width, rgb.height, argb, 0, rgb.width)
        val file = File(out, "$name.png")
        ImageIO.write(picture, "png", file)
        println("wrote ${file.path}")
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
