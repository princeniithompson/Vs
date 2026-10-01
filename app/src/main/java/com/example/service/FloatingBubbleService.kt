package com.example.service

import android.Manifest
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.example.BuildConfig
import com.example.MainActivity
import com.example.data.AppLogRepository
import com.example.data.ConnectionState
import com.example.data.CustomVocabularyRepository
import com.example.data.DiagnosticSource
import com.example.data.DiagnosticType
import com.example.data.HistoryRepository
import com.example.data.LogLevel
import com.example.service.floating.FloatingDictationSessionManager
import com.example.service.floating.FloatingHapticManager
import com.example.service.floating.FloatingHapticType
import com.example.service.floating.FloatingNotificationManager
import com.example.service.floating.FloatingOverlayWindowManager
import com.example.service.floating.FloatingPolishClient
import com.example.service.floating.PolishResult
import com.example.websocket.GeminiLiveWebSocketClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

class FloatingBubbleService : Service() {

    companion object {
        private const val TAG = "FloatingBubbleService"

        var instance: FloatingBubbleService? = null
            private set
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var overlayWindowManager: FloatingOverlayWindowManager
    private lateinit var sessionManager: FloatingDictationSessionManager

    private val isPolishingInProgress = AtomicBoolean(false)
    @androidx.annotation.VisibleForTesting(otherwise = androidx.annotation.VisibleForTesting.PRIVATE)
    internal var lastPolishClickTime = 0L
    private val polishDebounceMs = 800L
    @androidx.annotation.VisibleForTesting(otherwise = androidx.annotation.VisibleForTesting.PRIVATE)
    internal var polishDebounceJob: Job? = null

    @Volatile
    private var isPendingInjectionOnSmartCompletion = false
    private var pendingCompletionTimeoutJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundNotification()
        return START_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.d(TAG, "FloatingBubbleService onCreate")
        CustomVocabularyRepository.init(this)

        FloatingNotificationManager.createChannel(this)
        startForegroundNotification()

        overlayWindowManager = FloatingOverlayWindowManager(this)
        sessionManager = FloatingDictationSessionManager(
            onTranscriptUpdated = { fullText ->
                overlayWindowManager.overlayTranscript.value = fullText
                FloatingBubbleManager.updateSmartDefaultPolishMode(fullText)
            },
            onInterimReceived = { _ -> },
            onFinalSegmentReceived = { _ ->
                FloatingHapticManager.trigger(this, FloatingHapticType.FINAL_SENTENCE)
                if (isPendingInjectionOnSmartCompletion) {
                    Log.d(TAG, "Final transcript segment arrived while awaiting completion -> injecting")
                    isPendingInjectionOnSmartCompletion = false
                    pendingCompletionTimeoutJob?.cancel()
                    pendingCompletionTimeoutJob = null
                    performInjectionAndClose()
                }
            },
            onConnectionStateChanged = { state ->
                if (state is ConnectionState.Error && isPendingInjectionOnSmartCompletion) {
                    isPendingInjectionOnSmartCompletion = false
                    pendingCompletionTimeoutJob?.cancel()
                    pendingCompletionTimeoutJob = null
                    performInjectionAndClose()
                }
            },
            onAmplitudeChanged = { amp ->
                overlayWindowManager.overlayAudioAmplitude.value = amp
            },
            onError = { err ->
                val isPingTimeout = err.contains("ping", ignoreCase = true) ||
                                   err.contains("pong", ignoreCase = true) ||
                                   err.contains("timeout", ignoreCase = true)
                val endedReasonTag = if (isPingTimeout) "ping_timeout" else "connection_drop"

                AppLogRepository.logEvent(
                    DiagnosticSource.BUBBLE,
                    DiagnosticType.ERROR,
                    err
                )
                if (sessionManager.isRecording) {
                    stopVoiceTyping(endedReason = endedReasonTag)
                }
            },
            onDurationTicked = { _ -> }
        )

        try {
            overlayWindowManager.initOverlay(
                onRingClick = { onRingClicked() },
                onCancelClick = { onCancelClicked() },
                onPolishClick = { onPolishClicked() },
                onCompleteClick = { onConfirmClicked() }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing overlay window", e)
        }
    }

    private fun startForegroundNotification() {
        val notification = FloatingNotificationManager.buildNotification(this, "Floating voice bubble is active")
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    FloatingNotificationManager.NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(FloatingNotificationManager.NOTIFICATION_ID, notification, 0)
            } else {
                startForeground(FloatingNotificationManager.NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to startForeground", e)
        }
    }

    fun onSensitiveAppEntered(pkg: String?) {
        serviceScope.launch(Dispatchers.Main) {
            val wasRecording = sessionManager.isRecording
            if (wasRecording) {
                stopVoiceTyping(endedReason = "smart_safe_mode_triggered")
                Toast.makeText(
                    this@FloatingBubbleService,
                    "🛡️ Smart Safe Mode: Dictation paused for privacy",
                    Toast.LENGTH_SHORT
                ).show()
            }
            overlayWindowManager.collapsePanel()
            resetAndCollapse()
            AppLogRepository.logEvent(
                DiagnosticSource.BUBBLE,
                DiagnosticType.SAFE_MODE_TRIGGERED,
                "Smart Safe Mode engaged for package: $pkg"
            )
        }
    }

    private fun onRingClicked() {
        if (FloatingBubbleManager.isCurrentAppSensitive.value) {
            FloatingHapticManager.trigger(this, FloatingHapticType.BUBBLE_HOLD)
            Toast.makeText(
                this,
                "🛡️ Smart Safe Mode Active · Dictation is paused in sensitive apps for your privacy.",
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        if (overlayWindowManager.overlayShrunk.value) {
            overlayWindowManager.expandToFullSize()
            FloatingHapticManager.trigger(this, FloatingHapticType.BUBBLE_HOLD)
            overlayWindowManager.resetInactivityTimer()
            return
        }
        if (!sessionManager.isRecording && !overlayWindowManager.overlayExpanded.value) {
            overlayWindowManager.expandToFullSize()
            overlayWindowManager.expandPanel()
            startVoiceTyping()
        } else if (!sessionManager.isRecording) {
            startVoiceTyping()
        } else {
            FloatingHapticManager.trigger(this, FloatingHapticType.BUBBLE_HOLD)
        }
    }

    private fun startVoiceTyping() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Microphone permission required. Please grant it in VoxStream.", Toast.LENGTH_LONG).show()
            val appIntent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(appIntent)
            return
        }

        val apiKey = getEffectiveApiKey()
        if (apiKey.isBlank() || apiKey.trim().equals("MY_GEMINI_API_KEY", ignoreCase = true)) {
            Toast.makeText(this, "Gemini API key is required. Please set it in VoxStream app first.", Toast.LENGTH_LONG).show()
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            try {
                startForeground(
                    FloatingNotificationManager.NOTIFICATION_ID,
                    FloatingNotificationManager.buildNotification(this, "Listening..."),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } catch (e: Exception) {
                Log.w(TAG, "Could not upgrade FGS to microphone: ${e.message}")
            }
        }

        overlayWindowManager.expandToFullSize()
        FloatingBubbleManager.lockSessionContext(this)
        FloatingBubbleManager.setRecordingState(true)
        FloatingHapticManager.trigger(this, FloatingHapticType.TRANSCRIPTION_START)
        overlayWindowManager.expandPanel()

        isPendingInjectionOnSmartCompletion = false
        overlayWindowManager.overlayPendingFinalizing.value = false
        pendingCompletionTimeoutJob?.cancel()
        pendingCompletionTimeoutJob = null
        overlayWindowManager.overlayRecording.value = true

        val prefs = getSharedPreferences("voxstream_settings", Context.MODE_PRIVATE)
        val isSmartMode = prefs.getBoolean("smart_mode", false)
        val isAecEnabled = prefs.getBoolean("aec_enabled", true)
        val isNoiseSuppressorEnabled = prefs.getBoolean("noise_suppressor_enabled", true)
        val selectedModel = prefs.getString("selected_model", GeminiLiveWebSocketClient.DEFAULT_MODEL) ?: GeminiLiveWebSocketClient.DEFAULT_MODEL

        sessionManager.startSession(
            context = this,
            scope = serviceScope,
            apiKey = apiKey,
            model = selectedModel,
            smartMode = isSmartMode,
            aecEnabled = isAecEnabled,
            noiseSuppressorEnabled = isNoiseSuppressorEnabled
        )
    }

    private fun stopVoiceTyping(endedReason: String = "completed") {
        if (!sessionManager.isRecording) return
        FloatingHapticManager.trigger(this, FloatingHapticType.TRANSCRIPTION_STOP)
        FloatingBubbleManager.setRecordingState(false)
        overlayWindowManager.overlayRecording.value = false
        overlayWindowManager.resetInactivityTimer()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            try {
                startForeground(
                    FloatingNotificationManager.NOTIFICATION_ID,
                    FloatingNotificationManager.buildNotification(this, "Floating voice bubble is active"),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } catch (e: Exception) {
                Log.w(TAG, "Could not revert FGS to specialUse: ${e.message}")
            }
        }

        sessionManager.stopSession(serviceScope, endedReason)
    }

    private fun onConfirmClicked() {
        if (overlayWindowManager.overlayPendingFinalizing.value) return
        overlayWindowManager.overlayPendingFinalizing.value = true

        if (sessionManager.isRecording) {
            FloatingBubbleManager.setRecordingState(false)
            overlayWindowManager.overlayRecording.value = false
            sessionManager.signalStreamEndOnly(serviceScope)
        }

        serviceScope.launch {
            delay(1400L)
            performInjectionAndClose()
        }
    }

    private fun performInjectionAndClose() {
        val textToInject = sessionManager.getFullTranscriptText()
        if (sessionManager.isRecording) {
            stopVoiceTyping()
        }

        if (textToInject.isNotBlank()) {
            val lockedCtx = FloatingBubbleManager.lockedSessionContext.value ?: "AI · VoxStream"
            HistoryRepository.addHistoryItem(
                text = textToInject,
                appContext = lockedCtx,
                durationSeconds = sessionManager.durationSeconds
            )
            FloatingBubbleManager.injectOrFallbackToClipboard(this, textToInject)
        }

        resetAndCollapse()
    }

    private fun onPolishClicked() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastPolishClickTime < polishDebounceMs) {
            Log.d(TAG, "Ignoring rapid Polish tap (debounced)")
            return
        }
        lastPolishClickTime = now

        if (!isPolishingInProgress.compareAndSet(false, true)) {
            Log.d(TAG, "Polish operation already in progress")
            return
        }

        val rawTranscript = sessionManager.getFullTranscriptText()
        if (rawTranscript.isBlank()) {
            Toast.makeText(this, "No text to polish", Toast.LENGTH_SHORT).show()
            isPolishingInProgress.set(false)
            resetAndCollapse()
            return
        }

        overlayWindowManager.overlayPolishing.value = true
        val polishStartTime = SystemClock.elapsedRealtime()

        if (sessionManager.isRecording) {
            stopVoiceTyping()
        }

        polishDebounceJob?.cancel()
        polishDebounceJob = serviceScope.launch {
            try {
                val apiKey = getEffectiveApiKey()
                var result: PolishResult? = null

                AppLogRepository.addLog(
                    LogLevel.INFO,
                    "PolishAPI",
                    "User tapped Polish ✨ for transcript (${rawTranscript.length} chars)",
                    rawTranscript
                )
                AppLogRepository.logEvent(
                    DiagnosticSource.BUBBLE,
                    DiagnosticType.POLISH_CALLED,
                    "Transcript length: ${rawTranscript.length} chars"
                )

                if (apiKey.isBlank() || apiKey.trim().equals("MY_GEMINI_API_KEY", ignoreCase = true)) {
                    val err = "API key is missing or default placeholder. Please set GEMINI_API_KEY in app Settings."
                    Log.e(TAG, "Polish Error: $err")
                    AppLogRepository.addLog(LogLevel.ERROR, "PolishAPI", err)
                    AppLogRepository.logEvent(
                        DiagnosticSource.BUBBLE,
                        DiagnosticType.POLISH_FAILED,
                        err
                    )
                    result = PolishResult(null, err)
                } else {
                    val currentPkg = FloatingBubbleManager.currentForegroundPackage.value
                    val appContext = AppContextResolver.resolve(this@FloatingBubbleService, currentPkg)
                    val category = AppClassifier.classify(currentPkg, appContext?.appName)
                    val appName = appContext?.appName ?: "App"
                    val isAiApp = category == AppCategory.AI
                    val aiMode = if (isAiApp) FloatingBubbleManager.selectedAiPolishMode.value else null
                    val contextSnippet = if (isAiApp) VoxStreamAccessibilityService.instance?.extractRecentConversationContext() else null

                    withContext(Dispatchers.IO) {
                        result = FloatingPolishClient.polishTranscript(
                            apiKey = apiKey,
                            rawTranscript = rawTranscript,
                            category = category,
                            appName = appName,
                            aiPolishMode = aiMode,
                            conversationContext = contextSnippet
                        )
                    }
                }

                val elapsed = SystemClock.elapsedRealtime() - polishStartTime
                if (elapsed < 1400L) {
                    delay(1400L - elapsed)
                }

                val polishedText = result?.text
                if (!polishedText.isNullOrBlank()) {
                    AppLogRepository.addLog(LogLevel.INFO, "PolishAPI", "Polish completed successfully!", polishedText)
                    AppLogRepository.logEvent(
                        DiagnosticSource.BUBBLE,
                        DiagnosticType.POLISH_SUCCESS,
                        "Result: ${polishedText.take(60)}..."
                    )
                    FloatingBubbleManager.injectOrFallbackToClipboard(this@FloatingBubbleService, polishedText)
                    Toast.makeText(this@FloatingBubbleService, "Polished ✨", Toast.LENGTH_SHORT).show()
                } else {
                    val fallbackText = sessionManager.getFullTranscriptText()
                    if (fallbackText.isNotBlank()) {
                        FloatingBubbleManager.injectOrFallbackToClipboard(this@FloatingBubbleService, fallbackText)
                    }
                    val errorMsg = result?.errorDetail ?: "Unknown error"
                    Log.e(TAG, "Polish failed: $errorMsg")
                    AppLogRepository.addLog(LogLevel.ERROR, "PolishAPI", "Polish failed completely: $errorMsg")
                    AppLogRepository.logEvent(
                        DiagnosticSource.BUBBLE,
                        DiagnosticType.POLISH_FAILED,
                        errorMsg
                    )
                    Toast.makeText(this@FloatingBubbleService, "Polish failed: $errorMsg", Toast.LENGTH_LONG).show()
                }
            } finally {
                overlayWindowManager.overlayPolishing.value = false
                isPolishingInProgress.set(false)
                resetAndCollapse()
            }
        }
    }

