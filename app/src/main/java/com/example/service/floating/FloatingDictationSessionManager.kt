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

    private val finalizedTranscript = StringBuilder()
    private var interimTranscript = ""

    private val audioRecorder = AudioRecorder(
        onChunkReady = { chunk ->
            if (isRecording) {
                audioQueue.offer(chunk)
                val ws = webSocketClient
                if (ws != null && ws.setupComplete) {
                    while (audioQueue.isNotEmpty()) {
                        val c = audioQueue.poll() ?: break
                        val sent = ws.sendAudioChunk(c)
                        if (sent) {
                            sessionChunksSent++
                            sessionBytesSent += c.size
                        }
                    }
                }
            }
        },
        onAmplitudeChanged = { amp ->
            if (isRecording) {
                onAmplitudeChanged(amp.coerceIn(0f, 1f))
            } else {
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

    fun startSession(
        context: Context,
        scope: CoroutineScope,
        apiKey: String,
        model: String,
        smartMode: Boolean,
        aecEnabled: Boolean,
        noiseSuppressorEnabled: Boolean
    ) {
        if (isRecording) return
        isRecording = true

        finalizedTranscript.clear()
        interimTranscript = ""
        durationSeconds = 0
        sessionChunksSent = 0
        sessionBytesSent = 0L
        audioQueue.clear()

        val modeLabel = if (smartMode) "SMART" else "VERBATIM"
        AppLogRepository.logEvent(
            DiagnosticSource.BUBBLE,
            DiagnosticType.SESSION_START,
            "Mode: $modeLabel, Model: $model"
        )

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
            aecEnabled = aecEnabled,
            noiseSuppressorEnabled = noiseSuppressorEnabled,
            source = DiagnosticSource.BUBBLE
        )

        // Start WebSocket
        webSocketClient = GeminiLiveWebSocketClient(
            onSetupComplete = {
                val ws = webSocketClient
                if (ws != null && ws.setupComplete) {
                    while (audioQueue.isNotEmpty()) {
                        audioQueue.poll()?.let { ws.sendAudioChunk(it) }
                    }
                }
            },
            onInterimTranscription = { text ->
                interimTranscript = text
                onInterimReceived(text)
                onTranscriptUpdated(getFullTranscriptText())
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
            },
            onStateChanged = { state ->
                onConnectionStateChanged(state)
            },
            onLog = { _, _, _, _ -> },
            onError = { err ->
                Log.e(TAG, "WebSocket error: $err")
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

        val ws = webSocketClient
        webSocketClient = null
        val wl = wakeLock
        wakeLock = null

        scope.launch(Dispatchers.IO) {
            try {
                audioRecorder.stop()
                ws?.signalStreamEnd()
                delay(400)
                ws?.disconnect()
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping audio/websocket", e)
            } finally {
                AppLogRepository.logEvent(
                    DiagnosticSource.BUBBLE,
                    DiagnosticType.SESSION_END,
                    "ended_reason: $endedReason | Duration: ${durationAtEnd}s, Chunks: $chunksAtEnd, Streamed: ${bytesAtEnd / 1024} KB"
                )
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
