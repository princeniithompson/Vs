package com.example.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import com.example.data.AppLogRepository
import com.example.data.DiagnosticSource
import com.example.data.DiagnosticType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

class AudioRecorder(
    private val onChunkReady: (ByteArray) -> Unit,
    private val onAmplitudeChanged: (Float) -> Unit,
    private val onError: (String) -> Unit
) {
    companion object {
        const val TAG = "AudioRecorder"
        const val SAMPLE_RATE = 16000
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        // 100ms of 16kHz 16-bit mono PCM = 16000 samples/sec * 0.1 sec * 2 bytes/sample = 3200 bytes
        const val CHUNK_SIZE_BYTES = 3200
    }

    private var audioRecord: AudioRecord? = null
    private var acousticEchoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var automaticGainControl: AutomaticGainControl? = null
    private var recordingJob: Job? = null
    private val isRecording = AtomicBoolean(false)

    // Real-time 120Hz IIR High-Pass Filter: cuts fan rumble and sub-bass background noise
    private val highPassFilter = HighPassFilter(cutoffHz = 120f, sampleRate = SAMPLE_RATE.toFloat())

    // Noise environment metrics
    private var currentSource: DiagnosticSource = DiagnosticSource.APP
    private var sessionSampleCount = 0
    private var sessionAmplitudeSum = 0.0
    private var sessionElevatedNoiseCount = 0

    @SuppressLint("MissingPermission")
    fun start(
        scope: CoroutineScope,
        aecEnabled: Boolean = true,
        noiseSuppressorEnabled: Boolean = true,
        source: DiagnosticSource = DiagnosticSource.APP
    ) {
        if (isRecording.getAndSet(true)) {
            Log.w(TAG, "Recording already in progress")
            return
        }

        currentSource = source
        sessionSampleCount = 0
        sessionAmplitudeSum = 0.0
        sessionElevatedNoiseCount = 0
        highPassFilter.reset()

        // Start background WAV recording file if enabled in settings
        com.example.data.AudioRecordingRepository.startRecordingSession(source)

        recordingJob = scope.launch(Dispatchers.IO) {
            val minBufferSize = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT
            )

            if (minBufferSize == AudioRecord.ERROR || minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
                onError("AudioRecord buffer error: Invalid minBufferSize ($minBufferSize) for 16kHz mono 16-bit PCM")
                isRecording.set(false)
                return@launch
            }

            // Size the internal buffer to accommodate at least several chunks or minBufferSize
            val internalBufferSize = maxOf(minBufferSize, CHUNK_SIZE_BYTES * 4)

            try {
                val candidateSources = mutableListOf(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    MediaRecorder.AudioSource.MIC,
                    MediaRecorder.AudioSource.DEFAULT
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    candidateSources.add(MediaRecorder.AudioSource.UNPROCESSED)
                }

                var rec: AudioRecord? = null
                for (sourceType in candidateSources) {
                    try {
                        val candidate = AudioRecord(
                            sourceType,
                            SAMPLE_RATE,
                            CHANNEL_CONFIG,
                            AUDIO_FORMAT,
                            internalBufferSize
                        )
                        if (candidate.state == AudioRecord.STATE_INITIALIZED) {
                            rec = candidate
                            Log.d(TAG, "Successfully initialized AudioRecord with source: $sourceType")
                            break
                        } else {
                            candidate.release()
                        }
                    } catch (t: Throwable) {
                        Log.w(TAG, "Failed creating AudioRecord with source $sourceType: ${t.message}")
                    }
                }

                if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) {
                    onError("Microphone initialization failed. Please check microphone permission or hardware.")
                    isRecording.set(false)
                    rec?.release()
                    audioRecord = null
                    return@launch
                }

                audioRecord = rec

                val sessionId = audioRecord?.audioSessionId ?: 0
                if (sessionId != 0) {
                    // 1. Acoustic Echo Canceler
                    if (aecEnabled) {
                        try {
                            if (AcousticEchoCanceler.isAvailable()) {
                                acousticEchoCanceler = AcousticEchoCanceler.create(sessionId)?.apply {
                                    enabled = true
                                }
                                Log.d(TAG, "AcousticEchoCanceler enabled: ${acousticEchoCanceler?.enabled}")
                            } else {
                                Log.d(TAG, "AcousticEchoCanceler is not available on this device")
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to enable AcousticEchoCanceler: ${e.message}", e)
                        }
                    }

                    // 2. Noise Suppressor (Verify actual engagement & log warning if unavailable/failed)
                    if (noiseSuppressorEnabled) {
                        val nsAvailable = try {
                            NoiseSuppressor.isAvailable()
                        } catch (e: Exception) {
                            false
                        }
                        Log.d(TAG, "NoiseSuppressor isAvailable: $nsAvailable")

                        if (!nsAvailable) {
                            val warn = "NoiseSuppressor hardware effect is unavailable on this device"
                            Log.w(TAG, warn)
                            AppLogRepository.logEvent(currentSource, DiagnosticType.WARNING, warn)
                        } else {
                            try {
                                noiseSuppressor = NoiseSuppressor.create(sessionId)?.apply {
                                    enabled = true
                                }
                                val isNsActuallyEnabled = noiseSuppressor?.enabled == true
                                Log.d(TAG, "NoiseSuppressor enabled check: $isNsActuallyEnabled")
                                if (!isNsActuallyEnabled) {
                                    val warn = "NoiseSuppressor instance created but failed to enable (.enabled is false)"
                                    Log.w(TAG, warn)
                                    AppLogRepository.logEvent(currentSource, DiagnosticType.WARNING, warn)
                                }
                            } catch (e: Exception) {
                                val warn = "Failed to enable NoiseSuppressor: ${e.message}"
                                Log.w(TAG, warn, e)
                                AppLogRepository.logEvent(currentSource, DiagnosticType.WARNING, warn)
                            }
                        }
                    }

                    // 3. Automatic Gain Control (Normalizes speech level relative to background noise)
                    try {
                        val agcAvailable = try {
                            AutomaticGainControl.isAvailable()
                        } catch (e: Exception) {
                            false
                        }
                        Log.d(TAG, "AutomaticGainControl isAvailable: $agcAvailable")

                        if (agcAvailable) {
                            automaticGainControl = AutomaticGainControl.create(sessionId)?.apply {
                                enabled = true
                            }
                            val isAgcActuallyEnabled = automaticGainControl?.enabled == true
                            Log.d(TAG, "AutomaticGainControl enabled check: $isAgcActuallyEnabled")
                        } else {
                            Log.d(TAG, "AutomaticGainControl is not available on this hardware")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to enable AutomaticGainControl: ${e.message}", e)
                    }
                }

                audioRecord?.startRecording()
                Log.d(TAG, "AudioRecord started recording at 16000Hz PCM 16-bit (HighPass=120Hz, AGC, NS, AEC active)")

                val chunkBuffer = ByteArray(CHUNK_SIZE_BYTES)
                var bytesReadTotal = 0
                val sliceSizeBytes = 640 // 20ms at 16kHz 16-bit mono for instant attack & 50Hz visualizer updates

                while (isActive && isRecording.get()) {
                    val bytesToRead = minOf(sliceSizeBytes, CHUNK_SIZE_BYTES - bytesReadTotal)
                    val readResult = audioRecord?.read(
                        chunkBuffer,
                        bytesReadTotal,
                        bytesToRead
                    ) ?: -1

                    if (readResult < 0) {
                        val errorName = when (readResult) {
                            AudioRecord.ERROR_INVALID_OPERATION -> "ERROR_INVALID_OPERATION (-3)"
                            AudioRecord.ERROR_BAD_VALUE -> "ERROR_BAD_VALUE (-2)"
                            AudioRecord.ERROR_DEAD_OBJECT -> "ERROR_DEAD_OBJECT (-6)"
                            AudioRecord.ERROR -> "ERROR (-1)"
                            else -> "Code $readResult"
                        }
                        Log.e(TAG, "AudioRecord read error: $errorName")
                        onError("AudioRecord read failure: $errorName")
                        break
                    }

                    if (readResult > 0) {
                        // A. Apply lightweight 120Hz High-Pass Filter in-place on newly read PCM slice
                        highPassFilter.process(chunkBuffer, bytesReadTotal, readResult)

                        // B. Instant, high-frequency amplitude dispatch (every 20ms) from filtered audio
                        val instantAmp = calculateRmsAmplitude(chunkBuffer, bytesReadTotal, readResult)
                        onAmplitudeChanged(instantAmp)

                        // C. Track ambient noise conditions
                        sessionSampleCount++
                        sessionAmplitudeSum += instantAmp
                        if (instantAmp > 0.22f) {
                            sessionElevatedNoiseCount++
                        }
                    }

                    bytesReadTotal += readResult

                    if (bytesReadTotal >= CHUNK_SIZE_BYTES) {
                        // We have a full 100ms chunk
                        val readyChunk = chunkBuffer.copyOf()
                        bytesReadTotal = 0

                        // Save processed PCM chunk to WAV file
                        com.example.data.AudioRecordingRepository.appendAudioChunk(readyChunk)

                        // Deliver filtered 100ms PCM chunk to WebSocket / Gemini
                        onChunkReady(readyChunk)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in AudioRecord loop", e)
                onError("Microphone error: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                cleanUp()
            }
        }
    }

    fun stop() {
        if (!isRecording.getAndSet(false)) return
        CoroutineScope(Dispatchers.IO).launch {
            cleanUp()
        }
    }

    private fun checkAndLogNoiseConditions() {
        val count = sessionSampleCount
        if (count >= 40) { // At least ~800ms of audio recorded
            val avgAmp = (sessionAmplitudeSum / count).toFloat()
            val elevatedRatio = sessionElevatedNoiseCount.toFloat() / count
            // High sustained ambient floor heuristic (e.g. fan or AC continuously elevated)
            if (avgAmp > 0.26f && elevatedRatio > 0.40f) {
                val pct = (elevatedRatio * 100).toInt()
                val warn = "Noisy environment detected (avg amplitude: ${String.format(Locale.US, "%.2f", avgAmp)}, $pct% elevated baseline noise). High-pass 120Hz & noise suppression applied."
                Log.w(TAG, warn)
                AppLogRepository.logEvent(currentSource, DiagnosticType.NOISY_ENVIRONMENT, warn)
            }
        }
        sessionSampleCount = 0
        sessionAmplitudeSum = 0.0
        sessionElevatedNoiseCount = 0
    }

    private fun cleanUp() {
        checkAndLogNoiseConditions()
        com.example.data.AudioRecordingRepository.stopRecordingSession()

        try {
            acousticEchoCanceler?.apply {
                enabled = false
                release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing AcousticEchoCanceler", e)
        } finally {
            acousticEchoCanceler = null
        }

        try {
            noiseSuppressor?.apply {
                enabled = false
                release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing NoiseSuppressor", e)
        } finally {
            noiseSuppressor = null
        }

        try {
            automaticGainControl?.apply {
                enabled = false
                release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing AutomaticGainControl", e)
        } finally {
            automaticGainControl = null
        }

        try {
            audioRecord?.apply {
                if (recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    stop()
                }
                release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing AudioRecord", e)
        } finally {
            audioRecord = null
            recordingJob?.cancel()
            recordingJob = null
            onAmplitudeChanged(0f)
        }
    }

    private fun calculateRmsAmplitude(pcmBytes: ByteArray, offset: Int = 0, length: Int = pcmBytes.size): Float {
        if (length <= 0) return 0f
        val sampleCount = length / 2
        if (sampleCount == 0) return 0f

        val shortBuffer = ByteBuffer.wrap(pcmBytes, offset, length)
            .order(ByteOrder.LITTLE_ENDIAN)
            .asShortBuffer()

        var sumSquares = 0.0
        while (shortBuffer.hasRemaining()) {
            val sample = shortBuffer.get().toDouble()
            sumSquares += sample * sample
        }

        val rms = sqrt(sumSquares / sampleCount)

        // Lower noise floor so quieter speech still moves the wave and glow
        val noiseFloor = 8.0
        val effectiveRms = (rms - noiseFloor).coerceAtLeast(0.0)
        if (effectiveRms <= 0.0) return 0f

        // More aggressive scaling for the visualizer only
        val normalizedLinear = (effectiveRms / 900.0).coerceIn(0.0, 1.0)
        val boosted = sqrt(normalizedLinear).toFloat()

        // Extra visual boost (does NOT affect the PCM sent to Gemini)
        return (boosted * 1.55f).coerceIn(0f, 1f)
    }
}