    private fun onCancelClicked() {
        val durationAtEnd = sessionManager.durationSeconds
        val chunksAtEnd = sessionManager.sessionChunksSent

        overlayWindowManager.overlayPendingFinalizing.value = false
        isPendingInjectionOnSmartCompletion = false
        pendingCompletionTimeoutJob?.cancel()
        pendingCompletionTimeoutJob = null

        val wasRecording = sessionManager.isRecording
        if (wasRecording) {
            stopVoiceTyping(endedReason = "user_cancelled")
        } else {
            AppLogRepository.logEvent(
                DiagnosticSource.BUBBLE,
                DiagnosticType.SESSION_END,
                "ended_reason: user_cancelled | Duration: ${durationAtEnd}s, Chunks: $chunksAtEnd"
            )
        }

        Toast.makeText(this, "Voice typing cancelled", Toast.LENGTH_SHORT).show()
        resetAndCollapse()
    }

    private fun resetAndCollapse() {
        FloatingBubbleManager.unlockSessionContext()
        overlayWindowManager.overlayPendingFinalizing.value = false
        overlayWindowManager.overlayRecording.value = false
        isPolishingInProgress.set(false)
        isPendingInjectionOnSmartCompletion = false
        pendingCompletionTimeoutJob?.cancel()
        pendingCompletionTimeoutJob = null

        sessionManager.clearTranscripts()
        overlayWindowManager.collapsePanel()
        overlayWindowManager.updateOverlayVisibility(isSessionActive = false)
    }

