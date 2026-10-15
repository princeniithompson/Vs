package com.example.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sin

/**
 * High-performance Kaldi Log-Mel Filterbank Feature Extractor for WeSpeaker TFLite embedding model.
 *
 * Specifications matching HuggingFace litert-community/Speaker-Diarization-LiteRT:
 * - Sampling rate: 16,000 Hz
 * - Frame length: 25.0 ms (400 samples)
 * - Frame shift: 10.0 ms (160 samples)
 * - Window: Hamming window
 * - Pre-emphasis: 0.97
 * - FFT size: 512 points
 * - Mel bins: 80 bins (20 Hz to 8000 Hz)
 * - Number of frames: 500 frames (80,240 samples = 5.015 s)
 * - CMN (Cepstral Mean Normalization) applied across the 500 frames
 * - Output shape: [1, 500, 80] Float32
 */
class KaldiFbankExtractor {

    companion object {
        const val SAMPLE_RATE = 16000
        const val FRAME_LENGTH = 400
        const val FRAME_SHIFT = 160
        const val FFT_SIZE = 512
        const val NUM_MEL_BINS = 80
        const val NUM_FRAMES = 500
        const val TARGET_SAMPLES = (NUM_FRAMES - 1) * FRAME_SHIFT + FRAME_LENGTH // 80,240 samples
        const val LOW_FREQ = 20.0
        const val HIGH_FREQ = 8000.0
        const val PREEMPH_COEFF = 0.97f

        private fun mel(f: Double): Double = 1127.0 * ln(1.0 + f / 700.0)
        private fun invMel(m: Double): Double = 700.0 * (exp(m / 1127.0) - 1.0)
    }

    // Precomputed Hamming window
    private val hammingWindow = FloatArray(FRAME_LENGTH) { n ->
        (0.54 - 0.46 * cos(2.0 * PI * n / (FRAME_LENGTH - 1))).toFloat()
    }

    // Precomputed Mel filterbank sparse triangular bins
    private data class MelBinWeight(val binIndex: Int, val weight: Float)
    private val melFilterWeights = Array(NUM_MEL_BINS) { ArrayList<MelBinWeight>() }

    // Precomputed FFT bit reversal table and twiddle factors
    private val bitRev = IntArray(FFT_SIZE)
    private val cosTable = FloatArray(FFT_SIZE / 2)
    private val sinTable = FloatArray(FFT_SIZE / 2)

    // Reusable buffers to avoid heap allocations during audio feature extraction
    private val paddedAudio = FloatArray(TARGET_SAMPLES)
    private val fftReal = FloatArray(FFT_SIZE)
    private val fftImag = FloatArray(FFT_SIZE)
    private val powerSpectrum = FloatArray(FFT_SIZE / 2 + 1)
    private val fbankFrames = Array(NUM_FRAMES) { FloatArray(NUM_MEL_BINS) }

    init {
        initMelFilters()
        initFftTables()
    }

    private fun initMelFilters() {
        val lowMel = mel(LOW_FREQ)
        val highMel = mel(HIGH_FREQ)
        val binPoints = IntArray(NUM_MEL_BINS + 2)

        for (i in 0 until NUM_MEL_BINS + 2) {
            val melVal = lowMel + i * (highMel - lowMel) / (NUM_MEL_BINS + 1)
            val hzVal = invMel(melVal)
            val bin = ((FFT_SIZE + 1) * hzVal / SAMPLE_RATE).toInt().coerceIn(0, FFT_SIZE / 2)
            binPoints[i] = bin
        }

        for (m in 0 until NUM_MEL_BINS) {
            val left = binPoints[m]
            val center = max(left + 1, binPoints[m + 1])
            val right = max(center + 1, binPoints[m + 2])

            val list = melFilterWeights[m]
            list.clear()

            for (k in left..right) {
                if (k in 0..(FFT_SIZE / 2)) {
                    val weight = if (k < center) {
                        (k - left).toFloat() / max(1, center - left)
                    } else {
                        (right - k).toFloat() / max(1, right - center)
                    }
                    if (weight > 0f) {
                        list.add(MelBinWeight(k, weight))
                    }
                }
            }
        }
    }

    private fun initFftTables() {
        val levels = 9 // 2^9 = 512
        for (i in 0 until FFT_SIZE) {
            var rev = 0
            var temp = i
            for (j in 0 until levels) {
                rev = (rev shl 1) or (temp and 1)
                temp = temp shr 1
            }
            bitRev[i] = rev
        }

        for (i in 0 until FFT_SIZE / 2) {
            val angle = -2.0 * PI * i / FFT_SIZE
            cosTable[i] = cos(angle).toFloat()
            sinTable[i] = sin(angle).toFloat()
        }
    }

