package com.princeniithompson.rapcha.ui

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.princeniithompson.rapcha.BuildConfig
import com.princeniithompson.rapcha.audio.AudioRecorder
import com.princeniithompson.rapcha.data.ConnectionState
import com.princeniithompson.rapcha.data.LiveStats
import com.princeniithompson.rapcha.data.LogEntry
import com.princeniithompson.rapcha.data.LogLevel
import com.princeniithompson.rapcha.websocket.GeminiLiveWebSocketClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

class VoiceTypingViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "VoiceTypingVM"
        private const val MAX_LOGS = 120
        private const val PREFS_NAME = "voxstream_settings"
        private const val KEY_SMART_MODE = "smart_mode"
        private const val KEY_CUSTOM_API_KEY = "custom_api_key"
        private const val KEY_LAST_WEEKLY_CHECK_TIME = "last_weekly_smart_vocab_check_time"
        private const val KEY_WEEKLY_VOCAB_REMOVED = "removed_weekly_smart_vocab_v1"
    }

    private val prefs = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    val isSaveRecordingsEnabled: StateFlow<Boolean> = com.princeniithompson.rapcha.data.AudioRecordingRepository.isSaveRecordingsEnabled
    val recordings: StateFlow<List<com.princeniithompson.rapcha.data.AudioRecording>> = com.princeniithompson.rapcha.data.AudioRecordingRepository.recordings

    fun setSaveRecordingsEnabled(enabled: Boolean) {
        com.princeniithompson.rapcha.data.AudioRecordingRepository.setSaveRecordingsEnabled(enabled)
    }

    fun deleteRecording(recording: com.princeniithompson.rapcha.data.AudioRecording) {
        com.princeniithompson.rapcha.data.AudioRecordingRepository.deleteRecording(recording)
    }

    fun clearAllRecordings() {
        com.princeniithompson.rapcha.data.AudioRecordingRepository.clearAllRecordings()
    }

    private val _customApiKey = MutableStateFlow("")
    val customApiKey: StateFlow<String> = _customApiKey.asStateFlow()

    val isBubbleEnabled: StateFlow<Boolean> = com.princeniithompson.rapcha.service.FloatingBubbleManager.isBubbleEnabled
    val isSmartSafeModeEnabled: StateFlow<Boolean> = com.princeniithompson.rapcha.service.FloatingBubbleManager.isSmartSafeModeEnabled
    val isCurrentAppSensitive: StateFlow<Boolean> = com.princeniithompson.rapcha.service.FloatingBubbleManager.isCurrentAppSensitive
    val isAccessibilityConnected: StateFlow<Boolean> = com.princeniithompson.rapcha.service.FloatingBubbleManager.isAccessibilityConnected
    val hasOverlayPermission: StateFlow<Boolean> = com.princeniithompson.rapcha.service.FloatingBubbleManager.hasOverlayPermission
    val selectedGlowStyleId: StateFlow<String> = com.princeniithompson.rapcha.service.FloatingBubbleManager.selectedGlowStyleId
    val selectedFinishingStyleId: StateFlow<String> = com.princeniithompson.rapcha.service.FloatingBubbleManager.selectedFinishingStyleId

    fun refreshPermissions() {
        com.princeniithompson.rapcha.service.FloatingBubbleManager.checkPermissions(getApplication())
    }

    fun setSmartSafeModeEnabled(enabled: Boolean) {
        com.princeniithompson.rapcha.service.FloatingBubbleManager.setSmartSafeModeEnabled(getApplication(), enabled)
    }

    fun setGlowStyle(styleId: String) {
        com.princeniithompson.rapcha.service.FloatingBubbleManager.setGlowStyle(getApplication(), styleId)
    }

    fun setFinishingStyle(styleId: String) {
        com.princeniithompson.rapcha.service.FloatingBubbleManager.setFinishingStyle(getApplication(), styleId)
    }

    init {
        val savedKey = prefs.getString(KEY_CUSTOM_API_KEY, "") ?: ""
        if (savedKey.isNotBlank()) {
            _customApiKey.value = savedKey
        }
        com.princeniithompson.rapcha.data.AppLogRepository.init(application)
        com.princeniithompson.rapcha.service.FloatingBubbleManager.init(application)
        if (!prefs.getBoolean(KEY_WEEKLY_VOCAB_REMOVED, false)) {
            prefs.edit()
                .remove(KEY_LAST_WEEKLY_CHECK_TIME)
                .putBoolean(KEY_WEEKLY_VOCAB_REMOVED, true)
                .apply()
        }
    }

    val diagnosticEntries: StateFlow<List<com.princeniithompson.rapcha.data.DiagnosticLogEntry>> = com.princeniithompson.rapcha.data.AppLogRepository.diagnosticEntries
    val notes: StateFlow<List<com.princeniithompson.rapcha.data.DiagnosticNote>> = com.princeniithompson.rapcha.data.AppLogRepository.notes

    val isOnboardingCompleted: StateFlow<Boolean> = com.princeniithompson.rapcha.data.OnboardingRepository.isOnboardingCompleted
    val preferredDockSide: StateFlow<String> = com.princeniithompson.rapcha.data.OnboardingRepository.preferredDockSide
    val hasCompletedVoiceTest: StateFlow<Boolean> = com.princeniithompson.rapcha.data.OnboardingRepository.hasCompletedVoiceTest

    fun completeOnboarding() {
        com.princeniithompson.rapcha.data.OnboardingRepository.completeOnboarding()
    }

    fun resetOnboarding() {
        com.princeniithompson.rapcha.data.OnboardingRepository.resetOnboarding()
    }

    fun setPreferredDockSide(side: String) {
        com.princeniithompson.rapcha.data.OnboardingRepository.setPreferredDockSide(side)
    }

    fun setVoiceTestDone(done: Boolean) {
        com.princeniithompson.rapcha.data.OnboardingRepository.setVoiceTestDone(done)
    }

    fun addNote(text: String) {
        com.princeniithompson.rapcha.data.AppLogRepository.addNote(text)
    }

    fun clearAllDiagnostics() {
        com.princeniithompson.rapcha.data.AppLogRepository.clearAllDiagnostics()
    }

    fun canDrawOverlays(): Boolean = com.princeniithompson.rapcha.service.FloatingBubbleManager.canDrawOverlays(getApplication())

    fun setBubbleEnabled(enabled: Boolean) {
        com.princeniithompson.rapcha.service.FloatingBubbleManager.setBubbleEnabled(getApplication(), enabled)
    }

    private val _isSmartMode = MutableStateFlow(prefs.getBoolean(KEY_SMART_MODE, false))
    val isSmartMode: StateFlow<Boolean> = _isSmartMode.asStateFlow()

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    val connectionState: StateFlow<ConnectionState> = com.princeniithompson.rapcha.data.AppLogRepository.liveConnectionState

    private val _finalizedTranscript = MutableStateFlow("")
    val finalizedTranscript: StateFlow<String> = _finalizedTranscript.asStateFlow()

    private val _interimTranscript = MutableStateFlow("")
    val interimTranscript: StateFlow<String> = _interimTranscript.asStateFlow()

    private val _audioAmplitude = MutableStateFlow(0f)
    val audioAmplitude: StateFlow<Float> = _audioAmplitude.asStateFlow()

    val stats: StateFlow<LiveStats> = com.princeniithompson.rapcha.data.AppLogRepository.liveStats

    val logs: StateFlow<List<LogEntry>> = com.princeniithompson.rapcha.data.AppLogRepository.logs

    private val _selectedModel = MutableStateFlow(GeminiLiveWebSocketClient.DEFAULT_MODEL)
    val selectedModel: StateFlow<String> = _selectedModel.asStateFlow()

    private val audioQueue = ConcurrentLinkedQueue<ByteArray>()
    private val sessionEndLogged = AtomicBoolean(false)
    private val _previewPhase = MutableStateFlow(com.princeniithompson.rapcha.websocket.InterimPreviewMonitor.Phase.LISTENING)
    val previewPhase: StateFlow<com.princeniithompson.rapcha.websocket.InterimPreviewMonitor.Phase> = _previewPhase.asStateFlow()
    private var connectionLostHapticSent = false
    private var sessionClock = com.princeniithompson.rapcha.websocket.DictationSessionClock()
    private var finishAtNextPause = false
    private var sessionStartedAtMs = 0L
    private var durationJob: Job? = null
    private var drainJob: Job? = null

    private val webSocketClient = GeminiLiveWebSocketClient(
            onSetupComplete = {
            addLog(LogLevel.INFO, TAG, "setupComplete received! Flushing buffered queue & streaming live audio.")
            com.princeniithompson.rapcha.data.AppLogRepository.updateLiveStats { it.copy(source = com.princeniithompson.rapcha.data.DiagnosticSource.APP, setupCompleted = true) }
            onLiveSetupComplete()
        },
        onInterimTranscription = { text ->
            _interimTranscript.value = text
            com.princeniithompson.rapcha.data.AppLogRepository.updateLiveStats { it.copy(source = com.princeniithompson.rapcha.data.DiagnosticSource.APP, interimCount = it.interimCount + 1) }
        },
        onPieceCommitted = { piece ->
            _finalizedTranscript.value = mergePiece(_finalizedTranscript.value, piece)
            _interimTranscript.value = ""
        },
        onGoAway = {
            val sessionMs = stats.value.durationSeconds * 1000L
            if (sessionClock.shouldSplitOnGoAway(sessionMs)) {
                splitOntoFreshSocket()
            } else if (_isRecording.value) {
                autoFinishSession()
            }
        },
        onAudiblePause = {
            if (finishAtNextPause && _isRecording.value) {
                autoFinishSession()
            }
        },
        onFinalizedTranscription = { text ->
            val trimmedNew = text.trim()
            if (trimmedNew.isNotEmpty()) {
                com.princeniithompson.rapcha.data.AppLogRepository.logEvent(
                    com.princeniithompson.rapcha.data.DiagnosticSource.APP,
                    com.princeniithompson.rapcha.data.DiagnosticType.TRANSCRIPT_FINAL,
                    com.princeniithompson.rapcha.data.TranscriptPrivacy.summary(trimmedNew)
                )
                _finalizedTranscript.value = mergePiece(_finalizedTranscript.value, trimmedNew)
            }
            _interimTranscript.value = ""
            com.princeniithompson.rapcha.data.AppLogRepository.updateLiveStats { it.copy(source = com.princeniithompson.rapcha.data.DiagnosticSource.APP, finalizedCount = it.finalizedCount + 1) }
        },
        onStateChanged = { newState ->
            com.princeniithompson.rapcha.data.AppLogRepository.updateConnectionState(newState, com.princeniithompson.rapcha.data.DiagnosticSource.APP)
        },
        onLog = { level, tag, msg, payload ->
            addLog(level, tag, msg, payload)
        },
        onError = { errorMsg ->
            val endedReasonTag = com.princeniithompson.rapcha.data.DictationEndedReason.fromMessage(errorMsg)

            com.princeniithompson.rapcha.data.AppLogRepository.updateLiveStats { it.copy(source = com.princeniithompson.rapcha.data.DiagnosticSource.APP, lastError = errorMsg) }
            com.princeniithompson.rapcha.data.AppLogRepository.logEvent(
                com.princeniithompson.rapcha.data.DiagnosticSource.APP,
                com.princeniithompson.rapcha.data.DiagnosticType.ERROR,
                errorMsg
            )
            if (_isRecording.value) {
                stopRecording(endedReason = endedReasonTag)
            }
        },
        isSessionActive = { _isRecording.value },
        diagnosticSource = com.princeniithompson.rapcha.data.DiagnosticSource.APP,
        onPreviewPhase = { phase ->
            _previewPhase.value = phase
            if (phase == com.princeniithompson.rapcha.websocket.InterimPreviewMonitor.Phase.CONNECTION_LOST) {
                if (!connectionLostHapticSent) {
                    connectionLostHapticSent = true
                    com.princeniithompson.rapcha.service.floating.DictationAlerts.connectionDropped(getApplication())
                }
            } else {
                connectionLostHapticSent = false
            }
        }
    )

    private var appWakeLock: android.os.PowerManager.WakeLock? = null

    private val audioRecorder = AudioRecorder(
        onChunkReady = { chunk ->
            if (_isRecording.value) {
                // If setup is already complete, send directly; otherwise queue in bounded memory
                if (webSocketClient.setupComplete) {
                    val sent = webSocketClient.sendAudioChunk(chunk)
                    if (sent) {
                        com.princeniithompson.rapcha.data.AppLogRepository.updateLiveStats {
                            it.copy(
                                source = com.princeniithompson.rapcha.data.DiagnosticSource.APP,
                                chunksSent = it.chunksSent + 1,
                                bytesSent = it.bytesSent + chunk.size,
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
        onAmplitudeChanged = { amp ->
            _audioAmplitude.value = amp
        },
        onError = { err ->
            addLog(LogLevel.ERROR, TAG, "AudioRecorder Error: $err")
            com.princeniithompson.rapcha.data.AppLogRepository.updateLiveStats { it.copy(source = com.princeniithompson.rapcha.data.DiagnosticSource.APP, lastError = err) }
            com.princeniithompson.rapcha.service.floating.DictationAlerts.microphoneFailed(getApplication())
        }
    )

    private fun enqueueAudioChunk(chunk: ByteArray) {
        // Cap pre-connection audio queue to max 20 chunks (2.0 seconds) to avoid buffer overflow
        while (audioQueue.size >= 20) {
            audioQueue.poll()
        }
        audioQueue.add(chunk)
        com.princeniithompson.rapcha.data.AppLogRepository.updateLiveStats { it.copy(source = com.princeniithompson.rapcha.data.DiagnosticSource.APP, chunksBuffered = audioQueue.size) }
    }

    val effectiveApiKey: String
        get() = com.princeniithompson.rapcha.core.ApiConfig.getEffectiveKey(_customApiKey.value)

    fun isApiKeyConfigured(): Boolean {
        return !com.princeniithompson.rapcha.core.ApiConfig.isPlaceholder(effectiveApiKey)
    }

    fun setCustomApiKey(key: String) {
        val trimmed = key.trim()
        _customApiKey.value = trimmed
        prefs.edit().putString(KEY_CUSTOM_API_KEY, trimmed).apply()
        addLog(LogLevel.INFO, TAG, "Custom API key updated")
    }

    fun setSelectedModel(model: String) {
        _selectedModel.value = model.trim()
    }

    fun setSmartMode(enabled: Boolean) {
        if (_isRecording.value) {
            addLog(LogLevel.INFO, TAG, "Cannot change Smart Mode while session is recording")
            return
        }
        _isSmartMode.value = enabled
        prefs.edit().putBoolean(KEY_SMART_MODE, enabled).apply()
        addLog(LogLevel.INFO, TAG, "Smart Mode set to $enabled (saved to preferences, takes effect next session)")
    }

    fun toggleRecording() {
        if (_isRecording.value) {
            stopRecording()
        } else {
            startRecording()
        }
    }

    fun startRecording() {
        if (_isRecording.value) return

        sessionEndLogged.set(false)
        connectionLostHapticSent = false
        finishAtNextPause = false
        sessionClock = com.princeniithompson.rapcha.websocket.DictationSessionClock()
        sessionStartedAtMs = System.currentTimeMillis()
        _previewPhase.value = com.princeniithompson.rapcha.websocket.InterimPreviewMonitor.Phase.LISTENING
        _isRecording.value = true
        _interimTranscript.value = ""
        audioQueue.clear()
        com.princeniithompson.rapcha.data.AppLogRepository.updateLiveStats {
            LiveStats(
                source = com.princeniithompson.rapcha.data.DiagnosticSource.APP,
                setupCompleted = false
            )
        }
        com.princeniithompson.rapcha.data.AppLogRepository.updateConnectionState(ConnectionState.Connecting, com.princeniithompson.rapcha.data.DiagnosticSource.APP)

        try {
            val powerManager = getApplication<Application>().getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            if (appWakeLock?.isHeld == true) {
                try { appWakeLock?.release() } catch (_: Exception) {}
            }
            appWakeLock = powerManager.newWakeLock(
                android.os.PowerManager.PARTIAL_WAKE_LOCK,
                "voxstream:in_app_voice_typing_wakelock"
            ).apply {
                acquire(30 * 60 * 1000L /* 30 minutes max */)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not acquire WakeLock for in-app session", e)
        }

        val apiKey = effectiveApiKey
        val modeLabel = if (_isSmartMode.value) "SMART" else "VERBATIM"
        addLog(LogLevel.INFO, TAG, "Starting voice typing session ($modeLabel mode). Key configured: ${isApiKeyConfigured()}")
        com.princeniithompson.rapcha.data.AppLogRepository.logEvent(
            com.princeniithompson.rapcha.data.DiagnosticSource.APP,
            com.princeniithompson.rapcha.data.DiagnosticType.SESSION_START,
            "Mode: $modeLabel, Model: ${_selectedModel.value}"
        )

        // 1. Immediately start AudioRecord on Dispatchers.IO to capture speech without clipping
        audioRecorder.start(
            viewModelScope,
            source = com.princeniithompson.rapcha.data.DiagnosticSource.APP
        )

        // 2. Open Gemini Live WebSocket connection
        val smartMode = _isSmartMode.value
        val customVocab = com.princeniithompson.rapcha.data.CustomVocabularyRepository.liveTerms(smartMode)
        com.princeniithompson.rapcha.data.CustomVocabularyRepository.logLiveSelection(
            smartMode,
            com.princeniithompson.rapcha.data.DiagnosticSource.APP
        )
        try {
            webSocketClient.connect(apiKey, _selectedModel.value, smartMode, customVocabulary = customVocab)
        } catch (e: Exception) {
            Log.e(TAG, "Failed connecting WebSocket: ${e.message}")
            addLog(LogLevel.ERROR, TAG, "Connection failed: ${e.message}")
            stopRecording(endedReason = "connection_error")
            return
        }

        // 3. Start recording duration timer
        durationJob?.cancel()
        durationJob = viewModelScope.launch {
            val start = sessionStartedAtMs
            while (isActive && _isRecording.value) {
                delay(500)
                webSocketClient.pollInterimPreview()
                val elapsedMs = System.currentTimeMillis() - start
                val elapsedSec = (elapsedMs / 1000).toInt()
                com.princeniithompson.rapcha.data.AppLogRepository.updateLiveStats { it.copy(source = com.princeniithompson.rapcha.data.DiagnosticSource.APP, durationSeconds = elapsedSec) }
                onSessionClockTick(elapsedMs)
            }
        }
    }

    fun cancelSession() {
        val wasRecording = _isRecording.value
        val hasTranscript = _finalizedTranscript.value.isNotBlank() || _interimTranscript.value.isNotBlank()

        if (wasRecording) {
            stopRecording(endedReason = "user_cancelled")
        } else if (hasTranscript && sessionEndLogged.compareAndSet(false, true)) {
            com.princeniithompson.rapcha.data.AppLogRepository.logEvent(
                com.princeniithompson.rapcha.data.DiagnosticSource.APP,
                com.princeniithompson.rapcha.data.DiagnosticType.SESSION_END,
                "ended_reason: user_cancelled"
            )
        }
        clearTranscript()
    }

    fun stopRecording(endedReason: String = "completed") {
        if (!_isRecording.value) return
        _isRecording.value = false
        val shouldLogEnd = sessionEndLogged.compareAndSet(false, true)
        val sessionLimitStop = endedReason == "session_limit"
        durationJob?.cancel()

        addLog(LogLevel.INFO, TAG, "Stopping recording session ($endedReason)...")

        val wl = appWakeLock
        appWakeLock = null

        // 1. Stop mic capture and signal WS completion
        viewModelScope.launch(Dispatchers.IO) {
            val durationAtEnd = if (sessionStartedAtMs > 0L) {
                ((System.currentTimeMillis() - sessionStartedAtMs).coerceAtLeast(0L) / 1000L).toInt()
            } else {
                stats.value.durationSeconds
            }
            val chunksAtEnd = stats.value.chunksSent
            val bytesAtEnd = stats.value.bytesSent

            try {
                audioRecorder.stopAndJoin()
                _audioAmplitude.value = 0f
                while (true) {
                    val chunk = audioQueue.poll() ?: break
                    if (!webSocketClient.sendAudioChunk(chunk)) break
                }
                val shouldEndActivity = endedReason == "completed" || endedReason == "session_limit"
                if (shouldEndActivity) {
                    val finalText = webSocketClient.endActivityAndAwaitFinal().trim()
                    if (finalText.isNotBlank()) {
                        _finalizedTranscript.value = finalText
                        _interimTranscript.value = ""
                    } else if (stats.value.bytesSent > 32_000L) {
                        com.princeniithompson.rapcha.service.floating.DictationAlerts.finalTextLost(getApplication())
                    }
                }
                webSocketClient.disconnect()
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping in-app session", e)
            } finally {
                val fullText = _finalizedTranscript.value.trim()
                if (fullText.isNotBlank()) {
                    val words = fullText.split("\\s+".toRegex()).count { it.isNotBlank() }
                    com.princeniithompson.rapcha.data.AppLogRepository.recordInjectionResult(
                        durationMs = maxOf(400L, durationAtEnd * 1000L),
                        wordCount = words,
                        targetApp = "Rapcha",
                        transcriptText = fullText,
                        source = com.princeniithompson.rapcha.data.DiagnosticSource.APP
                    )
                }
                com.princeniithompson.rapcha.data.AppLogRepository.updateConnectionState(ConnectionState.Idle, com.princeniithompson.rapcha.data.DiagnosticSource.APP)
                addLog(LogLevel.INFO, TAG, "Session completed ($endedReason). Total chunks sent: $chunksAtEnd")
                if (shouldLogEnd) {
                    com.princeniithompson.rapcha.data.AppLogRepository.logEvent(
                        com.princeniithompson.rapcha.data.DiagnosticSource.APP,
                        com.princeniithompson.rapcha.data.DiagnosticType.SESSION_END,
                        "ended_reason: $endedReason | Duration: ${durationAtEnd}s, Chunks: $chunksAtEnd, Streamed: ${bytesAtEnd / 1024} KB | ${webSocketClient.sessionSummary()}"
                    )
                }
                if (sessionLimitStop && _finalizedTranscript.value.isNotBlank()) {
                    com.princeniithompson.rapcha.service.floating.DictationAlerts.sessionEnded(getApplication(), copied = false)
                }
                _previewPhase.value = com.princeniithompson.rapcha.websocket.InterimPreviewMonitor.Phase.LISTENING
                try {
                    if (wl?.isHeld == true) {
                        wl.release()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error releasing WakeLock in ViewModel", e)
                }
            }
        }
    }

    private fun onLiveSetupComplete() {
        webSocketClient.signalActivityStart()
        drainAudioQueue()
    }

    private fun drainAudioQueue() {
        drainJob?.cancel()
        drainJob = viewModelScope.launch(Dispatchers.IO) {
            var drainedCount = 0
            while (isActive && webSocketClient.setupComplete) {
                val chunk = audioQueue.poll() ?: break
                val sent = webSocketClient.sendAudioChunk(chunk)
                if (sent) {
                    drainedCount++
                    com.princeniithompson.rapcha.data.AppLogRepository.updateLiveStats {
                        it.copy(
                            source = com.princeniithompson.rapcha.data.DiagnosticSource.APP,
                            chunksSent = it.chunksSent + 1,
                            bytesSent = it.bytesSent + chunk.size,
                            chunksBuffered = maxOf(0, it.chunksBuffered - 1)
                        )
                    }
                    if (audioQueue.isNotEmpty()) {
                        delay(25) // Smooth 25ms pace so server is not flooded
                    }
                } else {
                    // Socket not writable, put chunk back or delay
                    audioQueue.add(chunk)
                    delay(20)
                    break
                }
            }
            if (drainedCount > 0) {
                addLog(LogLevel.INFO, TAG, "Flushed $drainedCount pre-buffered chunks to Gemini Live")
            }
        }
    }

    fun clearTranscript() {
        _finalizedTranscript.value = ""
        _interimTranscript.value = ""
        addLog(LogLevel.INFO, TAG, "Transcript cleared")
    }

    private fun splitOntoFreshSocket() {
        webSocketClient.requestFreshConnectionSplit()
    }

    private fun autoFinishSession() {
        if (!_isRecording.value) return
        stopRecording(endedReason = "session_limit")
    }

    private fun onSessionClockTick(sessionMs: Long) {
        val tick = sessionClock.onTick(sessionMs, webSocketClient.isSingleConnection())
        if (tick.warn) {
            com.princeniithompson.rapcha.service.floating.DictationAlerts.endingSoon(getApplication())
        }
        if (tick.armFinishAtPause) {
            finishAtNextPause = true
            if (webSocketClient.currentPauseMs() >= com.princeniithompson.rapcha.websocket.DictationPiecePlanner.PAUSE_MS) {
                autoFinishSession()
            }
        }
        if (tick.hardStop) {
            autoFinishSession()
        }
    }

    private fun mergePiece(current: String, piece: String): String {
        val trimmed = piece.trim()
        if (trimmed.isEmpty()) return current.trim()
        val base = current.trim()
        if (base == trimmed || base.endsWith(" $trimmed")) return base
        return com.princeniithompson.rapcha.websocket.TranscriptSeam.append(base, trimmed)
    }

    fun copyTranscript(context: Context): Boolean {
        val fullText = buildString {
            append(_finalizedTranscript.value)
            if (_interimTranscript.value.isNotEmpty()) {
                if (isNotEmpty()) append(" ")
                append(_interimTranscript.value)
            }
        }.trim()

        if (fullText.isEmpty()) return false

        val restored = com.princeniithompson.rapcha.data.CustomVocabularyRepository.prepareOutgoingTranscript(
            fullText,
            com.princeniithompson.rapcha.data.DiagnosticSource.APP
        )
        if (restored.isEmpty()) return false

        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Rapcha Transcript", restored)
        clipboard.setPrimaryClip(clip)
        addLog(LogLevel.INFO, TAG, "Copied transcript to clipboard (${restored.length} chars)")
        return true
    }

    private fun addLog(level: LogLevel, tag: String, message: String, payload: String? = null) {
        com.princeniithompson.rapcha.data.AppLogRepository.addLog(level, tag, message, payload)
    }

    fun clearLogs() {
        com.princeniithompson.rapcha.data.AppLogRepository.clearLiveFrames()
    }

    override fun onCleared() {
        super.onCleared()
        audioRecorder.stop()
        webSocketClient.disconnect()
        durationJob?.cancel()
        drainJob?.cancel()
    }
}
