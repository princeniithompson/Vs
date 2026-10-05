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
import com.example.data.LogLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

sealed class AudioRecorderState {
    object Idle : AudioRecorderState()
    object Starting : AudioRecorderState()
    object Recording : AudioRecorderState()
    object Stopping : AudioRecorderState()
    data class Error(val message: String) : AudioRecorderState()
}

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
    @Volatile private var recordingJob: Job? = null
    private val isRecording = AtomicBoolean(false)

    private val _state = MutableStateFlow<AudioRecorderState>(AudioRecorderState.Idle)
    val state: StateFlow<AudioRecorderState> = _state.asStateFlow()

    // Real-time gentle 75Hz IIR High-Pass Filter: reduces low-frequency rumble while preserving natural voice body
    private val highPassFilter = HighPassFilter(cutoffHz = 75f, sampleRate = SAMPLE_RATE.toFloat())

    // Noise environment metrics
    private var currentSource: DiagnosticSource = DiagnosticSource.APP
    private var sessionSampleCount = 0
    private var sessionAmplitudeSum = 0.0
    private var sessionElevatedNoiseCount = 0

    fun isFullyReleased(): Boolean = _state.value is AudioRecorderState.Idle && !isRecording.get()

    @SuppressLint("MissingPermission")
    fun start(
        scope: CoroutineScope,
        source: DiagnosticSource = DiagnosticSource.APP
    ) {
        if (isRecording.getAndSet(true)) {
            Log.w(TAG, "Recording already in progress, restarting session cleanly")
            recordingJob?.cancel()
            cleanUpInternal()
            isRecording.set(true)
        }

        _state.value = AudioRecorderState.Starting
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
                val err = "AudioRecord buffer error: Invalid minBufferSize ($minBufferSize) for 16kHz mono 16-bit PCM"
                _state.value = AudioRecorderState.Error(err)
                onError(err)
                isRecording.set(false)
                return@launch
            }

            // Size the internal buffer for low-latency capture: minBufferSize * 2
            val internalBufferSize = maxOf(minBufferSize * 2, CHUNK_SIZE_BYTES * 2)

            try {
                // Diagnostic Baseline: Prioritize VOICE_RECOGNITION tuned for natural speech recognition
                val candidateSources = mutableListOf(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    MediaRecorder.AudioSource.MIC,
                    MediaRecorder.AudioSource.DEFAULT,
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION
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
                    val err = "Microphone initialization failed. Please check microphone permission or hardware."
                    _state.value = AudioRecorderState.Error(err)
                    onError(err)
                    isRecording.set(false)
                    rec?.release()
                    audioRecord = null
                    return@launch
                }

                audioRecord = rec

                // Dedicated Clean Voice Baseline: Hardware AEC, NoiseSuppressor, and AGC are intentionally
                // disabled to capture pure, uncompressed vocal acoustics without clipping whispered speech.

                rec.startRecording()
                _state.value = AudioRecorderState.Recording
                val hpfCutoff = highPassFilter.cutoffHz.toInt()
                Log.d(TAG, "AudioRecord started recording at 16000Hz PCM 16-bit (Audio diagnostic baseline: VOICE_RECOGNITION, AEC/NS/AGC disabled, gentle HPF=${hpfCutoff}Hz)")
                AppLogRepository.addLog(
                    LogLevel.INFO,
                    TAG,
                    "Audio diagnostic baseline: VOICE_RECOGNITION, AEC/NS/AGC disabled, gentle HPF=${hpfCutoff}Hz"
                )

                val chunkBuffer = ByteArray(CHUNK_SIZE_BYTES)
                var bytesReadTotal = 0
                val sliceSizeBytes = 640 // 20ms at 16kHz 16-bit mono for instant attack & 50Hz visualizer updates

                while (isActive && isRecording.get()) {
                    val bytesToRead = minOf(sliceSizeBytes, CHUNK_SIZE_BYTES - bytesReadTotal)
                    val readResult = rec.read(
                        chunkBuffer,
                        bytesReadTotal,
                        bytesToRead
                    )

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
                        // Gentle 75Hz High-Pass Filter applied in-place to reduce low-frequency environmental rumble while preserving natural voice body
                        highPassFilter.process(chunkBuffer, bytesReadTotal, readResult)

                        // Amplitude dispatch (every 20ms) from processed audio
                        val instantAmp = calculateRmsAmplitude(chunkBuffer, bytesReadTotal, readResult)
                        onAmplitudeChanged(instantAmp)

                        // Track ambient noise conditions
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
                        try {
                            com.example.data.AudioRecordingRepository.appendAudioChunk(readyChunk)
                        } catch (t: Throwable) {
                            Log.w(TAG, "AudioRecordingRepository append error: ${t.message}")
                        }

                        // Deliver filtered 100ms PCM chunk to WebSocket / Gemini
                        onChunkReady(readyChunk)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in AudioRecord loop", e)
                onError("Microphone error: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                withContext(NonCancellable) {
                    cleanUpInternal()
                }
            }
        }
    }

    /**
     * Non-blocking stop signal. Signals the recording loop to terminate.
     * The recording coroutine will exit cleanly and run cleanUpInternal() in its finally block.
     */
    fun stop() {
        isRecording.set(false)
        _state.value = AudioRecorderState.Stopping
    }

    /**
     * Suspending stop that signals the loop to exit and awaits the definitive completion
     * of native AudioRecord and audio effect release.
     */
    suspend fun stopAndJoin() {
        isRecording.set(false)
        _state.value = AudioRecorderState.Stopping
        val job = recordingJob
        if (job != null && job.isActive) {
            try {
                job.join()
            } catch (_: Exception) {}
        }
        recordingJob = null
        _state.value = AudioRecorderState.Idle
    }

    private fun checkAndLogNoiseConditions() {
        val count = sessionSampleCount
        if (count >= 40) { // At least ~800ms of audio recorded
            val avgAmp = (sessionAmplitudeSum / count).toFloat()
            val elevatedRatio = sessionElevatedNoiseCount.toFloat() / count
            if (avgAmp > 0.26f && elevatedRatio > 0.40f) {
                val pct = (elevatedRatio * 100).toInt()
                val warn = "Noisy environment detected (avg amplitude: ${String.format(Locale.US, "%.2f", avgAmp)}, $pct% elevated baseline noise). High-pass 75Hz; hardware NS/AGC/AEC disabled."
                Log.w(TAG, warn)
                AppLogRepository.logEvent(currentSource, DiagnosticType.NOISY_ENVIRONMENT, warn)
            }
        }
        sessionSampleCount = 0
        sessionAmplitudeSum = 0.0
        sessionElevatedNoiseCount = 0
    }

    /**
     * Internal cleanup strictly invoked on the recording thread inside the coroutine's finally block.
     * This guarantees that read() has finished before release() is called, preventing HAL deadlocks.
     */
    private fun cleanUpInternal() {
        isRecording.set(false)
        checkAndLogNoiseConditions()
        try {
            com.example.data.AudioRecordingRepository.stopRecordingSession()
        } catch (_: Exception) {}

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
            _state.value = AudioRecorderState.Idle
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

        val noiseFloor = 8.0
        val effectiveRms = (rms - noiseFloor).coerceAtLeast(0.0)
        if (effectiveRms <= 0.0) return 0f

        val normalizedLinear = (effectiveRms / 900.0).coerceIn(0.0, 1.0)
        val boosted = sqrt(normalizedLinear).toFloat()

        return (boosted * 1.55f).coerceIn(0f, 1f)
    }
}

