// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.core.gpu

import android.opengl.GLES20
import android.opengl.GLES30
import android.opengl.GLES31
import io.github.tengigabytes.anomalops.core.imaging.develop.RawFrame
import io.github.tengigabytes.anomalops.core.imaging.develop.Render
import io.github.tengigabytes.anomalops.core.imaging.develop.RenderOptions
import io.github.tengigabytes.anomalops.core.imaging.develop.ShadingMap
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The ends of FR-17's pipeline on the GPU (ADR-0017 step 4): a RAW frame uploaded as a 16-bit integer texture and
 * decoded to half-size R, G, B and luma planes (`Demosaic.halfSize` and `Rgb.luma`), and merged camera RGB rendered
 * to 8-bit sRGB ARGB (`Render.toArgb`, with the same sRGB table, and its whole-picture steps in [GpuFinish]).
 * Needs a current [GlesContext].
 */
class GpuDevelop : AutoCloseable {
    private val decode = ComputeProgram(DevelopShaders.HALF_SIZE)
    private val render = ComputeProgram(DevelopShaders.RENDER)
    private val finish = GpuFinish()
    private val buffers = IntArray(BUFFERS).also { GLES20.glGenBuffers(BUFFERS, it, 0) }
    private var raw = 0
    private var rawWidth = 0
    private var rawHeight = 0
    private var staging: ByteBuffer = ByteBuffer.allocateDirect(0)
    private var linearFor: Pair<List<Float>, Float>? = null

    init {
        upload(LUT, lutBytes())
    }