    private fun computeFft(real: FloatArray, imag: FloatArray) {
        // Bit-reversal permutation
        for (i in 0 until FFT_SIZE) {
            val j = bitRev[i]
            if (i < j) {
                val tempR = real[i]
                real[i] = real[j]
                real[j] = tempR
                val tempI = imag[i]
                imag[i] = imag[j]
                imag[j] = tempI
            }
        }

        // Cooley-Tukey Radix-2 decimation-in-time
        var halfSize = 1
        while (halfSize < FFT_SIZE) {
            val step = halfSize shl 1
            val tableStep = (FFT_SIZE / 2) / halfSize
            for (i in 0 until FFT_SIZE step step) {
                for (j in 0 until halfSize) {
                    val k = j * tableStep
                    val c = cosTable[k]
                    val s = sinTable[k]

                    val idxA = i + j
                    val idxB = idxA + halfSize

                    val tr = c * real[idxB] - s * imag[idxB]
                    val ti = c * imag[idxB] + s * real[idxB]

                    real[idxB] = real[idxA] - tr
                    imag[idxB] = imag[idxA] - ti
                    real[idxA] += tr
                    imag[idxA] += ti
                }
            }
            halfSize = step
        }
    }

    /**
     * Extracts Kaldi 80-bin log-mel filterbank features with CMN across 500 frames.
     * Takes raw 16-bit PCM samples, tile-pads to 80,240 samples if shorter.
     * Writes into direct ByteBuffer formatted for TFLite [1, 500, 80] Float32 tensor.
     */
    @Synchronized
    fun extractFbankCmnToBuffer(pcmSamples: ShortArray, outputBuffer: ByteBuffer) {
        val nSamples = pcmSamples.size
        if (nSamples == 0) return

        // 1. Tile-pad / repeat audio to fill target 80,240 samples
        for (i in 0 until TARGET_SAMPLES) {
            paddedAudio[i] = pcmSamples[i % nSamples].toFloat()
        }

        // 2. Compute 80 log-mel energies for each of 500 frames
        for (frameIdx in 0 until NUM_FRAMES) {
            val frameStart = frameIdx * FRAME_SHIFT

            // Zero imaginary and FFT buffer
            for (i in 0 until FFT_SIZE) {
                fftReal[i] = 0f
                fftImag[i] = 0f
            }

            // Extract 400 samples with pre-emphasis and Hamming window
            for (n in 0 until FRAME_LENGTH) {
                val curr = paddedAudio[frameStart + n]
                val prev = if (frameStart + n > 0) paddedAudio[frameStart + n - 1] else curr
                val preemph = curr - PREEMPH_COEFF * prev
                fftReal[n] = preemph * hammingWindow[n]
            }

            // 512-point FFT
            computeFft(fftReal, fftImag)

            // Power spectrum for bins 0..256
            for (k in 0..(FFT_SIZE / 2)) {
                powerSpectrum[k] = fftReal[k] * fftReal[k] + fftImag[k] * fftImag[k]
            }

            // 80 Mel filterbank energies
            val frameMel = fbankFrames[frameIdx]
            for (m in 0 until NUM_MEL_BINS) {
                val weights = melFilterWeights[m]
                var energy = 0f
                for (w in weights) {
                    energy += powerSpectrum[w.binIndex] * w.weight
                }
                // Kaldi natural log floor
                frameMel[m] = ln(max(energy, 1e-10f))
            }
        }

        // 3. CMN (Cepstral Mean Normalization) across 500 frames
        for (m in 0 until NUM_MEL_BINS) {
            var sum = 0f
            for (frameIdx in 0 until NUM_FRAMES) {
                sum += fbankFrames[frameIdx][m]
            }
            val mean = sum / NUM_FRAMES.toFloat()
            for (frameIdx in 0 until NUM_FRAMES) {
                fbankFrames[frameIdx][m] -= mean
            }
        }

        // 4. Fill output ByteBuffer in [1, 500, 80] order
        outputBuffer.clear()
        outputBuffer.order(ByteOrder.nativeOrder())
        for (frameIdx in 0 until NUM_FRAMES) {
            val frameMel = fbankFrames[frameIdx]
            for (m in 0 until NUM_MEL_BINS) {
                outputBuffer.putFloat(frameMel[m])
            }
        }
        outputBuffer.flip()
    }
}
