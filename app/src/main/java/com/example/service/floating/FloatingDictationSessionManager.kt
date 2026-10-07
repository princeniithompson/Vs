package com.example.service.floating

import android.content.Context
import android.os.PowerManager
import android.util.Log
import com.example.audio.AudioRecorder
import com.example.data.AppLogRepository
import com.example.data.ConnectionState
import com.example.data.CustomVocabularyRepository
import com.example.data.DiagnosticSource
import com.example.data.DiagnosticType
import com.example.data.LiveStats
import com.example.data.LogLevel
import com.example.websocket.GeminiLiveWebSocketClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentLinkedQueue

class FloatingDictationSessionManager(
    private val onTranscriptUpdated: (fullText: String) -> Unit,
    private val onInterimReceived: (interim: String) -> Unit,
    private val onFinalSegmentReceived: (segment: String) -> Unit,
    private val onConnectionStateChanged: (state: ConnectionState) -> Unit,
    private val onAmplitudeChanged: (amplitude: Float) -> Unit,
    private val onError: (errorMsg: String) -> Unit,
    private val onDurationTicked: (seconds: Int) -> Unit
) {
    companion object {
        private const val TAG = "FloatingDictationMgr"
        private const val MAX_AUDIO_QUEUE_CHUNKS = 50 // ~5s buffer at 100ms chunks to bridge reconnects
        // Syllable duration threshold: Human speech/whisper requires at least 3 consecutive 20ms frames (~60ms)
        private const val MIN_SPEECH_FRAMES = 3
        // Minimum speech amplitude above ambient noise floor (tuned for close-proximity whispering: ~0.030)
        private const val SPEECH_DELTA_THRESHOLD = 0.016f
        private const val MIN_ABSOLUTE_SPEECH_AMP = 0.030f

        // VAD Gating & Hangover configuration
        private const val VAD_HANGOVER_MS = 700L
        private const val ZERO_PADDING_KEEP_ALIVE_INTERVAL_MS = 1000L
        private const val GATED_DIAGNOSTIC_LOG_INTERVAL_MS = 5000L
    }

    private val audioQueue = ConcurrentLinkedQueue<ByteArray>()
    private var webSocketClient: GeminiLiveWebSocketClient? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private var durationJob: Job? = null
    var durationSeconds: Int = 0
        private set

    var isRecording: Boolean = false
        private set

    var sessionChunksSent: Int = 0
        private set
    var sessionBytesSent: Long = 0L
        private set
    var sessionGatedChunksCount: Int = 0
        private set

    private val finalizedTranscript = StringBuilder()
    private var interimTranscript = ""

    // VAD & Silence tracking state: separates sustained speech from brief ambient clatter/transients
    private var baselineNoiseFloor = 0.018f
    private var consecutiveSpeechFrames = 0
    private var smoothedAmp = 0f
    private var lastSustainedSpeechTimestamp = 0L

    // VAD Gating timing & diagnostics tracking
    private var lastZeroPaddingTimestamp = 0L
    private var continuousGatedStartTimestamp = 0L
    private var lastGatedLogTimestamp = 0L

    var isSpeechActive: Boolean = false
        private set

    val silenceDurationMs: Long
        get() {
            if (!isRecording) return 0L
            val lastSpeech = lastSustainedSpeechTimestamp
            return if (lastSpeech > 0L) (System.currentTimeMillis() - lastSpeech).coerceAtLeast(0L) else 0L
        }

    private val audioRecorder = AudioRecorder(
        onChunkReady = { chunk ->
            if (isRecording) {
                val now = System.currentTimeMillis()
                val isHangoverActive = lastSustainedSpeechTimestamp > 0L &&
                    (now - lastSustainedSpeechTimestamp) < VAD_HANGOVER_MS
                val shouldStreamSpeech = isSpeechActive || isHangoverActive

                if (shouldStreamSpeech) {
                    // Reset continuous non-speech gating counters when speech is streaming
                    continuousGatedStartTimestamp = 0L
                    lastGatedLogTimestamp = 0L

                    val ws = webSocketClient
                    if (ws != null && ws.setupComplete) {
                        val sent = ws.sendAudioChunk(chunk)
                        if (sent) {
                            sessionChunksSent++
                            sessionBytesSent += chunk.size
                            AppLogRepository.updateLiveStats {
                                it.copy(
                                    source = DiagnosticSource.BUBBLE,
                                    chunksSent = sessionChunksSent,
                                    bytesSent = sessionBytesSent,
                                    chunksBuffered = audioQueue.size
                                )
                            }
                        } else {
                            enqueueAudioChunk(chunk)
                        }
                    } else {
                        enqueueAudioChunk(chunk)
                    }
                } else {
                    // Speech is inactive: Gate out raw noise chunks (do NOT send raw noise or enqueue to WebSocket)
                    sessionGatedChunksCount++

                    val ws = webSocketClient
                    // Send zero-padding chunk at most once every 1000ms to keep Gemini Live session warm
                    if (ws != null && ws.setupComplete) {
                        if (now - lastZeroPaddingTimestamp >= ZERO_PADDING_KEEP_ALIVE_INTERVAL_MS) {
                            lastZeroPaddingTimestamp = now
                            ws.sendZeroPaddingChunk()
                        }
                    }

                    // Track continuous non-speech audio and emit diagnostic log every 5 seconds
                    if (continuousGatedStartTimestamp == 0L) {
                        continuousGatedStartTimestamp = now
                        lastGatedLogTimestamp = now
                    } else {
                        val continuousGatedDurationMs = now - continuousGatedStartTimestamp
                        if (now - lastGatedLogTimestamp >= GATED_DIAGNOSTIC_LOG_INTERVAL_MS) {
                            lastGatedLogTimestamp = now
                            val gatedSecs = (continuousGatedDurationMs / 1000L).coerceAtLeast(5L)
                            val noiseFloorFormatted = String.format(java.util.Locale.US, "%.3f", baselineNoiseFloor)
                            val diagnosticMsg = "[VAD Gating] Continuous non-speech audio gated for ${gatedSecs}s | noiseFloor: $noiseFloorFormatted | keepAlive: active"
                            Log.i(TAG, diagnosticMsg)
                            AppLogRepository.logEvent(
                                DiagnosticSource.BUBBLE,
                                DiagnosticType.NOISY_ENVIRONMENT,
                                diagnosticMsg
                            )
                            AppLogRepository.addLog(
                                LogLevel.INFO,
                                TAG,
                                diagnosticMsg,
                                payload = null,
                                source = DiagnosticSource.BUBBLE
                            )
                        }
                    }
                }
            }
        },
        onAmplitudeChanged = { rawAmp ->
            if (isRecording) {
                val clamped = rawAmp.coerceIn(0f, 1f)

                // 1. Slow adaptation of ambient noise floor during quiet periods
                if (!isSpeechActive && clamped < 0.05f) {
                    baselineNoiseFloor = (baselineNoiseFloor * 0.98f) + (clamped * 0.02f)
                }

                // 2. Check if frame exceeds vocal speech threshold (including close whisper)
                val speechThreshold = maxOf(MIN_ABSOLUTE_SPEECH_AMP, baselineNoiseFloor + SPEECH_DELTA_THRESHOLD)
                val isAboveThreshold = clamped >= speechThreshold

                if (isAboveThreshold) {
                    consecutiveSpeechFrames++
                    if (consecutiveSpeechFrames >= MIN_SPEECH_FRAMES) {
                        isSpeechActive = true
                        lastSustainedSpeechTimestamp = System.currentTimeMillis()
                    }
                } else {
                    // Frame below speech threshold
                    if (consecutiveSpeechFrames > 0) {
                        consecutiveSpeechFrames = 0
                    }
                    if (isSpeechActive) {
                        isSpeechActive = false
                    }
                }

                // 3. Transient dampening for visualizer:
                // An isolated transient spike (< 60ms) without sustained speech does not jerk the visualizer to max
                val targetAmp = if (isSpeechActive || isAboveThreshold) {
                    clamped
                } else {
                    clamped.coerceAtMost(0.04f)
                }
                smoothedAmp = (smoothedAmp * 0.7f) + (targetAmp * 0.3f)
                onAmplitudeChanged(smoothedAmp.coerceIn(0f, 1f))
            } else {
                smoothedAmp = 0f
                consecutiveSpeechFrames = 0
                isSpeechActive = false
                onAmplitudeChanged(0f)
            }
        },
        onError = { err ->
            Log.e(TAG, "AudioRecorder error: $err")
            AppLogRepository.logEvent(
                DiagnosticSource.BUBBLE,
                DiagnosticType.ERROR,
                "AudioRecorder: $err"
            )
        }
    )

    private fun enqueueAudioChunk(chunk: ByteArray) {
        while (audioQueue.size >= MAX_AUDIO_QUEUE_CHUNKS) {
            audioQueue.poll()
        }
        audioQueue.offer(chunk)
        AppLogRepository.updateLiveStats {
            it.copy(
                source = DiagnosticSource.BUBBLE,
                chunksBuffered = audioQueue.size
            )
        }
    }

    fun startSession(
        context: Context,
        scope: CoroutineScope,
        apiKey: String,
        model: String,
        smartMode: Boolean
    ) {
        if (isRecording) return
        isRecording = true

        finalizedTranscript.clear()
        interimTranscript = ""
        durationSeconds = 0
        sessionChunksSent = 0
        sessionBytesSent = 0L
        sessionGatedChunksCount = 0
        audioQueue.clear()

        baselineNoiseFloor = 0.018f
        consecutiveSpeechFrames = 0
        smoothedAmp = 0f
        isSpeechActive = false
        lastSustainedSpeechTimestamp = 0L
        lastZeroPaddingTimestamp = 0L
        continuousGatedStartTimestamp = 0L
        lastGatedLogTimestamp = 0L

        val modeLabel = if (smartMode) "SMART" else "VERBATIM"
        AppLogRepository.logEvent(
            DiagnosticSource.BUBBLE,
            DiagnosticType.SESSION_START,
            "Mode: $modeLabel, Model: $model"
        )
        AppLogRepository.updateConnectionState(ConnectionState.Connecting, DiagnosticSource.BUBBLE)
        AppLogRepository.updateLiveStats {
            LiveStats(
                source = DiagnosticSource.BUBBLE,
                chunksBuffered = 0,
                chunksSent = 0,
                bytesSent = 0L,
                setupCompleted = false,
                interimCount = 0,
                finalizedCount = 0,
                lastError = null
            )
        }

        // Acquire WakeLock for up to 30 min
        try {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            if (wakeLock?.isHeld == true) {
                try { wakeLock?.release() } catch (_: Exception) {}
            }
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "voxstream:floating_dictation_wakelock"
            ).apply {
                acquire(30 * 60 * 1000L)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not acquire WakeLock", e)
        }

        // Start Audio Recorder
        audioRecorder.start(
            scope,
            source = DiagnosticSource.BUBBLE
        )

        // Start WebSocket
        webSocketClient = GeminiLiveWebSocketClient(
            onSetupComplete = {
                AppLogRepository.updateLiveStats {
                    it.copy(
                        source = DiagnosticSource.BUBBLE,
                        setupCompleted = true
                    )
                }
                val ws = webSocketClient
                if (ws != null && ws.setupComplete) {
                    scope.launch(Dispatchers.IO) {
                        while (isActive && ws.setupComplete && audioQueue.isNotEmpty()) {
                            val chunk = audioQueue.poll() ?: break
                            val sent = ws.sendAudioChunk(chunk)
                            if (sent) {
                                sessionChunksSent++
                                sessionBytesSent += chunk.size
                                AppLogRepository.updateLiveStats {
                                    it.copy(
                                        source = DiagnosticSource.BUBBLE,
                                        chunksSent = sessionChunksSent,
                                        bytesSent = sessionBytesSent,
                                        chunksBuffered = audioQueue.size
                                    )
                                }
                                if (audioQueue.isNotEmpty()) {
                                    delay(25)
                                }
                            }
                        }
                    }
                }
            },
            onInterimTranscription = { text ->
                interimTranscript = text
                onInterimReceived(text)
                onTranscriptUpdated(getFullTranscriptText())
                AppLogRepository.updateLiveStats {
                    it.copy(
                        source = DiagnosticSource.BUBBLE,
                        interimCount = it.interimCount + 1
                    )
                }
            },
            onFinalizedTranscription = { text ->
                val trimmed = text.trim()
                if (trimmed.isNotEmpty()) {
                    AppLogRepository.logEvent(
                        DiagnosticSource.BUBBLE,
                        DiagnosticType.TRANSCRIPT_FINAL,
                        trimmed
                    )
                }
                if (finalizedTranscript.isNotEmpty() && !finalizedTranscript.endsWith(" ")) {
                    finalizedTranscript.append(" ")
                }
                finalizedTranscript.append(text)
                interimTranscript = ""
                onFinalSegmentReceived(text)
                onTranscriptUpdated(getFullTranscriptText())
                AppLogRepository.updateLiveStats {
                    it.copy(
                        source = DiagnosticSource.BUBBLE,
                        finalizedCount = it.finalizedCount + 1
                    )
                }
            },
            onStateChanged = { state ->
                onConnectionStateChanged(state)
                AppLogRepository.updateConnectionState(state, DiagnosticSource.BUBBLE)
            },
            onLog = { level, tag, msg, payload ->
                AppLogRepository.addLog(level, tag, msg, payload, DiagnosticSource.BUBBLE)
            },
            onError = { err ->
                Log.e(TAG, "WebSocket error: $err")
                AppLogRepository.updateLiveStats { it.copy(lastError = err) }
                onError(err)
            }
        ).apply {
            val customVocab = CustomVocabularyRepository.getVocabulary()
            try {
                connect(apiKey = apiKey, model = model, smartMode = smartMode, customVocabulary = customVocab)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to connect WebSocket: ${e.message}")
                onError(e.message ?: "Connection error")
            }
        }

        // Duration timer
        durationJob?.cancel()
        durationJob = scope.launch {
            while (isActive && isRecording) {
                delay(1000)
                durationSeconds++
                onDurationTicked(durationSeconds)
            }
        }
    }

    fun stopSession(scope: CoroutineScope, endedReason: String = "completed") {
        if (!isRecording) return
        isRecording = false

        durationJob?.cancel()
        durationJob = null

        val durationAtEnd = durationSeconds
        val chunksAtEnd = sessionChunksSent
        val bytesAtEnd = sessionBytesSent
        val gatedChunksAtEnd = sessionGatedChunksCount

        val ws = webSocketClient
        webSocketClient = null
        val wl = wakeLock
        wakeLock = null

        scope.launch(Dispatchers.IO) {
            try {
                audioRecorder.stopAndJoin()
                ws?.signalStreamEnd()
                delay(400)
                ws?.disconnect()
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping audio/websocket", e)
            } finally {
                AppLogRepository.logEvent(
                    DiagnosticSource.BUBBLE,
                    DiagnosticType.SESSION_END,
                    "ended_reason: $endedReason | Duration: ${durationAtEnd}s, Chunks: $chunksAtEnd, Gated: $gatedChunksAtEnd, Streamed: ${bytesAtEnd / 1024} KB"
                )
                AppLogRepository.updateConnectionState(ConnectionState.Idle, DiagnosticSource.BUBBLE)
                try {
                    if (wl?.isHeld == true) {
                        wl.release()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error releasing WakeLock", e)
                }
            }
        }
    }

    fun signalStreamEndOnly(scope: CoroutineScope) {
        if (isRecording) {
            isRecording = false
            durationJob?.cancel()
            durationJob = null
            audioRecorder.stop()
            val ws = webSocketClient
            scope.launch(Dispatchers.IO) {
                try {
                    ws?.signalStreamEnd()
                } catch (e: Exception) {
                    Log.e(TAG, "Error signaling stream end", e)
                }
            }
        }
    }

    fun getFullTranscriptText(): String {
        val sb = StringBuilder()
        if (finalizedTranscript.isNotEmpty()) {
            sb.append(finalizedTranscript)
        }
        if (interimTranscript.isNotEmpty()) {
            if (sb.isNotEmpty() && !sb.endsWith(" ")) {
                sb.append(" ")
            }
            sb.append(interimTranscript)
        }
        return sb.toString().trim()
    }

    fun clearTranscripts() {
        finalizedTranscript.clear()
        interimTranscript = ""
        onTranscriptUpdated("")
    }

    fun release() {
        isRecording = false
        durationJob?.cancel()
        durationJob = null
        try {
            audioRecorder.stop()
            webSocketClient?.disconnect()
            webSocketClient = null
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
            wakeLock = null
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing session manager", e)
        }
    }
}
