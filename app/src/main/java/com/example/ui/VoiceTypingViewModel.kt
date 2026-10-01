package com.example.ui

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.BuildConfig
import com.example.audio.AudioRecorder
import com.example.data.ConnectionState
import com.example.data.LiveStats
import com.example.data.LogEntry
import com.example.data.LogLevel
import com.example.websocket.GeminiLiveWebSocketClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentLinkedQueue

class VoiceTypingViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "VoiceTypingVM"
        private const val MAX_LOGS = 120
        private const val PREFS_NAME = "voxstream_settings"
        private const val KEY_SMART_MODE = "smart_mode"
        private const val KEY_CUSTOM_API_KEY = "custom_api_key"
        private const val KEY_AEC_ENABLED = "aec_enabled"
        private const val KEY_NOISE_SUPPRESSOR_ENABLED = "noise_suppressor_enabled"
    }

    private val prefs = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    val isAecSupported: Boolean = try {
        android.media.audiofx.AcousticEchoCanceler.isAvailable()
    } catch (t: Throwable) {
        false
    }

    val isNoiseSuppressorSupported: Boolean = try {
        android.media.audiofx.NoiseSuppressor.isAvailable()
    } catch (t: Throwable) {
        false
    }

    private val _isAecEnabled = MutableStateFlow(prefs.getBoolean(KEY_AEC_ENABLED, true))
    val isAecEnabled: StateFlow<Boolean> = _isAecEnabled.asStateFlow()

    private val _isNoiseSuppressorEnabled = MutableStateFlow(prefs.getBoolean(KEY_NOISE_SUPPRESSOR_ENABLED, true))
    val isNoiseSuppressorEnabled: StateFlow<Boolean> = _isNoiseSuppressorEnabled.asStateFlow()

    fun setAecEnabled(enabled: Boolean) {
        _isAecEnabled.value = enabled
        prefs.edit().putBoolean(KEY_AEC_ENABLED, enabled).apply()
        addLog(LogLevel.INFO, TAG, "Acoustic Echo Canceler set to $enabled")
    }

    fun setNoiseSuppressorEnabled(enabled: Boolean) {
        _isNoiseSuppressorEnabled.value = enabled
        prefs.edit().putBoolean(KEY_NOISE_SUPPRESSOR_ENABLED, enabled).apply()
        addLog(LogLevel.INFO, TAG, "Noise Suppressor set to $enabled")
    }

    val isSaveRecordingsEnabled: StateFlow<Boolean> = com.example.data.AudioRecordingRepository.isSaveRecordingsEnabled
    val recordings: StateFlow<List<com.example.data.AudioRecording>> = com.example.data.AudioRecordingRepository.recordings

    fun setSaveRecordingsEnabled(enabled: Boolean) {
        com.example.data.AudioRecordingRepository.setSaveRecordingsEnabled(enabled)
    }

    fun deleteRecording(recording: com.example.data.AudioRecording) {
        com.example.data.AudioRecordingRepository.deleteRecording(recording)
    }

    fun clearAllRecordings() {
        com.example.data.AudioRecordingRepository.clearAllRecordings()
    }

    private val _customApiKey = MutableStateFlow("")
    val customApiKey: StateFlow<String> = _customApiKey.asStateFlow()

    val isBubbleEnabled: StateFlow<Boolean> = com.example.service.FloatingBubbleManager.isBubbleEnabled
    val isSmartSafeModeEnabled: StateFlow<Boolean> = com.example.service.FloatingBubbleManager.isSmartSafeModeEnabled
    val isCurrentAppSensitive: StateFlow<Boolean> = com.example.service.FloatingBubbleManager.isCurrentAppSensitive
    val isAccessibilityConnected: StateFlow<Boolean> = com.example.service.FloatingBubbleManager.isAccessibilityConnected
    val selectedGlowStyleId: StateFlow<String> = com.example.service.FloatingBubbleManager.selectedGlowStyleId
    val selectedFinishingStyleId: StateFlow<String> = com.example.service.FloatingBubbleManager.selectedFinishingStyleId

    fun setSmartSafeModeEnabled(enabled: Boolean) {
        com.example.service.FloatingBubbleManager.setSmartSafeModeEnabled(getApplication(), enabled)
    }

    fun setGlowStyle(styleId: String) {
        com.example.service.FloatingBubbleManager.setGlowStyle(getApplication(), styleId)
    }

    fun setFinishingStyle(styleId: String) {
        com.example.service.FloatingBubbleManager.setFinishingStyle(getApplication(), styleId)
    }

    init {
        val savedKey = prefs.getString(KEY_CUSTOM_API_KEY, "") ?: ""
        if (savedKey.isNotBlank()) {
            _customApiKey.value = savedKey
        }
        com.example.data.AppLogRepository.init(application)
        com.example.service.FloatingBubbleManager.init(application)
        checkWeeklySmartVocabularySchedule()
    }

    val diagnosticEntries: StateFlow<List<com.example.data.DiagnosticLogEntry>> = com.example.data.AppLogRepository.diagnosticEntries
    val notes: StateFlow<List<com.example.data.DiagnosticNote>> = com.example.data.AppLogRepository.notes

    fun addNote(text: String) {
        com.example.data.AppLogRepository.addNote(text)
    }

    fun clearAllDiagnostics() {
        com.example.data.AppLogRepository.clearAllDiagnostics()
    }

    fun canDrawOverlays(): Boolean = com.example.service.FloatingBubbleManager.canDrawOverlays(getApplication())

    fun setBubbleEnabled(enabled: Boolean) {
        com.example.service.FloatingBubbleManager.setBubbleEnabled(getApplication(), enabled)
    }

    private val _isSmartMode = MutableStateFlow(prefs.getBoolean(KEY_SMART_MODE, false))
    val isSmartMode: StateFlow<Boolean> = _isSmartMode.asStateFlow()

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _finalizedTranscript = MutableStateFlow("")
    val finalizedTranscript: StateFlow<String> = _finalizedTranscript.asStateFlow()

    private val _interimTranscript = MutableStateFlow("")
    val interimTranscript: StateFlow<String> = _interimTranscript.asStateFlow()

    private val _audioAmplitude = MutableStateFlow(0f)
    val audioAmplitude: StateFlow<Float> = _audioAmplitude.asStateFlow()

    private val _stats = MutableStateFlow(LiveStats())
    val stats: StateFlow<LiveStats> = _stats.asStateFlow()

    val logs: StateFlow<List<LogEntry>> = com.example.data.AppLogRepository.logs

    private val _selectedModel = MutableStateFlow(GeminiLiveWebSocketClient.DEFAULT_MODEL)
    val selectedModel: StateFlow<String> = _selectedModel.asStateFlow()

    private val audioQueue = ConcurrentLinkedQueue<ByteArray>()
    private var durationJob: Job? = null
    private var drainJob: Job? = null

    private val webSocketClient = GeminiLiveWebSocketClient(
        onSetupComplete = {
            addLog(LogLevel.INFO, TAG, "setupComplete received! Flushing buffered queue & streaming live audio.")
            _stats.update { it.copy(setupCompleted = true) }
            drainAudioQueue()
        },
        onInterimTranscription = { text ->
            _interimTranscript.value = text
            _stats.update { it.copy(interimCount = it.interimCount + 1) }
        },
        onFinalizedTranscription = { text ->
            val trimmedNew = text.trim()
            if (trimmedNew.isNotEmpty()) {
                com.example.data.AppLogRepository.logEvent(
                    com.example.data.DiagnosticSource.APP,
                    com.example.data.DiagnosticType.TRANSCRIPT_FINAL,
                    trimmedNew
                )
            }
            _finalizedTranscript.update { current ->
                if (trimmedNew.isEmpty()) current
                else if (current.isBlank()) trimmedNew
                else "$current $trimmedNew"
            }
            _interimTranscript.value = ""
            _stats.update { it.copy(finalizedCount = it.finalizedCount + 1) }
        },
        onStateChanged = { newState ->
            _connectionState.value = newState
        },
        onLog = { level, tag, msg, payload ->
            addLog(level, tag, msg, payload)
        },
        onError = { errorMsg ->
            val isPingTimeout = errorMsg.contains("ping", ignoreCase = true) || errorMsg.contains("pong", ignoreCase = true) || errorMsg.contains("timeout", ignoreCase = true)
            val endedReasonTag = if (isPingTimeout) "ping_timeout" else "connection_drop"

            _stats.update { it.copy(lastError = errorMsg) }
            com.example.data.AppLogRepository.logEvent(
                com.example.data.DiagnosticSource.APP,
                com.example.data.DiagnosticType.ERROR,
                errorMsg
            )
            if (_isRecording.value) {
                stopRecording(endedReason = endedReasonTag)
            }
        }
    )

    private var appWakeLock: android.os.PowerManager.WakeLock? = null

    private val audioRecorder = AudioRecorder(
        onChunkReady = { chunk ->
            if (_isRecording.value) {
                // If setup is already complete, send directly; otherwise queue in memory
                if (webSocketClient.setupComplete) {
                    val sent = webSocketClient.sendAudioChunk(chunk)
                    if (sent) {
                        _stats.update {
                            it.copy(
                                chunksSent = it.chunksSent + 1,
                                bytesSent = it.bytesSent + chunk.size
                            )
                        }
                    } else {
                        // Socket might be busy, buffer chunk
                        audioQueue.add(chunk)
                        _stats.update { it.copy(chunksBuffered = it.chunksBuffered + 1) }
                    }
                } else {
                    // Pre-setup phase: buffer chunks in-memory so early speech isn't clipped
                    audioQueue.add(chunk)
                    _stats.update { it.copy(chunksBuffered = it.chunksBuffered + 1) }
                }
            }
        },
        onAmplitudeChanged = { amp ->
            _audioAmplitude.value = amp
        },
        onError = { err ->
            addLog(LogLevel.ERROR, TAG, "AudioRecorder Error: $err")
            _stats.update { it.copy(lastError = err) }
        }
    )

    val effectiveApiKey: String
        get() {
            val custom = _customApiKey.value.trim()
            if (custom.isNotEmpty()) return custom
            return try {
                BuildConfig.GEMINI_API_KEY
            } catch (e: Throwable) {
                ""
            }
        }

    fun isApiKeyConfigured(): Boolean {
        val key = effectiveApiKey.trim()
        return key.isNotEmpty() && !key.equals("MY_GEMINI_API_KEY", ignoreCase = true)
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

        _isRecording.value = true
        _interimTranscript.value = ""
        audioQueue.clear()
        _stats.value = LiveStats(setupCompleted = false)

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
        com.example.data.AppLogRepository.logEvent(
            com.example.data.DiagnosticSource.APP,
            com.example.data.DiagnosticType.SESSION_START,
            "Mode: $modeLabel, Model: ${_selectedModel.value}"
        )

        // 1. Immediately start AudioRecord on Dispatchers.IO to capture speech without clipping
        audioRecorder.start(
            viewModelScope,
            aecEnabled = _isAecEnabled.value,
            noiseSuppressorEnabled = _isNoiseSuppressorEnabled.value,
            source = com.example.data.DiagnosticSource.APP
        )

        // 2. Open Gemini Live WebSocket connection
        val customVocab = com.example.data.CustomVocabularyRepository.getVocabulary()
        try {
            webSocketClient.connect(apiKey, _selectedModel.value, _isSmartMode.value, customVocabulary = customVocab)
        } catch (e: Exception) {
            Log.e(TAG, "Failed connecting WebSocket: ${e.message}")
            addLog(LogLevel.ERROR, TAG, "Connection failed: ${e.message}")
            stopRecording(endedReason = "connection_error")
            return
        }

        // 3. Start recording duration timer
        durationJob?.cancel()
        durationJob = viewModelScope.launch {
            val start = System.currentTimeMillis()
            while (isActive && _isRecording.value) {
                delay(500)
                val elapsedSec = ((System.currentTimeMillis() - start) / 1000).toInt()
                _stats.update { it.copy(durationSeconds = elapsedSec) }
            }
        }
    }

    fun cancelSession() {
        val wasRecording = _isRecording.value
        val hasTranscript = _finalizedTranscript.value.isNotBlank() || _interimTranscript.value.isNotBlank()

        if (wasRecording) {
            stopRecording(endedReason = "user_cancelled")
        } else if (hasTranscript) {
            com.example.data.AppLogRepository.logEvent(
                com.example.data.DiagnosticSource.APP,
                com.example.data.DiagnosticType.SESSION_END,
                "ended_reason: user_cancelled"
            )
        }
        clearTranscript()
    }

    fun stopRecording(endedReason: String = "completed") {
        if (!_isRecording.value) return
        _isRecording.value = false
        durationJob?.cancel()

        addLog(LogLevel.INFO, TAG, "Stopping recording session ($endedReason)...")

        // 1. Stop mic capture
        audioRecorder.stop()
        _audioAmplitude.value = 0f

        val wl = appWakeLock
        appWakeLock = null

        // 2. Signal stream completion & close WS
        viewModelScope.launch(Dispatchers.IO) {
            val durationAtEnd = _stats.value.durationSeconds
            val chunksAtEnd = _stats.value.chunksSent
            val bytesAtEnd = _stats.value.bytesSent

            try {
                // Drain any remainder in queue
                drainAudioQueue()
                // Step D: Send audioStreamEnd
                webSocketClient.signalStreamEnd()
                delay(400) // Grace period for server to send final transcription frames
                webSocketClient.disconnect()
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping in-app session", e)
            } finally {
                _connectionState.value = ConnectionState.Idle
                addLog(LogLevel.INFO, TAG, "Session completed ($endedReason). Total chunks sent: $chunksAtEnd")
                com.example.data.AppLogRepository.logEvent(
                    com.example.data.DiagnosticSource.APP,
                    com.example.data.DiagnosticType.SESSION_END,
                    "ended_reason: $endedReason | Duration: ${durationAtEnd}s, Chunks: $chunksAtEnd, Streamed: ${bytesAtEnd / 1024} KB"
                )
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

    private fun drainAudioQueue() {
        drainJob?.cancel()
        drainJob = viewModelScope.launch(Dispatchers.IO) {
            var drainedCount = 0
            while (isActive && webSocketClient.setupComplete) {
                val chunk = audioQueue.poll() ?: break
                val sent = webSocketClient.sendAudioChunk(chunk)
                if (sent) {
                    drainedCount++
                    _stats.update {
                        it.copy(
                            chunksSent = it.chunksSent + 1,
                            bytesSent = it.bytesSent + chunk.size,
                            chunksBuffered = maxOf(0, it.chunksBuffered - 1)
                        )
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

    fun copyTranscript(context: Context): Boolean {
        val fullText = buildString {
            append(_finalizedTranscript.value)
            if (_interimTranscript.value.isNotEmpty()) {
                if (isNotEmpty()) append(" ")
                append(_interimTranscript.value)
            }
        }.trim()

        if (fullText.isEmpty()) return false

        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("VoxStream Transcript", fullText)
        clipboard.setPrimaryClip(clip)
        addLog(LogLevel.INFO, TAG, "Copied transcript to clipboard (${fullText.length} chars)")
        return true
    }

    private fun addLog(level: LogLevel, tag: String, message: String, payload: String? = null) {
        com.example.data.AppLogRepository.addLog(level, tag, message, payload)
    }

    fun clearLogs() {
        com.example.data.AppLogRepository.clearLiveFrames()
    }

    // Weekly Smart Vocabulary
    private val KEY_LAST_WEEKLY_CHECK_TIME = "last_weekly_smart_vocab_check_time"
    private val _smartVocabSuggestions = MutableStateFlow<List<com.example.service.SmartVocabularySuggestion>>(emptyList())
    val smartVocabSuggestions: StateFlow<List<com.example.service.SmartVocabularySuggestion>> = _smartVocabSuggestions.asStateFlow()

    private val _isAnalyzingSmartVocab = MutableStateFlow(false)
    val isAnalyzingSmartVocab: StateFlow<Boolean> = _isAnalyzingSmartVocab.asStateFlow()

    private val _smartVocabMessage = MutableStateFlow<String?>(null)
    val smartVocabMessage: StateFlow<String?> = _smartVocabMessage.asStateFlow()

    private val _showSmartVocabSheet = MutableStateFlow(false)
    val showSmartVocabSheet: StateFlow<Boolean> = _showSmartVocabSheet.asStateFlow()

    fun dismissSmartVocabSheet() {
        _showSmartVocabSheet.value = false
    }

    fun clearSmartVocabMessage() {
        _smartVocabMessage.value = null
    }

    fun acceptSmartVocabSuggestion(suggestion: com.example.service.SmartVocabularySuggestion) {
        com.example.data.CustomVocabularyRepository.addTerm(suggestion.term)
        _smartVocabSuggestions.update { list -> list.filter { it.id != suggestion.id } }
        addLog(LogLevel.INFO, TAG, "Accepted smart vocabulary suggestion: '${suggestion.term}'")
    }

    fun editAndAcceptSmartVocabSuggestion(suggestion: com.example.service.SmartVocabularySuggestion, newTerm: String) {
        val trimmed = newTerm.trim()
        if (trimmed.isNotBlank()) {
            com.example.data.CustomVocabularyRepository.addTerm(trimmed)
            _smartVocabSuggestions.update { list -> list.filter { it.id != suggestion.id } }
            addLog(LogLevel.INFO, TAG, "Edited & accepted smart vocabulary suggestion: '$trimmed'")
        }
    }

    fun dismissSmartVocabSuggestion(suggestion: com.example.service.SmartVocabularySuggestion) {
        _smartVocabSuggestions.update { list -> list.filter { it.id != suggestion.id } }
        addLog(LogLevel.INFO, TAG, "Dismissed smart vocabulary suggestion: '${suggestion.term}'")
    }

    fun acceptAllSmartVocabSuggestions() {
        val currentList = _smartVocabSuggestions.value
        currentList.forEach { suggestion ->
            com.example.data.CustomVocabularyRepository.addTerm(suggestion.term)
        }
        _smartVocabSuggestions.value = emptyList()
        addLog(LogLevel.INFO, TAG, "Accepted all (${currentList.size}) smart vocabulary suggestions")
    }

    fun simulateWeeklySmartVocabulary() {
        if (_isAnalyzingSmartVocab.value) return
        val sevenDaysAgo = System.currentTimeMillis() - (7 * 24 * 60 * 60 * 1000L)
        val recentTranscripts = com.example.data.HistoryRepository.historyItems.value.filter {
            it.timestamp >= sevenDaysAgo && it.text.isNotBlank()
        }

        if (recentTranscripts.isEmpty()) {
            _smartVocabMessage.value = "Not enough dictation history yet. Complete real voice typing in your apps over the week to generate smart suggestions!"
            return
        }

        val apiKey = effectiveApiKey
        if (apiKey.isBlank()) {
            _smartVocabMessage.value = "Gemini API key is not configured. Please set your API key in Settings or Secrets."
            return
        }

        _isAnalyzingSmartVocab.value = true
        _smartVocabMessage.value = null

        viewModelScope.launch {
            try {
                val existingVocab = com.example.data.CustomVocabularyRepository.getVocabulary()
                val result = com.example.service.SmartVocabularyService.analyzeWeeklyTranscripts(
                    apiKey = apiKey,
                    transcripts = recentTranscripts,
                    existingVocabulary = existingVocab
                )
                result.onSuccess { suggestions ->
                    _isAnalyzingSmartVocab.value = false
                    if (suggestions.isNotEmpty()) {
                        _smartVocabSuggestions.value = suggestions
                        _showSmartVocabSheet.value = true
                    } else {
                        _smartVocabMessage.value = "No new candidate terms found in the last 7 days of dictations."
                    }
                }.onFailure { err ->
                    _isAnalyzingSmartVocab.value = false
                    _smartVocabMessage.value = "Smart Vocabulary analysis failed: ${err.message}"
                }
            } catch (e: Exception) {
                _isAnalyzingSmartVocab.value = false
                _smartVocabMessage.value = "Error during analysis: ${e.message}"
            }
        }
    }

    fun checkWeeklySmartVocabularySchedule() {
        val lastCheck = prefs.getLong(KEY_LAST_WEEKLY_CHECK_TIME, 0L)
        val now = System.currentTimeMillis()
        val oneWeekMs = 7 * 24 * 60 * 60 * 1000L
        if (now - lastCheck >= oneWeekMs) {
            val sevenDaysAgo = now - oneWeekMs
            val recentTranscripts = com.example.data.HistoryRepository.historyItems.value.filter {
                it.timestamp >= sevenDaysAgo && it.text.isNotBlank()
            }
            if (recentTranscripts.size >= 3) {
                prefs.edit().putLong(KEY_LAST_WEEKLY_CHECK_TIME, now).apply()
                simulateWeeklySmartVocabulary()
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        audioRecorder.stop()
        webSocketClient.disconnect()
        durationJob?.cancel()
        drainJob?.cancel()
    }
}
