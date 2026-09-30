// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.core.camera.experiment

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Handler
import android.view.Surface

/**
 * A video encoder whose output is counted and dropped: the camera session sees a real encoder surface (ADR-0011,
 * check 4) without anything being written. HEVC when the device can encode [width] x [height] at [fps], else AVC;
 * [create] returns null when neither can.
 */
internal class EncoderSink private constructor(val mime: String, private val codec: MediaCodec, val surface: Surface) :
    AutoCloseable {
    /** Presentation times of encoded frames, in microseconds (the camera's sensor clock). */
    val framesUs = mutableListOf<Long>()

    override fun close() {
        runCatching { codec.stop() }
        codec.release()
        surface.release()
    }

    companion object {
        private const val BITS_PER_PIXEL_FRAME = 0.15
        private const val KEY_FRAME_S = 1

        fun create(width: Int, height: Int, fps: Int, handler: Handler): EncoderSink? {
            val (mime, name) = listOf(MediaFormat.MIMETYPE_VIDEO_HEVC, MediaFormat.MIMETYPE_VIDEO_AVC)
                .firstNotNullOfOrNull { mime -> hardwareEncoder(mime, width, height, fps)?.let { mime to it } }
                ?: return null
            val format = MediaFormat.createVideoFormat(mime, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, (width * height * fps * BITS_PER_PIXEL_FRAME).toInt())
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, KEY_FRAME_S)
            }
            val codec = MediaCodec.createByCodecName(name)
            lateinit var sink: EncoderSink
            codec.setCallback(
                object : MediaCodec.Callback() {
                    override fun onInputBufferAvailable(codec: MediaCodec, index: Int) = Unit

                    override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                            sink.framesUs += info.presentationTimeUs
                        }
                        codec.releaseOutputBuffer(index, false)
                    }

                    override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) = Unit

                    override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) = Unit
                },
                handler,
            )
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val surface = codec.createInputSurface()
            codec.start()
            sink = EncoderSink("$mime $name", codec, surface)
            return sink
        }

        /** The name of a hardware encoder for [mime] that takes [width] x [height] at [fps], or null. */
        private fun hardwareEncoder(mime: String, width: Int, height: Int, fps: Int): String? =
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                .filter { it.isEncoder && it.isHardwareAccelerated && mime in it.supportedTypes }
                .firstOrNull {
                    it.getCapabilitiesForType(mime).videoCapabilities
                        ?.areSizeAndRateSupported(width, height, fps.toDouble()) == true
                }?.name
    }
}
