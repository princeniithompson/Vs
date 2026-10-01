package com.example.audio

import kotlin.math.PI
import kotlin.math.roundToInt

/**
 * High-performance, zero-allocation first-order IIR High-Pass Filter for 16kHz 16-bit mono PCM.
 * Cuts frequencies below 120Hz to eliminate fan hum, air conditioner rumble, and low-frequency
 * room noise while keeping vocal fundamentals and speech dynamics completely intact and delay-free.
 */
class HighPassFilter(
    val cutoffHz: Float = 120f,
    val sampleRate: Float = 16000f
) {
    // alpha = 1 / (1 + 2 * PI * cutoff / sampleRate)
    private val alpha: Float = run {
        val dt = 1f / sampleRate
        val rc = 1f / (2f * PI.toFloat() * cutoffHz)
        rc / (rc + dt)
    }

    private var prevInput: Float = 0f
    private var prevOutput: Float = 0f

    /**
     * Resets filter history at the start of a new audio recording session.
     */
    fun reset() {
        prevInput = 0f
        prevOutput = 0f
    }

    /**
     * Processes signed 16-bit PCM little-endian byte array in-place.
     * Guaranteed zero heap allocation per call for real-time streaming safety.
     */
    fun process(pcmBytes: ByteArray, offset: Int = 0, length: Int = pcmBytes.size) {
        if (length < 2) return
        var i = offset
        val end = offset + length - 1
        var xPrev = prevInput
        var yPrev = prevOutput
        val a = alpha

        while (i < end) {
            val b0 = pcmBytes[i].toInt() and 0xFF
            val b1 = pcmBytes[i + 1].toInt()
            val sample = (b1 shl 8) or b0 // Signed 16-bit PCM
            val x = sample.toFloat()

            // y[n] = alpha * (y[n-1] + x[n] - x[n-1])
            val y = a * (yPrev + x - xPrev)
            xPrev = x
            yPrev = y

            val clamped = y.roundToInt().coerceIn(-32768, 32767)
            pcmBytes[i] = (clamped and 0xFF).toByte()
            pcmBytes[i + 1] = ((clamped shr 8) and 0xFF).toByte()

            i += 2
        }

        prevInput = xPrev
        prevOutput = yPrev
    }
}
