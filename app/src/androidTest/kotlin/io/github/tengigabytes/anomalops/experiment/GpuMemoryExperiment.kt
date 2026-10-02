// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.experiment

import android.Manifest
import android.os.ParcelFileDescriptor
import android.os.Process
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import io.github.tengigabytes.anomalops.acceptance.AppRig
import io.github.tengigabytes.anomalops.core.gpu.GlesContext
import io.github.tengigabytes.anomalops.core.gpu.GpuContrastSelect
import io.github.tengigabytes.anomalops.core.gpu.GpuDevelop
import io.github.tengigabytes.anomalops.core.gpu.GpuFocusAccumulator
import io.github.tengigabytes.anomalops.core.gpu.GpuLaplacianPyramid
import io.github.tengigabytes.anomalops.core.gpu.GpuLowLightMerge
import io.github.tengigabytes.anomalops.core.gpu.GpuPlane
import io.github.tengigabytes.anomalops.core.gpu.GpuStackGuard
import io.github.tengigabytes.anomalops.core.gpu.PlaneFormat
import io.github.tengigabytes.anomalops.core.imaging.align.Similarity
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * T11 (docs/test/macro-stacking-test-plan.md) and ADR-0017 step 6: the memory of the GPU merges while the camera
 * previews and the FR-62 RAW buffer is full. For FR-17 (5 frames) and FR-33 A and B with `StackGuard` (8 frames),
 * every frame is decoded on the GPU and added; before `finish`, when the GPU holds the most, five stills fill the
 * RAW buffer and the memory is read; then the merge finishes, everything is released and it is read again. The RAW
 * frames are made one at a time on the CPU (only one is in the Java heap) and are already aligned, so alignment is
 * left out. Logs `T11` lines under [TAG]: TOTAL PSS, the Graphics and GL mtrack rows, Java and native heap of
 * `dumpsys meminfo`, and the dma-bufs this process holds (where the RAW buffer lives). Stills are deleted afterwards.
 */
