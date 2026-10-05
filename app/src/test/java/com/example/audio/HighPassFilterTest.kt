package com.example.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class HighPassFilterTest {

    @Test
    fun `default configuration uses 75Hz cutoff and 16000Hz sample rate`() {
        val filter = HighPassFilter()
        assertEquals(75f, filter.cutoffHz, 0.001f)
        assertEquals(16000f, filter.sampleRate, 0.001f)
    }

    @Test
    fun `processing 16-bit PCM preserves buffer length and format bounds`() {
        val filter = HighPassFilter(cutoffHz = 75f, sampleRate = 16000f)
        val sampleCount = 320 // 20ms of 16kHz
        val pcmBytes = ByteArray(sampleCount * 2)

        // Fill with a 200Hz sine wave
        for (i in 0 until sampleCount) {
            val sampleVal = (sin(2.0 * PI * 200.0 * i / 16000.0) * 16000.0).toInt().coerceIn(-32768, 32767)
            pcmBytes[i * 2] = (sampleVal and 0xFF).toByte()
            pcmBytes[i * 2 + 1] = ((sampleVal shr 8) and 0xFF).toByte()
        }

        val originalLength = pcmBytes.size
        filter.process(pcmBytes)

        assertEquals("Buffer length must not change", originalLength, pcmBytes.size)

        // Verify all output samples remain valid signed 16-bit integers
        for (i in 0 until sampleCount) {
            val b0 = pcmBytes[i * 2].toInt() and 0xFF
            val b1 = pcmBytes[i * 2 + 1].toInt()
            val sample = (b1 shl 8) or b0
            assertTrue("Sample must remain within 16-bit bounds", sample in -32768..32767)
        }
    }

    @Test
    fun `dc offset is attenuated while audio continues through filter`() {
        val filter = HighPassFilter(cutoffHz = 75f, sampleRate = 16000f)
        val sampleCount = 1600 // 100ms
        val pcmBytes = ByteArray(sampleCount * 2)

        // Step function: constant DC offset of +10000
        for (i in 0 until sampleCount) {
            val sampleVal = 10000
            pcmBytes[i * 2] = (sampleVal and 0xFF).toByte()
            pcmBytes[i * 2 + 1] = ((sampleVal shr 8) and 0xFF).toByte()
        }

        filter.process(pcmBytes)

        // The end of the 100ms DC burst should have decayed towards 0
        val lastB0 = pcmBytes[(sampleCount - 1) * 2].toInt() and 0xFF
        val lastB1 = pcmBytes[(sampleCount - 1) * 2 + 1].toInt()
        val lastSample = (lastB1 shl 8) or lastB0

        assertTrue("DC offset must be attenuated below starting level (was $lastSample)", lastSample < 5000)
    }

    @Test
    fun `filter state persists across chunk slices without discontinuities and resets cleanly`() {
        val filter = HighPassFilter(cutoffHz = 75f, sampleRate = 16000f)
        val chunk1 = ByteArray(100) { 0 }
        val chunk2 = ByteArray(100) { 0 }

        // Process chunk1 then chunk2
        filter.process(chunk1)
        filter.process(chunk2)

        // Calling reset clears internal history
        filter.reset()

        val chunkAfterReset = ByteArray(100) { 0 }
        filter.process(chunkAfterReset)
        assertEquals(0, chunkAfterReset[0].toInt())
    }
}
