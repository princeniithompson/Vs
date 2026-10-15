package com.example.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.example.data.DiagnosticSource
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
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

sealed class AudioRecorderState {
    object Idle : AudioRecorderState()
    object Starting : AudioRecorderState()
    object Recording : AudioRecorderState()
    object Stopping : AudioRecorderState()
    data class Error(val message: String) : AudioRecorderState()
}

/**
 * Whisperflow-style production audio capture pipeline for VoxStream.
 *
 * Characteristics:
 * - Direct AudioSource = MediaRecorder.AudioSource.MIC (value 1)
 * - 16 kHz sample rate, mono channel, 16-bit linear PCM
 * - No hardware audio effects (AEC / NS / AGC) in the capture path
 * - No software filters mutating the PCM bytes before Gemini Live
 * - Clean 100 ms (3200 bytes) raw PCM packets delivered to [onChunkReady]
 * - Smooth volume/energy calculation for UI visualizer without filtering audio
 * - HeadSilenceTracker for analytics / diagnostics without muting speech
 * - Rock-solid start/stop/release lifecycle preventing HAL microphone leaks
 */
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
        // 100ms at 16kHz 16-bit mono PCM = 16000 * 0.1 * 2 = 3200 bytes
        const val CHUNK_SIZE_BYTES = 3200
        // Slices for responsive 50Hz amplitude visualizer updates (20ms = 640 bytes)
        private const val SLICE_SIZE_BYTES = 640
        // Head silence baseline energy threshold (0.0 .. 1.0)
        private const val HEAD_SILENCE_THRESHOLD = 0.04f
    }

    private var audioRecord: AudioRecord? = null
    @Volatile private var recordingJob: Job? = null
    private val isRecording = AtomicBoolean(false)

    private val _state = MutableStateFlow<AudioRecorderState>(AudioRecorderState.Idle)
    val state: StateFlow<AudioRecorderState> = _state.asStateFlow()

    // HeadSilenceTracker metrics (analytics only, zero audio manipulation)
    @Volatile var headSilenceDurationMs: Long = 0L
        private set

    fun isFullyReleased(): Boolean = _state.value is AudioRecorderState.Idle && !isRecording.get()

    @SuppressLint("MissingPermission")
    fun start(
        scope: CoroutineScope,
        source: DiagnosticSource = DiagnosticSource.APP
    ) {
        if (isRecording.getAndSet(true)) {
            Log.w(TAG, "Recording already in progress, restarting session cleanly")
            stop()
        }

        _state.value = AudioRecorderState.Starting
        headSilenceDurationMs = 0L

        // Optional local diagnostics WAV recording session
        try {
            com.example.data.AudioRecordingRepository.startRecordingSession(source)
        } catch (t: Throwable) {
            Log.w(TAG, "Failed starting WAV diagnostics session: ${t.message}")
        }

        recordingJob = scope.launch(Dispatchers.IO) {
            val minBufferSize = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT
            )

            if (minBufferSize <= 0) {
                val err = "AudioRecord initialization error: Invalid minBufferSize ($minBufferSize)"
                Log.e(TAG, err)
                _state.value = AudioRecorderState.Error(err)
                onError(err)
                isRecording.set(false)
                return@launch
            }

            // Size internal buffer for smooth capture
            val internalBufferSize = maxOf(minBufferSize * 2, CHUNK_SIZE_BYTES * 2)

            var recorder: AudioRecord? = null
            try {
                // Whisperflow production setup: Direct Hardware MIC (AudioSource.MIC = 1)
                recorder = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT,
                    internalBufferSize
                )

                if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                    val err = "Microphone initialization failed with AudioSource.MIC"
                    Log.e(TAG, err)
                    _state.value = AudioRecorderState.Error(err)
                    onError(err)
                    recorder.release()
                    isRecording.set(false)
                    return@launch
                }

                audioRecord = recorder
                recorder.startRecording()
                _state.value = AudioRecorderState.Recording

                Log.i(TAG, "Whisperflow pipeline active: AudioSource.MIC, 16kHz mono 16-bit PCM, raw transmission")

                val chunkBuffer = ByteArray(CHUNK_SIZE_BYTES)
                var bytesReadTotal = 0

                var initialSilenceCount = 0
                var firstSpeechDetected = false

                while (isActive && isRecording.get()) {
                    val bytesToRead = minOf(SLICE_SIZE_BYTES, CHUNK_SIZE_BYTES - bytesReadTotal)
                    val readResult = recorder.read(
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
                        if (isRecording.get()) {
                            Log.e(TAG, "AudioRecord read error: $errorName")
                            onError("AudioRecord read failure: $errorName")
                        }
                        break
                    }

                    if (readResult > 0) {
                        // Calculate volume meter for UI visualizer without modifying PCM data
                        val sliceAmplitude = calculateMeterValue(chunkBuffer, bytesReadTotal, readResult)
                        onAmplitudeChanged(sliceAmplitude)

                        bytesReadTotal += readResult
                    }

                    if (bytesReadTotal >= CHUNK_SIZE_BYTES) {
                        // Complete 100ms packet ready
                        val rawPacket = chunkBuffer.copyOf()
                        bytesReadTotal = 0

                        // HeadSilenceTracker: observe initial silence before voice onset
                        val packetEnergy = calculateMeterValue(rawPacket, 0, rawPacket.size)
                        if (!firstSpeechDetected) {
                            if (packetEnergy < HEAD_SILENCE_THRESHOLD) {
                                initialSilenceCount++
                                headSilenceDurationMs = initialSilenceCount * 100L
                            } else {
                                firstSpeechDetected = true
                                Log.d(TAG, "HeadSilenceTracker: Voice detected after ${headSilenceDurationMs}ms")
                            }
                        }

                        // Save raw PCM to optional local WAV
                        try {
                            com.example.data.AudioRecordingRepository.appendAudioChunk(rawPacket)
                        } catch (t: Throwable) {
                            Log.w(TAG, "AudioRecordingRepository append error: ${t.message}")
                        }

                        // Send 100ms raw PCM to Gemini Live callback - ZERO filters, ZERO gating
                        onChunkReady(rawPacket)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exception in AudioRecorder loop: ${e.message}", e)
                if (isRecording.get()) {
                    onError("Microphone error: ${e.message ?: e.javaClass.simpleName}")
                }
            } finally {
                withContext(NonCancellable) {
                    releaseRecorderSafely(recorder)
                }
            }
        }
    }

    /**
     * Terminate the recording loop and unblock any pending read call.
     */
    fun stop() {
        if (!isRecording.getAndSet(false)) {
            return
        }
        _state.value = AudioRecorderState.Stopping
        try {
            audioRecord?.let {
                if (it.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    it.stop()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping AudioRecord: ${e.message}")
        }
    }

    /**
     * Stop recording and wait for background thread release to complete.
     */
    suspend fun stopAndJoin() {
        stop()
        val job = recordingJob
        if (job != null && job.isActive) {
            try {
                job.join()
            } catch (_: Exception) {}
        }
        recordingJob = null
        _state.value = AudioRecorderState.Idle
    }

    private fun releaseRecorderSafely(recorder: AudioRecord?) {
        isRecording.set(false)

        try {
            com.example.data.AudioRecordingRepository.stopRecordingSession()
        } catch (_: Exception) {}

        try {
            recorder?.apply {
                if (recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    stop()
                }
                release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing AudioRecord: ${e.message}")
        } finally {
            audioRecord = null
            _state.value = AudioRecorderState.Idle
            onAmplitudeChanged(0f)
            Log.d(TAG, "Microphone released cleanly")
        }
    }

    /**
     * Whisperflow-style volume / energy meter calculation for UI visualizers.
     * Calculates the RMS of 16-bit linear PCM samples and normalizes to [0.0 .. 1.0].
     * Purely analytical; does not modify or gate audio in any way.
     */
    private fun calculateMeterValue(pcmBytes: ByteArray, offset: Int = 0, length: Int = pcmBytes.size): Float {
        val sampleCount = length / 2
        if (sampleCount <= 0) return 0f

        var sumSquares = 0.0
        for (i in 0 until sampleCount) {
            val idx = offset + i * 2
            val low = pcmBytes[idx].toInt() and 0xFF
            val high = pcmBytes[idx + 1].toInt()
            val sample = (high shl 8) or low
            sumSquares += sample.toDouble() * sample.toDouble()
        }

        val rms = sqrt(sumSquares / sampleCount)
        val normalized = (rms / 3200.0).coerceIn(0.0, 1.0)
        return (sqrt(normalized).toFloat() * 1.25f).coerceIn(0f, 1f)
    }
}