@RunWith(AndroidJUnit4::class)
class GpuMemoryExperiment {
    @get:Rule
    val cameraPermission: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.CAMERA)

    private val rig = AppRig()

    @After
    fun tearDown() = rig.close()

    @Test
    fun t11_gpuMergesWithRawBufferFull() = runBlocking<Unit> {
        rig.start()
        delay(SETTLE_MS)
        report("preview running")
        fillRawBuffer()
        report("preview, RAW buffer full")
        rig.keeper.clear()
        GlesContext.create().use {
            GpuDevelop().use { develop ->
                val planes = List(PLANES) { GpuPlane(W, H, PlaneFormat.FLOAT32) }
                report("GL context, decoder and four planes")
                lowLight(develop, planes)
                GpuContrastSelect().use { a -> stack("FR-33 A", develop, planes) { w, h, c -> a.start(w, h, c) } }
                GpuLaplacianPyramid().use { b -> stack("FR-33 B", develop, planes) { w, h, c -> b.start(w, h, c) } }
                planes.forEach { it.close() }
            }
        }
        report("GL context closed")
    }

    private suspend fun lowLight(develop: GpuDevelop, planes: List<GpuPlane>) {
        val reference = List(PLANES) { GpuPlane(W, H, PlaneFormat.FLOAT32) }
        develop.halfSize(raw(0), into = reference)
        GpuLowLightMerge().use { merge ->
            val accumulator = merge.start(reference[LUMA], reference.take(LUMA), SIGMA)
            for (k in 1 until LOW_LIGHT_FRAMES) {
                develop.halfSize(raw(k), into = planes)
                accumulator.add(planes[LUMA], planes.take(LUMA))
            }
            fillRawBuffer()
            report("FR-17 $LOW_LIGHT_FRAMES frames added, RAW buffer full")
            accumulator.finish().forEach { it.close() }
        }
        reference.forEach { it.close() }
        rig.keeper.clear()
        report("FR-17 released")
    }

    private suspend fun stack(
        name: String,
        develop: GpuDevelop,
        planes: List<GpuPlane>,
        candidate: (Int, Int, Int) -> GpuFocusAccumulator,
    ) {
        val reference = List(PLANES) { GpuPlane(W, H, PlaneFormat.FLOAT32) }
        develop.halfSize(raw(0), into = reference)
        GpuStackGuard(candidate).use { guard ->
            val session = guard.start(reference[LUMA], reference.take(LUMA))
            repeat(session.passes) { pass ->
                for (k in 0 until STACK_FRAMES) {
                    develop.halfSize(raw(k), into = planes)
                    session.add(planes[LUMA], planes.take(LUMA))
                }
                if (pass < session.passes - 1) session.endPass()
            }
            fillRawBuffer()
            report("$name $STACK_FRAMES frames added, RAW buffer full")
            session.finish().channels.forEach { it.close() }
        }
        reference.forEach { it.close() }
        rig.keeper.clear()
        report("$name released")
    }

    /** RAW frame [k] of the bracket, made on the CPU (only this one is held). */
    private fun raw(k: Int) = BenchScene.raw(Similarity(), NOISE, noiseSeed = 2 + k)

    private suspend fun fillRawBuffer() {
        repeat(STILLS) { rig.track(rig.pipeline.shoot().saved.stem) }
    }

    /** One `T11` line: the rows of `dumpsys meminfo` that matter here and the dma-bufs, after a collection. */
    @Suppress("ExplicitGarbageCollectionCall") // Garbage left by the frame generator is not the merge's memory.
    private fun report(label: String) {
        Runtime.getRuntime().gc()
        Thread.sleep(GC_SETTLE_MS)
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val text = ParcelFileDescriptor.AutoCloseInputStream(
            automation.executeShellCommand("dumpsys meminfo ${Process.myPid()}"),
        ).bufferedReader().use { it.readText() }
        fun kb(pattern: String) = Regex(pattern).find(text)?.groupValues?.get(1)?.toDouble()?.div(KB_PER_MB)
        val rows = listOf(
            "PSS" to kb("""TOTAL PSS:\s+(\d+)"""),
            "Graphics" to kb("""Graphics:\s+(\d+)"""),
            "GL mtrack" to kb("""GL mtrack\s+(\d+)"""),
            "Java heap" to kb("""Java Heap:\s+(\d+)"""),
            "native heap" to kb("""Native Heap:\s+(\d+)"""),
        ).joinToString { (name, mb) -> if (mb == null) "$name -" else "$name %.1f".format(mb) }
        Log.i(TAG, "T11 $label: $rows, dma-buf %.1f MB".format(dmaBufMb()))
    }

    /** The dma-bufs this process holds, from /proc/self/fdinfo (as `AppRig.memoryMb` reads them). */
    private fun dmaBufMb(): Double = File("/proc/self/fdinfo").listFiles().orEmpty().sumOf { info ->
        val lines = runCatching { info.readLines() }.getOrDefault(emptyList())
        if (lines.none { it.startsWith("exp_name:") }) {
            0L
        } else {
            lines.firstOrNull { it.startsWith("size:") }?.substringAfter(':')?.trim()?.toLongOrNull() ?: 0L
        }
    } / BYTES_PER_MB

    private companion object {
        const val TAG = "GpuMemory"
        const val W = BenchScene.WIDTH
        const val H = BenchScene.HEIGHT
        const val PLANES = 4
        const val LUMA = 3
        const val LOW_LIGHT_FRAMES = 5
        const val STACK_FRAMES = 8
        const val STILLS = 5
        const val NOISE = 40f

        // Noise of the decoded planes (linear, white = 1): 40 RAW units of about 4000.
        const val SIGMA = 0.01f
        const val SETTLE_MS = 2_000L
        const val GC_SETTLE_MS = 1_000L
        const val KB_PER_MB = 1024.0
        const val BYTES_PER_MB = 1_048_576.0
    }
}