    fun onKeyboardVisibilityChanged(isVisible: Boolean) {
        val isSessionActive = sessionManager.isRecording ||
                             overlayWindowManager.overlayPendingFinalizing.value ||
                             isPolishingInProgress.get()
        overlayWindowManager.onKeyboardVisibilityChanged(isVisible, isSessionActive)
    }

    fun onFieldFocusChanged(isFocused: Boolean) {
        // No-op: Visibility is strictly governed by TYPE_INPUT_METHOD window presence in onKeyboardVisibilityChanged
    }

    fun attachToAccessibilityService(accessService: VoxStreamAccessibilityService) {
        overlayWindowManager.attachToAccessibilityService(accessService)
    }

    private fun getEffectiveApiKey(): String {
        val prefs = getSharedPreferences("voxstream_settings", Context.MODE_PRIVATE)
        val customKey = prefs.getString("custom_api_key", "")?.trim() ?: ""
        if (customKey.isNotBlank()) return customKey

        val buildKey = BuildConfig.GEMINI_API_KEY.trim()
        if (buildKey.isNotBlank() && !buildKey.equals("MY_GEMINI_API_KEY", ignoreCase = true)) return buildKey

        return ""
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "FloatingBubbleService onDestroy")

        polishDebounceJob?.cancel()
        polishDebounceJob = null
        lastPolishClickTime = 0L
        isPolishingInProgress.set(false)

        isPendingInjectionOnSmartCompletion = false
        pendingCompletionTimeoutJob?.cancel()
        pendingCompletionTimeoutJob = null

        if (::sessionManager.isInitialized) {
            stopVoiceTyping()
            sessionManager.release()
        }
        if (::overlayWindowManager.isInitialized) {
            overlayWindowManager.onDestroy()
        }
        serviceScope.cancel()

        if (instance == this) {
            instance = null
        }
    }
}
