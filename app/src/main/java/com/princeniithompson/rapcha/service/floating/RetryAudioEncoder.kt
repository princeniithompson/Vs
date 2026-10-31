package com.princeniithompson.rapcha.service.floating

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import android.util.Log
import java.io.File

data class EncodedAudio(
    val bytes: ByteArray,
    val mimeType: String,
    val format: String
)

interface RetryAudioEncoder {
    fun encode(wavBytes: ByteArray): EncodedAudio
}

/** Sends the WAV unchanged. Used when compression is unavailable and in tests. */
object WavIdentityEncoder : RetryAudioEncoder {
    override fun encode(wavBytes: ByteArray): EncodedAudio {
        return EncodedAudio(wavBytes, "audio/wav", "wav")
    }
}

/**
 * Opus in OGG on API 29+, otherwise AAC in M4A. Any encoder failure returns WAV.
 */
class AndroidRetryAudioEncoder(
    private val sdkInt: Int = Build.VERSION.SDK_INT
) : RetryAudioEncoder {
    override fun encode(wavBytes: ByteArray): EncodedAudio {
        val pcm = pcmPayload(wavBytes)
        if (pcm.isEmpty()) return WavIdentityEncoder.encode(wavBytes)
        if (sdkInt >= Build.VERSION_CODES.Q) {
            encodeCompressed(pcm, opus = true)?.let { return it }
        }
        encodeCompressed(pcm, opus = false)?.let { return it }
        return WavIdentityEncoder.encode(wavBytes)
    }

    private fun encodeCompressed(pcm: ByteArray, opus: Boolean): EncodedAudio? {
        var codec: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var temp: File? = null
        try {
            val mime = if (opus) MediaFormat.MIMETYPE_AUDIO_OPUS else MediaFormat.MIMETYPE_AUDIO_AAC
            val format = MediaFormat.createAudioFormat(mime, SAMPLE_RATE, 1).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, if (opus) OPUS_BITRATE else AAC_BITRATE)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, pcm.size.coerceAtMost(16_384))
                if (!opus) {
                    setInteger(
                        MediaFormat.KEY_AAC_PROFILE,
                        MediaCodecInfo.CodecProfileLevel.AACObjectLC
                    )
                }
            }
            codec = MediaCodec.createEncoderByType(mime)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            temp = File.createTempFile(if (opus) "retry-opus" else "retry-aac", if (opus) ".ogg" else ".m4a")
            val outputFormat = if (opus && sdkInt >= Build.VERSION_CODES.Q) {
                MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG
            } else {
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
            }
            muxer = MediaMuxer(temp.absolutePath, outputFormat)
            muxPcm(codec, muxer, pcm)
            val bytes = temp.readBytes()
            if (bytes.size < 32) return null
            return if (opus) {
                EncodedAudio(bytes, "audio/ogg", "ogg")
            } else {
                EncodedAudio(bytes, "audio/m4a", "m4a")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Retry encode failed: ${t.javaClass.simpleName}")
            return null
        } finally {
            try {
                muxer?.stop()
            } catch (_: Exception) {
            }
            try {
                muxer?.release()
            } catch (_: Exception) {
            }
            try {
                codec?.stop()
            } catch (_: Exception) {
            }
            try {
                codec?.release()
            } catch (_: Exception) {
            }
            temp?.delete()
        }
    }

    private fun muxPcm(codec: MediaCodec, muxer: MediaMuxer, pcm: ByteArray) {
        val info = MediaCodec.BufferInfo()
        var offset = 0
        var inputDone = false
        var muxerStarted = false
        var track = -1
        var spins = 0
        while (spins < MAX_SPINS) {
            spins++
            if (!inputDone) {
                val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                if (inIndex >= 0) {
                    val input = codec.getInputBuffer(inIndex) ?: continue
                    input.clear()
                    val remaining = pcm.size - offset
                    if (remaining <= 0) {
                        codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        val count = minOf(input.remaining(), remaining)
                        input.put(pcm, offset, count)
                        val timeUs = offset * 1_000_000L / (SAMPLE_RATE * 2)
                        offset += count
                        codec.queueInputBuffer(inIndex, 0, count, timeUs, 0)
                    }
                }
            }
            val outIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)
            when {
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (!muxerStarted) {
                        track = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    }
                }
                outIndex >= 0 -> {
                    val output = codec.getOutputBuffer(outIndex)
                    if (output != null && info.size > 0 && muxerStarted &&
                        info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
                    ) {
                        output.position(info.offset)
                        output.limit(info.offset + info.size)
                        muxer.writeSampleData(track, output, info)
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    companion object {
        private const val TAG = "RetryAudioEncoder"
        private const val SAMPLE_RATE = 16_000
        private const val OPUS_BITRATE = 24_000
        private const val AAC_BITRATE = 32_000
        private const val TIMEOUT_US = 10_000L
        private const val MAX_SPINS = 100_000

        fun pcmPayload(wavBytes: ByteArray): ByteArray {
            if (wavBytes.size <= WAV_HEADER) return ByteArray(0)
            val data = wavBytes.copyOfRange(WAV_HEADER, wavBytes.size)
            return if (data.size % 2 == 0) data else data.copyOf(data.size - 1)
        }

        private const val WAV_HEADER = 44
    }
}
