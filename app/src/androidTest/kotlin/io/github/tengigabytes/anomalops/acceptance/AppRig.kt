// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.acceptance

import android.graphics.ImageFormat
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.Process
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import io.github.tengigabytes.anomalops.capture.ShotPipeline
import io.github.tengigabytes.anomalops.core.camera.session.CameraController
import io.github.tengigabytes.anomalops.core.profile.CalibrationKey
import io.github.tengigabytes.anomalops.core.profile.DepthBand
import io.github.tengigabytes.anomalops.core.profile.DeviceProfile
import io.github.tengigabytes.anomalops.core.profile.DeviceProfiles
import io.github.tengigabytes.anomalops.core.profile.LensFilter
import io.github.tengigabytes.anomalops.core.profile.ScenePreset
import io.github.tengigabytes.anomalops.core.store.media.StillStore
import io.github.tengigabytes.anomalops.core.store.raw.DngStore
import io.github.tengigabytes.anomalops.core.store.raw.RawKeeper
import io.github.tengigabytes.anomalops.core.store.stack.BurstStacks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.io.File
import java.util.Collections

/**
 * The app's shot pipeline on the real camera, with an off-screen preview (a PRIVATE ImageReader that drops
 * frames) and a separate stack database. Every file whose stem is [track]ed is deleted on [close], so the
 * acceptance runs leave no photos behind.
 */
internal class AppRig : AutoCloseable {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val profile: DeviceProfile =
        requireNotNull(DeviceProfiles.load(Build.DEVICE)) { "no device profile for ${Build.DEVICE}" }
    val controller = CameraController(context, profile)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val keeper = RawKeeper(DngStore(context), scope)
    val stacks = BurstStacks(context, TEST_DATABASE)
    val pipeline = ShotPipeline(controller, StillStore(context), keeper, stacks)
    private val stems = Collections.synchronizedSet(mutableSetOf<String>())
    private val sinkThread = HandlerThread("preview-sink").apply { start() }
    private val sink = ImageReader.newInstance(
        CameraController.PREVIEW_SIZE.width,
        CameraController.PREVIEW_SIZE.height,
        ImageFormat.PRIVATE,
        SINK_IMAGES,
        HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE,
    ).apply { setOnImageAvailableListener({ it.acquireLatestImage()?.close() }, Handler(sinkThread.looper)) }

    /** The M1 screen's conditions; calibrated for lenses 2, 3 and 9 (docs/test/m1-pipeline-calibration.md). */
    val conditions = CalibrationKey(DepthBand.SHALLOW, LensFilter.NONE, diveLight = false)

    suspend fun start(preset: ScenePreset = ScenePreset.SNAPSHOT, key: CalibrationKey = conditions) =
        controller.start(sink.surface, preset, key)

    /** Marks the files of [stem] (still, DNG, burst frames) for deletion on [close]. */
    fun track(stem: String?) {
        stem?.let { stems += it }
    }

    /** Display names in MediaStore that start with [stem]. */
    fun files(stem: String): List<Pair<Uri, String>> {
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val projection = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME)
        val where = "${MediaStore.Images.Media.DISPLAY_NAME} LIKE ?"
        return context.contentResolver.query(collection, projection, where, arrayOf("$stem%"), null)?.use { c ->
            buildList {
                while (c.moveToNext()) add(Uri.withAppendedPath(collection, c.getLong(0).toString()) to c.getString(1))
            }
        }.orEmpty()
    }

    fun read(uri: Uri): ByteArray = requireNotNull(context.contentResolver.openInputStream(uri)).use { it.readBytes() }

    /**
     * Memory of this process in MB after a garbage collection (FR-62 check): TOTAL PSS from `dumpsys meminfo`,
     * and the camera buffers, which PSS leaves out. Held RAW frames live in dma-bufs that are not mapped, so they
     * are read from /proc/self/fdinfo (docs/test/m2-instrumented.md).
     */
    @Suppress("ExplicitGarbageCollectionCall") // A leak check must not count garbage that is merely uncollected.
    fun memoryMb(): Memory {
        Runtime.getRuntime().gc()
        Thread.sleep(GC_SETTLE_MS)
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val text = ParcelFileDescriptor.AutoCloseInputStream(
            automation.executeShellCommand("dumpsys meminfo ${Process.myPid()}"),
        ).bufferedReader().use { it.readText() }
        val pssKb = Regex("""TOTAL PSS:\s+(\d+)""").find(text)?.groupValues?.get(1)?.toDouble() ?: Double.NaN
        val dmaBufBytes = File("/proc/self/fdinfo").listFiles().orEmpty().sumOf { info ->
            val lines = runCatching { info.readLines() }.getOrDefault(emptyList())
            if (lines.none { it.startsWith("exp_name:") }) 0L else lines.dmaBufSize()
        }
        return Memory(pssKb / KB_PER_MB, dmaBufBytes / BYTES_PER_MB)
    }

    class Memory(val pssMb: Double, val dmaBufMb: Double)

    private fun List<String>.dmaBufSize(): Long =
        firstOrNull { it.startsWith("size:") }?.substringAfter(':')?.trim()?.toLongOrNull() ?: 0L

    override fun close() {
        keeper.clear()
        controller.stopBlocking()
        controller.release()
        scope.cancel()
        stems.toList().forEach { stem ->
            files(
                stem,
            ).forEach { (uri, _) -> context.contentResolver.delete(uri, null, null) }
        }
        stacks.close()
        context.deleteDatabase(TEST_DATABASE)
        sink.close()
        sinkThread.quitSafely()
    }

    private companion object {
        const val TEST_DATABASE = "burst-stacks-acceptance.db"
        const val SINK_IMAGES = 4
        const val GC_SETTLE_MS = 1_000L
        const val KB_PER_MB = 1024.0
        const val BYTES_PER_MB = 1_048_576.0
    }
}
