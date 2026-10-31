package com.princeniithompson.rapcha.data

/**
 * Drops leading and trailing quiet PCM, leaving a short pad so the first and
 * last words are not clipped. If nothing looks like speech, the buffer is kept.
 */
object PcmSilenceTrim {
    const val PAD_MS = 300
    private const val FRAME_MS = 20
    private const val SPEECH_RMS = 450

    fun trim(pcm: ByteArray, sampleRate: Int = RetryClipStore.SAMPLE_RATE, padMs: Int = PAD_MS): ByteArray {
        if (pcm.size < 4 || sampleRate <= 0) return pcm
        val even = pcm.size - (pcm.size % 2)
        val frameBytes = (sampleRate * FRAME_MS / 1000) * 2
        if (frameBytes <= 0 || even < frameBytes) return pcm
        var firstSpeech = -1
        var lastSpeech = -1
        var offset = 0
        var frame = 0
        while (offset + frameBytes <= even) {
            if (rms(pcm, offset, frameBytes) >= SPEECH_RMS) {
                if (firstSpeech < 0) firstSpeech = frame
                lastSpeech = frame
            }
            offset += frameBytes
            frame++
        }
        if (firstSpeech < 0 || lastSpeech < 0) return pcm.copyOf(even)
        val padFrames = (padMs + FRAME_MS - 1) / FRAME_MS
        val startFrame = (firstSpeech - padFrames).coerceAtLeast(0)
        val endFrame = (lastSpeech + padFrames).coerceAtMost(frame - 1)
        val start = startFrame * frameBytes
        val end = ((endFrame + 1) * frameBytes).coerceAtMost(even)
        if (end <= start) return pcm.copyOf(even)
        return pcm.copyOfRange(start, end)
    }

    private fun rms(pcm: ByteArray, offset: Int, length: Int): Int {
        val samples = length / 2
        if (samples <= 0) return 0
        var sum = 0.0
        var index = offset
        repeat(samples) {
            val low = pcm[index].toInt() and 0xFF
            val high = pcm[index + 1].toInt()
            val sample = (high shl 8) or low
            sum += sample.toDouble() * sample.toDouble()
            index += 2
        }
        return kotlin.math.sqrt(sum / samples).toInt()
    }
}
