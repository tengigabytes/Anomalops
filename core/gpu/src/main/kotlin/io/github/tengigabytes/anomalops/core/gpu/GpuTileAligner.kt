// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.core.gpu

import android.opengl.GLES20
import android.opengl.GLES30
import android.opengl.GLES31
import io.github.tengigabytes.anomalops.core.imaging.align.AlignOptions
import io.github.tengigabytes.anomalops.core.imaging.align.Similarity
import io.github.tengigabytes.anomalops.core.imaging.align.TileField
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.ceil

/**
 * `TileAligner` on the GPU (ADR-0017 step 4): one work group per tile runs the tile's whole coarse-to-fine search
 * and its parabolas, so a frame takes one dispatch and one read-back. The reference tiles' texture and the trust
 * threshold (a ratio of the median) depend only on the reference, so they are computed once per reference plane.
 * Costs are float sums where the CPU sums in double, so a near tie can pick another shift. Needs a current
 * [GlesContext].
 */
class GpuTileAligner(private val options: AlignOptions = AlignOptions()) : AutoCloseable {
    private val texture = ComputeProgram(TileShaders.TEXTURE)
    private val search = ComputeProgram(TileShaders.ALIGN)
    private val buffers = IntArray(2).also { GLES20.glGenBuffers(2, it, 0) }
    private var textured: GpuPlane? = null
    private var textures = FloatArray(0)
    private var threshold = 0f

    fun align(reference: GpuPyramid, frame: GpuPyramid, global: Similarity): TileField {
        val base = reference[0]
        val size = options.tileSize
        val field = TileField(size, base.width / size, base.height / size)
        val tiles = field.cols * field.rows
        if (textured !== base) measureTexture(base, field.cols, tiles)
        val startLevel = minOf(TILE_LEVEL, reference.top)
        for (level in 0..TILE_LEVEL) {
            reference[minOf(level, startLevel)].bindSampler(level)
            frame[minOf(level, startLevel)].bindSampler(FRAME_UNIT + level)
        }
        val u = (0..TILE_LEVEL).map { LevelUniforms(global, it, base.width, base.height) }
        GLES20.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, buffers[1])
        GLES20.glBufferData(GLES31.GL_SHADER_STORAGE_BUFFER, tiles * VEC4_BYTES, null, GLES30.GL_STREAM_READ)
        GLES30.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 0, buffers[0])
        GLES30.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 1, buffers[1])
        search.use()
            .uniform("tileSize", size)
            .uniform("cols", field.cols)
            .uniform("startLevel", startLevel)
            .uniform("radius", ceil(options.tileSearchPx.toFloat() / (1 shl startLevel)).toInt())
            .uniform("threshold", threshold)
            .uniform("scale", global.scale)
            .uniform("shift", global.dx, global.dy)
            .uniform("centre0", u[0].centreX, u[0].centreY)
            .uniform("centre1", u[1].centreX, u[1].centreY)
            .uniform("centre2", u[2].centreX, u[2].centreY)
            .dispatch(tiles)
        GLES31.glMemoryBarrier(GLES31.GL_BUFFER_UPDATE_BARRIER_BIT)
        GLES20.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, buffers[1])
        read(tiles * VEC4_BYTES) { values ->
            for (i in 0 until tiles) {
                field.dx[i] = values.get(VEC4 * i)
                field.dy[i] = values.get(VEC4 * i + 1)
                field.cost[i] = values.get(VEC4 * i + 2)
                field.trusted[i] = values.get(VEC4 * i + TRUSTED) != 0f
            }
        }
        textures.copyInto(field.texture)
        checkGl("GpuTileAligner")
        return field
    }

    override fun close() {
        texture.close()
        search.close()
        GLES20.glDeleteBuffers(2, buffers, 0)
    }

    /** Each reference tile's texture into buffer 0 (kept for [align]), and the trust threshold from its median. */
    private fun measureTexture(base: GpuPlane, cols: Int, tiles: Int) {
        GLES20.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, buffers[0])
        GLES20.glBufferData(GLES31.GL_SHADER_STORAGE_BUFFER, tiles * Float.SIZE_BYTES, null, GLES30.GL_STATIC_READ)
        GLES30.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 0, buffers[0])
        base.bindSampler(0)
        texture.use().uniform("tileSize", options.tileSize).uniform("cols", cols).dispatch(tiles)
        GLES31.glMemoryBarrier(GLES31.GL_BUFFER_UPDATE_BARRIER_BIT)
        textures = FloatArray(tiles)
        read(tiles * Float.SIZE_BYTES) { it.get(textures) }
        val median = textures.sorted().let { it[it.size / 2] }
        threshold = options.minTextureRatio * median
        textured = base
    }

    /** Maps the bound storage buffer's first [bytes] for reading. */
    private fun read(bytes: Int, use: (FloatBuffer) -> Unit) {
        val mapped = GLES30.glMapBufferRange(GLES31.GL_SHADER_STORAGE_BUFFER, 0, bytes, GLES30.GL_MAP_READ_BIT)
        checkNotNull(mapped) { "glMapBufferRange failed" }
        use((mapped as ByteBuffer).order(ByteOrder.nativeOrder()).asFloatBuffer())
        GLES30.glUnmapBuffer(GLES31.GL_SHADER_STORAGE_BUFFER)
    }

    private companion object {
        /** As `TileAligner`: the search starts on level 2 (a quarter size), or the top if lower. */
        const val TILE_LEVEL = 2

        /** Sampler units: the reference's levels 0..2 on 0..2, the frame's on 3..5. */
        const val FRAME_UNIT = 3
        const val VEC4 = 4
        const val VEC4_BYTES = 16
        const val TRUSTED = 3
    }
}