    /**
     * [frame] decoded to half-size red, green, blue and luma planes (FLOAT32), written into [into] (four planes of
     * that size) or new ones.
     */
    fun halfSize(frame: RawFrame, into: List<GpuPlane>? = null): List<GpuPlane> {
        val w = frame.width / 2
        val h = frame.height / 2
        val out = into ?: List(PLANES) { GpuPlane(w, h, PlaneFormat.FLOAT32) }
        require(out.size == PLANES && out.all { it.width == w && it.height == h && it.format == PlaneFormat.FLOAT32 }) {
            "four FLOAT32 planes of ${w}x$h"
        }
        uploadRaw(frame)
        uploadLinear(frame)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, raw)
        out.forEachIndexed { i, plane -> plane.bindImage(i, GLES31.GL_WRITE_ONLY) }
        decode.use()
            .uniform("cell", IntArray(CELL) { frame.layout.colourAt(it % 2, it / 2) })
            .dispatch(GpuPlane.groups(w), GpuPlane.groups(h))
        GLES31.glMemoryBarrier(GLES31.GL_TEXTURE_FETCH_BARRIER_BIT or GLES31.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT)
        checkGl("GpuDevelop.halfSize")
        return out
    }

    /**
     * `Render.toArgb` of camera RGB planes from a [rawWidth] x [rawHeight] frame; [gains] red, green, blue, [matrix]
     * 3 x 3 row by row. The picture then goes through `Render.finish`'s steps ([GpuFinish]) before it is read
     * back.
     */
    @Suppress("LongParameterList") // Render.toArgb's parameters, with the planes apart.
    fun toArgb(
        rgb: List<GpuPlane>,
        gains: FloatArray,
        matrix: FloatArray,
        options: RenderOptions = RenderOptions(),
        shading: ShadingMap? = null,
        rawWidth: Int = rgb[0].width,
        rawHeight: Int = rgb[0].height,
    ): IntArray {
        require(rgb.size >= CHANNELS && gains.size == CHANNELS && matrix.size == CHANNELS * CHANNELS) { "planes" }
        val w = rgb[0].width
        val h = rgb[0].height
        val params = gains + matrix + floatArrayOf(
            options.exposure,
            options.highlightKnee,
            options.shoulder,
            options.white,
        )
        upload(PARAMS, floats(params))
        upload(SHADING, floats(shading?.gains ?: params))
        allocate(OUT, OUT, w * h)
        for (c in 0 until CHANNELS) rgb[c].bindSampler(c)
        render.use()
            .uniform("shadingColumns", shading?.columns ?: 0)
            .uniform("shadingRows", shading?.rows ?: 0)
            .uniform("rawSize", rawWidth, rawHeight)
            .dispatch(GpuPlane.groups(w), GpuPlane.groups(h))
        val result = finish.run(buffers[OUT], w, h, options)
        GLES31.glMemoryBarrier(GLES31.GL_BUFFER_UPDATE_BARRIER_BIT)
        GLES20.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, result)
        val bytes = w * h * Int.SIZE_BYTES
        val mapped = GLES30.glMapBufferRange(GLES31.GL_SHADER_STORAGE_BUFFER, 0, bytes, GLES30.GL_MAP_READ_BIT)
        checkNotNull(mapped) { "glMapBufferRange failed" }
        val argb = IntArray(w * h)
        (mapped as ByteBuffer).order(ByteOrder.nativeOrder()).asIntBuffer().get(argb)
        GLES30.glUnmapBuffer(GLES31.GL_SHADER_STORAGE_BUFFER)
        checkGl("GpuDevelop.toArgb")
        return argb
    }

    /** Buffer [index] sized for [pixels] packed pixels, to be read back, bound to [binding]. */
    private fun allocate(index: Int, binding: Int, pixels: Int) {
        GLES20.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, buffers[index])
        GLES20.glBufferData(GLES31.GL_SHADER_STORAGE_BUFFER, pixels * Int.SIZE_BYTES, null, GLES30.GL_STREAM_READ)
        GLES30.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, binding, buffers[index])
    }

    override fun close() {
        decode.close()
        render.close()
        finish.close()
        GLES20.glDeleteBuffers(BUFFERS, buffers, 0)
        if (raw != 0) GLES20.glDeleteTextures(1, intArrayOf(raw), 0)
    }

    /** The samples into the R16UI texture, which is made again only when the size changes. */
    private fun uploadRaw(frame: RawFrame) {
        if (raw == 0 || frame.width != rawWidth || frame.height != rawHeight) {
            if (raw != 0) GLES20.glDeleteTextures(1, intArrayOf(raw), 0)
            raw = IntArray(1).also { GLES20.glGenTextures(1, it, 0) }[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, raw)
            GLES30.glTexStorage2D(GLES20.GL_TEXTURE_2D, 1, GLES30.GL_R16UI, frame.width, frame.height)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
            rawWidth = frame.width
            rawHeight = frame.height
        }
        val bytes = frame.samples.size * Short.SIZE_BYTES
        if (staging.capacity() < bytes) staging = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())
        staging.clear()
        staging.asShortBuffer().put(frame.samples)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, raw)
        GLES20.glPixelStorei(GLES30.GL_UNPACK_ROW_LENGTH, frame.rowStride)
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 2)
        GLES20.glTexSubImage2D(
            GLES20.GL_TEXTURE_2D,
            0,
            0,
            0,
            frame.width,
            frame.height,
            GLES30.GL_RED_INTEGER,
            GLES20.GL_UNSIGNED_SHORT,
            staging,
        )
        GLES20.glPixelStorei(GLES30.GL_UNPACK_ROW_LENGTH, 0)
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, DEFAULT_ALIGNMENT)
        checkGl("GpuDevelop raw upload")
    }

    /**
     * `RawFrame.linear` of every 16-bit sample for each cell position into buffer [LINEAR], made again only when the
     * black or white levels change (with per-frame dynamic black levels, every frame).
     */
    private fun uploadLinear(frame: RawFrame) {
        val key = frame.blackLevels.toList() to frame.whiteLevel
        if (key != linearFor) {
            val values = FloatArray(CELL * SAMPLES)
            for (k in 0 until CELL) {
                val black = frame.blackLevels[k]
                for (s in 0 until SAMPLES) values[k * SAMPLES + s] = (s - black) / (frame.whiteLevel - black)
            }
            upload(LINEAR, floats(values))
            linearFor = key
        }
        GLES30.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 0, buffers[LINEAR])
    }

    private fun upload(index: Int, data: ByteBuffer) {
        GLES20.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, buffers[index])
        GLES20.glBufferData(GLES31.GL_SHADER_STORAGE_BUFFER, data.capacity(), data, GLES20.GL_DYNAMIC_DRAW)
        GLES30.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, index, buffers[index])
    }

    private fun floats(values: FloatArray): ByteBuffer =
        ByteBuffer.allocateDirect(values.size * Float.SIZE_BYTES).order(ByteOrder.nativeOrder()).apply {
            asFloatBuffer().put(values)
        }

    private companion object {
        const val PLANES = 4
        const val CHANNELS = 3
        const val CELL = 4
        const val LUT_SIZE = 1 shl 16
        const val CODE_MAX = 255
        const val HALF = 0.5f
        const val DEFAULT_ALIGNMENT = 4

        /** Storage buffer bindings (and indices into `buffers`), as the render kernel declares them. */
        const val OUT = 0
        const val PARAMS = 1
        const val SHADING = 2
        const val LUT = 3

        /** The decode kernel's table of linear values, bound to its binding 0. */
        const val LINEAR = 4
        const val BUFFERS = 5
        const val SAMPLES = 1 shl 16

        /** `Render`'s sRGB table, built with the same expression, four 8-bit codes per uint. */
        fun lutBytes(): ByteBuffer = ByteBuffer.allocateDirect(LUT_SIZE).order(ByteOrder.nativeOrder()).apply {
            for (i in 0 until LUT_SIZE) put((Render.srgb(i / (LUT_SIZE - 1f)) * CODE_MAX + HALF).toInt().toByte())
            position(0)
        }
    }
}
