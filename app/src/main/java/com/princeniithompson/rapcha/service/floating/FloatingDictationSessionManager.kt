package com.princeniithompson.rapcha.service.floating

import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import com.princeniithompson.rapcha.audio.AudioRecorder
import com.princeniithompson.rapcha.data.AppLogRepository
import com.princeniithompson.rapcha.data.ConnectionState
import com.princeniithompson.rapcha.data.CustomVocabularyRepository
import com.princeniithompson.rapcha.data.DiagnosticSource
import com.princeniithompson.rapcha.data.DiagnosticType
import com.princeniithompson.rapcha.data.LiveStats
import com.princeniithompson.rapcha.data.LogLevel
import com.princeniithompson.rapcha.data.RetryClipStore
import com.princeniithompson.rapcha.data.TranscriptPrivacy
import com.princeniithompson.rapcha.service.FloatingBubbleManager
import com.princeniithompson.rapcha.websocket.DictationPiecePlanner
import com.princeniithompson.rapcha.websocket.DictationSessionClock
import com.princeniithompson.rapcha.websocket.GeminiLiveWebSocketClient
import com.princeniithompson.rapcha.websocket.InterimPreviewMonitor
import com.princeniithompson.rapcha.websocket.TranscriptSeam
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

class FloatingDictationSessionManager(
    private val context: Context,
    private val onTranscriptUpdated: (fullText: String) -> Unit,
    private val onInterimReceived: (interim: String) -> Unit,
    private val onFinalSegmentReceived: (segment: String) -> Unit,
    private val onConnectionStateChanged: (state: ConnectionState) -> Unit,
    private val onAmplitudeChanged: (amplitude: Float) -> Unit,
    private val onError: (errorMsg: String) -> Unit,
    private val onDurationTicked: (seconds: Int) -> Unit,
    private val onSessionLimit: (message: String, finalizeNow: Boolean) -> Unit = { _, _ -> },
    private val onPreviewPhase: (InterimPreviewMonitor.Phase) -> Unit = {}
) {
    companion object {
        private const val TAG = "FloatingDictationMgr"
        private const val MAX_AUDIO_QUEUE_CHUNKS = 50 // ~5s buffer at 100ms chunks to bridge reconnects

        internal fun elapsedSeconds(startedAtElapsed: Long, nowElapsed: Long): Int {
            if (startedAtElapsed <= 0L) return 0
            return ((nowElapsed - startedAtElapsed).coerceAtLeast(0L) / 1000L).toInt()
        }
    }

    private var sessionStartedAtElapsed = 0L

    private val audioQueue = ConcurrentLinkedQueue<ByteArray>()
    private var webSocketClient: GeminiLiveWebSocketClient? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private var sessionJob: CompletableJob? = null
    private var sessionScope: CoroutineScope? = null
    private var drainJob: Job? = null

    private var durationJob: Job? = null
    private var retryStopJob: Job? = null

    @Volatile
    private var liveAbandoned = false
    private val sessionEndLogged = AtomicBoolean(false)
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
    private var sessionClock = DictationSessionClock()
    private var finishAtNextPause = false

    private var smoothedAmp = 0f
    private var lastSustainedSpeechTimestamp = 0L

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
                try {
                    RetryClipStore.append(chunk)
                } catch (t: Throwable) {
                    Log.w(TAG, "Retry clip append skipped: ${t.javaClass.simpleName}")
                }
                val ws = if (liveAbandoned) null else webSocketClient
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
            }
        },
        onAmplitudeChanged = { rawAmp ->
            if (isRecording) {
                val clamped = rawAmp.coerceIn(0f, 1f)
                val now = System.currentTimeMillis()
                if (clamped >= 0.03f) {
                    isSpeechActive = true
                    lastSustainedSpeechTimestamp = now
                } else if (now - lastSustainedSpeechTimestamp > 700L) {
                    isSpeechActive = false
                }
                val smoothingFactor = if (clamped > smoothedAmp) 0.45f else 0.25f
                smoothedAmp = (smoothedAmp * (1f - smoothingFactor)) + (clamped * smoothingFactor)
                onAmplitudeChanged(smoothedAmp.coerceIn(0f, 1f))
            } else {
                smoothedAmp = 0f
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
            DictationAlerts.microphoneFailed(context)
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

    private fun drainAudioQueue(ws: GeminiLiveWebSocketClient, scope: CoroutineScope) {
        drainJob?.cancel()
        drainJob = scope.launch(Dispatchers.IO) {
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
                        delay(20)
                    }
                } else {
                    enqueueAudioChunk(chunk)
                    break
                }
            }
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
        liveAbandoned = false
        sessionEndLogged.set(false)

        sessionJob?.cancel()
        val sJob = SupervisorJob()
        sessionJob = sJob
        val currentSessionScope = CoroutineScope(Dispatchers.Main + sJob)
        sessionScope = currentSessionScope

        FloatingBubbleManager.setNetworkProblem(false)

        finalizedTranscript.clear()
        interimTranscript = ""
        sessionClock = DictationSessionClock()
        finishAtNextPause = false
        sessionStartedAtElapsed = SystemClock.elapsedRealtime()
        durationSeconds = 0
        sessionChunksSent = 0
        sessionBytesSent = 0L
        audioQueue.clear()

        smoothedAmp = 0f
        isSpeechActive = false
        lastSustainedSpeechTimestamp = 0L

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

        try {
            RetryClipStore.begin(context)
        } catch (t: Throwable) {
            Log.w(TAG, "Retry clip not started: ${t.javaClass.simpleName}")
        }

        // Start Audio Recorder tied to session scope
        audioRecorder.start(
            currentSessionScope,
            source = DiagnosticSource.BUBBLE
        )

        // Start WebSocket
        webSocketClient = GeminiLiveWebSocketClient(
            onSetupComplete = {
                FloatingBubbleManager.setNetworkProblem(false)
                AppLogRepository.updateLiveStats {
                    it.copy(
                        source = DiagnosticSource.BUBBLE,
                        setupCompleted = true
                    )
                }
                val ws = webSocketClient
                if (ws != null && ws.setupComplete) {
                    ws.signalActivityStart()
                    drainAudioQueue(ws, currentSessionScope)
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
            onPieceCommitted = { piece ->
                appendPiece(piece)
                interimTranscript = ""
                onTranscriptUpdated(getFullTranscriptText())
            },
            onGoAway = {
                refreshDurationFromClock()
                if (sessionClock.shouldSplitOnGoAway(durationSeconds * 1000L)) {
                    webSocketClient?.requestFreshConnectionSplit()
                } else if (isRecording) {
                    onSessionLimit("Session ended at 10 minutes.", true)
                }
            },
            onAudiblePause = {
                if (finishAtNextPause && isRecording) {
                    onSessionLimit("Session ended at 10 minutes.", true)
                }
            },
            onFinalizedTranscription = { text ->
                val trimmed = text.trim()
                if (trimmed.isNotEmpty()) {
                    AppLogRepository.logEvent(
                        DiagnosticSource.BUBBLE,
                        DiagnosticType.TRANSCRIPT_FINAL,
                        TranscriptPrivacy.summary(trimmed)
                    )
                }
                appendPiece(trimmed)
                interimTranscript = ""
                onFinalSegmentReceived(trimmed)
                onTranscriptUpdated(getFullTranscriptText())
                AppLogRepository.updateLiveStats {
                    it.copy(
                        source = DiagnosticSource.BUBBLE,
                        finalizedCount = it.finalizedCount + 1
                    )
                }
            },
            onStateChanged = { state ->
                val isProblem = (state is ConnectionState.Error) ||
                    (state is ConnectionState.Connecting && (webSocketClient?.isReconnecting == true))
                FloatingBubbleManager.setNetworkProblem(isProblem)
                onConnectionStateChanged(state)
                AppLogRepository.updateConnectionState(state, DiagnosticSource.BUBBLE)
            },
            onLog = { level, tag, msg, payload ->
                AppLogRepository.addLog(level, tag, msg, payload, DiagnosticSource.BUBBLE)
            },
            onError = { err ->
                Log.e(TAG, "WebSocket error: $err")
                AppLogRepository.updateLiveStats { it.copy(lastError = err) }
                FloatingBubbleManager.setNetworkProblem(true)
                onError(err)
            },
            isSessionActive = { isRecording },
            diagnosticSource = DiagnosticSource.BUBBLE,
            onPreviewPhase = onPreviewPhase
        ).apply {
            val customVocab = CustomVocabularyRepository.getVocabulary()
            try {
                connect(apiKey = apiKey, model = model, smartMode = smartMode, customVocabulary = customVocab)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to connect WebSocket: ${e.message}")
                FloatingBubbleManager.setNetworkProblem(true)
                onError(e.message ?: "Connection error")
            }
        }

        // Duration timer tied to session scope
        durationJob?.cancel()
        durationJob = currentSessionScope.launch {
            while (isActive && isRecording) {
                delay(1000)
                refreshDurationFromClock()
                webSocketClient?.pollInterimPreview()
                onSessionClockTick()
                onDurationTicked(durationSeconds)
            }
        }
    }

    private fun refreshDurationFromClock() {
        durationSeconds = elapsedSeconds(sessionStartedAtElapsed, SystemClock.elapsedRealtime())
    }

    /**
     * Drop Gemini Live for this session and cancel reconnects.
     * The microphone and the retry WAV keep running.
     */
    fun abandonLiveKeepMicrophone() {
        if (liveAbandoned) return
        liveAbandoned = true
        val ws = webSocketClient
        webSocketClient = null
        try {
            ws?.disconnect()
        } catch (t: Throwable) {
            Log.w(TAG, "Live cancel failed: ${t.javaClass.simpleName}")
        }
        AppLogRepository.logEvent(
            DiagnosticSource.BUBBLE,
            DiagnosticType.RETRY_LIVE_CANCELLED,
            "reconnect_cancelled"
        )
    }

    fun stopSession(
        scope: CoroutineScope,
        endedReason: String = "completed",
        discardRetryClip: Boolean = false
    ) {
        if (!isRecording) return
        isRecording = false
        val retryClipId = RetryClipStore.currentId()
        FloatingBubbleManager.setNetworkProblem(false)

        durationJob?.cancel()
        durationJob = null
        drainJob?.cancel()
        drainJob = null

        refreshDurationFromClock()
        val durationAtEnd = durationSeconds
        val chunksAtEnd = sessionChunksSent
        val bytesAtEnd = sessionBytesSent

        val ws = webSocketClient
        webSocketClient = null
        val wl = wakeLock
        wakeLock = null

        sessionJob?.cancel()
        sessionJob = null
        sessionScope = null

        retryStopJob = scope.launch(Dispatchers.IO) {
            try {
                audioRecorder.stopAndJoin()
                ws?.disconnect()
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping audio/websocket", e)
            } finally {
                try {
                    if (discardRetryClip) {
                        RetryClipStore.discard(retryClipId)
                    } else {
                        RetryClipStore.finalizeIfCurrent(retryClipId)
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "Retry clip settle failed: ${t.javaClass.simpleName}")
                }
                logSessionEndOnce(
                    "ended_reason: $endedReason | Duration: ${durationAtEnd}s, Chunks: $chunksAtEnd, Streamed: ${bytesAtEnd / 1024} KB | ${ws?.sessionSummary().orEmpty()}"
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

    /**
     * Wait until the in-flight stop has closed the retry WAV.
     * Safe to call when the session was already stopped.
     */
    suspend fun awaitRetryClip() {
        retryStopJob?.join()
    }

    fun settleRetryClip(discard: Boolean) {
        val id = RetryClipStore.currentId()
        try {
            if (discard) RetryClipStore.discard(id) else RetryClipStore.finalizeIfCurrent(id)
        } catch (t: Throwable) {
            Log.w(TAG, "Retry clip settle failed: ${t.javaClass.simpleName}")
        }
    }

    /**
     * Drop the microphone before any network wait. Safe Mode calls this first
     * so the privacy indicator does not stay up while the final transcript arrives.
     */
    suspend fun releaseMicrophoneNow() {
        durationJob?.cancel()
        durationJob = null
        drainJob?.cancel()
        drainJob = null
        audioRecorder.stopAndJoin()
        isRecording = false
    }

    /**
     * User pressed Complete. Stop the mic, send activityEnd, and wait for the
     * authoritative transcript. Pauses never reach this path.
     */
    suspend fun finishActivityAndAwaitTranscript(): String {
        if (!isRecording && webSocketClient == null) return getFullTranscriptText()
        releaseMicrophoneNow()

        refreshDurationFromClock()
        val durationAtEnd = durationSeconds
        val chunksAtEnd = sessionChunksSent
        val bytesAtEnd = sessionBytesSent
        val ws = webSocketClient
        val wl = wakeLock
        wakeLock = null

        return try {
            if (ws != null) {
                while (true) {
                    val chunk = audioQueue.poll() ?: break
                    val sent = ws.sendAudioChunk(chunk)
                    if (!sent) break
                }
            }
            val finalText = ws?.endActivityAndAwaitFinal().orEmpty().trim()
            if (finalizedTranscript.isEmpty() && finalText.isNotEmpty()) {
                finalizedTranscript.append(finalText)
            }
            interimTranscript = ""
            val text = getFullTranscriptText().ifBlank { finalText }
            onTranscriptUpdated(text)
            text
        } catch (e: Exception) {
            Log.e(TAG, "Error finishing manual activity", e)
            getFullTranscriptText()
        } finally {
            try {
                ws?.disconnect()
            } catch (e: Exception) {
                Log.e(TAG, "Error disconnecting after activityEnd", e)
            }
            webSocketClient = null
            withContext(NonCancellable + Dispatchers.IO) {
                settleRetryClip(discard = false)
            }
            logSessionEndOnce(
                "ended_reason: completed | Duration: ${durationAtEnd}s, Chunks: $chunksAtEnd, Streamed: ${bytesAtEnd / 1024} KB | ${ws?.sessionSummary().orEmpty()}"
            )
            AppLogRepository.updateConnectionState(ConnectionState.Idle, DiagnosticSource.BUBBLE)
            try {
                if (wl?.isHeld == true) wl.release()
            } catch (e: Exception) {
                Log.w(TAG, "Error releasing WakeLock", e)
            }
        }
    }

    fun logSessionEndOnce(message: String) {
        if (!sessionEndLogged.compareAndSet(false, true)) return
        AppLogRepository.logEvent(
            DiagnosticSource.BUBBLE,
            DiagnosticType.SESSION_END,
            message
        )
    }

    private fun onSessionClockTick() {
        val client = webSocketClient
        val tick = sessionClock.onTick(
            sessionMs = durationSeconds * 1000L,
            singleConnection = client?.isSingleConnection() != false
        )
        if (tick.warn) {
            onSessionLimit("1 minute left in this session", false)
        }
        if (tick.armFinishAtPause) {
            finishAtNextPause = true
            if ((client?.currentPauseMs() ?: 0L) >= DictationPiecePlanner.PAUSE_MS) {
                onSessionLimit("Session ended at 10 minutes.", true)
            }
        }
        if (tick.hardStop) {
            onSessionLimit("Session ended at 10 minutes.", true)
        }
    }

    private fun appendPiece(piece: String) {
        val trimmed = piece.trim()
        if (trimmed.isEmpty()) return
        val current = finalizedTranscript.toString().trim()
        if (current == trimmed || current.endsWith(" $trimmed")) return
        finalizedTranscript.clear()
        finalizedTranscript.append(TranscriptSeam.append(current, trimmed))
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
        FloatingBubbleManager.setNetworkProblem(false)
        durationJob?.cancel()
        durationJob = null
        drainJob?.cancel()
        drainJob = null
        sessionJob?.cancel()
        sessionJob = null
        sessionScope = null
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
        if (retryStopJob?.isActive != true) {
            settleRetryClip(discard = false)
        }
    }
}
