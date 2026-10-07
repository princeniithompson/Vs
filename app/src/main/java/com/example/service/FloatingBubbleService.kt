package com.example.service

import android.Manifest
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.core.ApiConfig
import com.example.data.AppLogRepository
import com.example.data.ConnectionState
import com.example.data.CustomVocabularyRepository
import com.example.data.DiagnosticSource
import com.example.data.DiagnosticType
import com.example.data.HistoryRepository
import com.example.service.floating.FloatingDictationSessionManager
import com.example.service.floating.FloatingHapticManager
import com.example.service.floating.FloatingHapticType
import com.example.service.floating.FloatingNotificationManager
import com.example.service.floating.FloatingOverlayWindowManager
import com.example.service.floating.FloatingPolishCoordinator
import com.example.service.floating.FloatingTextInjector
import com.example.websocket.GeminiLiveWebSocketClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Thin coordinator service for the floating voice bubble overlay.
 * Coordinates between overlay window management, dictation session recording,
 * AI polishing, and text injection.
 */
class FloatingBubbleService : Service() {

    companion object {
        private const val TAG = "FloatingBubbleService"

        var instance: FloatingBubbleService? = null
            private set
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var overlayWindowManager: FloatingOverlayWindowManager
    private lateinit var sessionManager: FloatingDictationSessionManager
    val polishCoordinator = FloatingPolishCoordinator()

    @androidx.annotation.VisibleForTesting(otherwise = androidx.annotation.VisibleForTesting.PRIVATE)
    internal var lastPolishClickTime: Long
        get() = polishCoordinator.lastPolishClickTime
        set(value) { polishCoordinator.lastPolishClickTime = value }

    @androidx.annotation.VisibleForTesting(otherwise = androidx.annotation.VisibleForTesting.PRIVATE)
    internal var polishDebounceJob: Job?
        get() = polishCoordinator.polishDebounceJob
        set(value) { polishCoordinator.polishDebounceJob = value }

    @Volatile
    private var isPendingInjectionOnSmartCompletion = false
    private var pendingCompletionTimeoutJob: Job? = null
    private var activeDetectionJob: Job? = null
    private var fgsDowngradeJob: Job? = null

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
        fgsDowngradeJob?.cancel()
        fgsDowngradeJob = null

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Microphone permission required. Please grant it in VoxStream.", Toast.LENGTH_LONG).show()
            val appIntent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(appIntent)
            return
        }

        val apiKey = getEffectiveApiKey()
        if (ApiConfig.isPlaceholder(apiKey)) {
            Toast.makeText(this, "Gemini API key is required. Please set it in VoxStream app first.", Toast.LENGTH_LONG).show()
            return
        }

        val notification = FloatingNotificationManager.buildNotification(this, "Listening...")
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    FloatingNotificationManager.NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    FloatingNotificationManager.NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } else {
                startForeground(FloatingNotificationManager.NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not startForeground with microphone service type: ${e.message}")
        }

        overlayWindowManager.expandToFullSize()
        FloatingBubbleManager.lockSessionContext(this)

        activeDetectionJob?.cancel()
        activeDetectionJob = null

        val a11y = VoxStreamAccessibilityService.instance
        val pkg = a11y?.getActivePackageName() ?: FloatingBubbleManager.currentForegroundPackage.value
        if (pkg != null && pkg != packageName && !com.example.util.AppResolutionEngine.defaultInstance.isSystemOrIme(this, pkg)) {
            activeDetectionJob = serviceScope.launch {
                try {
                    // 1. IMPLEMENT DEBOUNCING: Wait 500ms and verify foreground package remains stable
                    delay(500L)
                    val currentPkg = a11y?.getActivePackageName() ?: FloatingBubbleManager.currentForegroundPackage.value
                    if (currentPkg != pkg) {
                        Log.d("AppDetector", "Package name changed from $pkg to $currentPkg. Skipping detection.")
                        return@launch
                    }

                    // Collect multi-signal evidence (Stage A)
                    val evidence = com.example.util.AppResolutionEngine.defaultInstance.collectEvidence(
                        context = this@FloatingBubbleService,
                        packageName = pkg
                    )

                    // Run hybrid detection (Stage B local check -> Stage C Gemini only if needed)
                    val result = kotlinx.coroutines.withTimeout(20000L) {
                        AppDetector.detectAppHybrid(evidence, this@FloatingBubbleService)
                    }

                    // Log success
                    val resolutionSource = if (result.isLocallyResolved) "Local" else "Gemini"
                    Log.d("AppDetector", "Detection succeeded ($resolutionSource): ${result.appName} -> ${result.category}")
                    
                    // Show final result visibly (small label on the bubble and toast)
                    val formatted = "Detected: ${result.appName} -> ${result.category}"
                    FloatingBubbleManager.setLockedSessionContext(formatted)
                    Toast.makeText(this@FloatingBubbleService, formatted, Toast.LENGTH_SHORT).show()
                } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
                    val reason = "Detection timed out after 20s"
                    Log.d("AppDetector", "Detection failed: $reason")
                    FloatingBubbleManager.setLockedSessionContext("Detection failed: $reason")
                    Toast.makeText(this@FloatingBubbleService, "Detection failed: $reason", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    val reason = e.message ?: e.javaClass.simpleName
                    Log.d("AppDetector", "Detection failed: $reason")
                    FloatingBubbleManager.setLockedSessionContext("Detection failed: $reason")
                    Toast.makeText(this@FloatingBubbleService, "Detection failed: $reason", Toast.LENGTH_SHORT).show()
                }
            }
        } else {
            FloatingBubbleManager.setLockedSessionContext(null)
        }

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
        val selectedModel = prefs.getString("selected_model", GeminiLiveWebSocketClient.DEFAULT_MODEL) ?: GeminiLiveWebSocketClient.DEFAULT_MODEL

        sessionManager.startSession(
            context = this,
            scope = serviceScope,
            apiKey = apiKey,
            model = selectedModel,
            smartMode = isSmartMode
        )
    }

    private fun stopVoiceTyping(endedReason: String = "completed") {
        if (!sessionManager.isRecording) return
        FloatingHapticManager.trigger(this, FloatingHapticType.TRANSCRIPTION_STOP)
        FloatingBubbleManager.setRecordingState(false)
        overlayWindowManager.overlayRecording.value = false
        overlayWindowManager.resetInactivityTimer()

        sessionManager.stopSession(serviceScope, endedReason)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            fgsDowngradeJob?.cancel()
            fgsDowngradeJob = serviceScope.launch {
                delay(450L)
                try {
                    startForeground(
                        FloatingNotificationManager.NOTIFICATION_ID,
                        FloatingNotificationManager.buildNotification(this@FloatingBubbleService, "Floating voice bubble is active"),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Could not revert FGS to specialUse: ${e.message}")
                }
            }
        }
    }

    private fun onConfirmClicked() {
        if (overlayWindowManager.overlayPendingFinalizing.value) return
        overlayWindowManager.overlayPendingFinalizing.value = true

        if (sessionManager.isRecording) {
            FloatingBubbleManager.setRecordingState(false)
            overlayWindowManager.overlayRecording.value = false
            sessionManager.signalStreamEndOnly(serviceScope)
        }

        pendingCompletionTimeoutJob?.cancel()
        pendingCompletionTimeoutJob = serviceScope.launch {
            delay(1400L)
            if (isActive) {
                performInjectionAndClose()
            }
        }
    }

    private fun performInjectionAndClose() {
        val startInjectTime = android.os.SystemClock.elapsedRealtime()
        val textToInject = sessionManager.getFullTranscriptText()
        val durationSec = sessionManager.durationSeconds
        if (sessionManager.isRecording) {
            stopVoiceTyping()
        }

        if (textToInject.isNotBlank()) {
            val lockedCtx = FloatingBubbleManager.lockedSessionContext.value ?: "AI · VoxStream"
            HistoryRepository.addHistoryItem(
                text = textToInject,
                appContext = lockedCtx,
                durationSeconds = durationSec
            )
            FloatingTextInjector.injectOrFallbackToClipboard(this, textToInject)

            val elapsedMs = maxOf(300L, android.os.SystemClock.elapsedRealtime() - startInjectTime)
            val wordCount = textToInject.trim().split("\\s+".toRegex()).count { it.isNotBlank() }
            AppLogRepository.recordInjectionResult(
                durationMs = elapsedMs,
                wordCount = wordCount,
                targetApp = lockedCtx,
                transcriptText = textToInject,
                source = DiagnosticSource.BUBBLE
            )
        }

        resetAndCollapse()
    }

    private fun onPolishClicked() {
        val rawTranscript = sessionManager.getFullTranscriptText()
        val apiKey = getEffectiveApiKey()
        if (sessionManager.isRecording) {
            stopVoiceTyping()
        }

        polishCoordinator.handlePolishRequest(
            context = this,
            scope = serviceScope,
            rawTranscript = rawTranscript,
            apiKey = apiKey,
            onStartPolish = {
                overlayWindowManager.overlayPolishing.value = true
            },
            onFinishPolish = {
                overlayWindowManager.overlayPolishing.value = false
                resetAndCollapse()
            }
        )
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
        activeDetectionJob?.cancel()
        activeDetectionJob = null
        AppDetector.clearCache()

        FloatingBubbleManager.unlockSessionContext()
        overlayWindowManager.overlayPendingFinalizing.value = false
        overlayWindowManager.overlayRecording.value = false
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
                             polishCoordinator.isPolishingInProgress.get()
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
        return ApiConfig.getEffectiveKey(customKey)
    }

    private fun isBrowserOrContainerPackage(pkg: String): Boolean {
        val lower = pkg.lowercase(java.util.Locale.US)
        return lower == "com.google.android.googlequicksearchbox" ||
                lower.startsWith("org.chromium.webapk") ||
                lower.contains(".webapk") ||
                lower == "com.android.chrome" ||
                lower == "com.chrome.beta" ||
                lower == "com.chrome.dev" ||
                lower == "com.sec.android.app.sbrowser" ||
                lower == "com.microsoft.emmx" ||
                lower == "org.mozilla.firefox" ||
                lower == "com.brave.browser" ||
                lower == "com.opera.browser" ||
                lower.contains("chrome") ||
                lower.contains("browser") ||
                lower.contains("firefox") ||
                lower.contains("webkit")
    }

    private fun collectBrowserAndContainerEvidence(
        pkg: String,
        a11y: VoxStreamAccessibilityService?
    ): String? {
        if (a11y == null) return null
        val sb = StringBuilder()

        // 1. Window title
        val windowTitle = try {
            a11y.getActiveApplicationWindow()?.title?.toString()
        } catch (_: Throwable) {
            null
        }
        if (!windowTitle.isNullOrBlank()) {
            sb.append("Window title: ").append(windowTitle.trim())
        }

        // 2. Activity / class name
        val className = a11y.lastSeenClassName
        if (!className.isNullOrBlank()) {
            if (sb.isNotEmpty()) sb.append("\n")
            sb.append("Activity: ").append(className.trim())
        }

        // 3. Root node for URL and visible text samples
        val root = try {
            a11y.rootInActiveWindow
        } catch (_: Throwable) {
            null
        }

        if (root != null) {
            val url = findBrowserUrl(root)
            if (!url.isNullOrBlank()) {
                if (sb.isNotEmpty()) sb.append("\n")
                sb.append("URL: ").append(url.trim())
            }

            val visibleTexts = mutableListOf<String>()
            collectSampleVisibleTexts(root, visibleTexts, maxCount = 10, depth = 0, maxDepth = 6)
            if (visibleTexts.isNotEmpty()) {
                if (sb.isNotEmpty()) sb.append("\n")
                sb.append("Visible text samples:\n")
                visibleTexts.forEach { text ->
                    sb.append("- ").append(text).append("\n")
                }
            }
        }

        return if (sb.isNotEmpty()) sb.toString().trim() else null
    }

    private fun findBrowserUrl(rootNode: AccessibilityNodeInfo): String? {
        val urlViewIds = listOf(
            "com.android.chrome:id/url_bar",
            "com.chrome.beta:id/url_bar",
            "com.chrome.dev:id/url_bar",
            "com.sec.android.app.sbrowser:id/location_bar_edit_text",
            "com.microsoft.emmx:id/url_bar",
            "org.mozilla.firefox:id/url_bar_title",
            "url_bar",
            "location_bar",
            "search_box_text"
        )
        for (id in urlViewIds) {
            try {
                val nodes = rootNode.findAccessibilityNodeInfosByViewId(id)
                if (!nodes.isNullOrEmpty()) {
                    for (node in nodes) {
                        val text = node.text?.toString()?.trim()
                        if (!text.isNullOrBlank()) return text
                    }
                }
            } catch (_: Throwable) {}
        }
        return searchNodeForUrl(rootNode, 0, 6)
    }

    private fun searchNodeForUrl(node: AccessibilityNodeInfo?, depth: Int, maxDepth: Int): String? {
        if (node == null || depth > maxDepth) return null
        val text = node.text?.toString()?.trim() ?: ""
        if (text.isNotBlank()) {
            val lower = text.lowercase(java.util.Locale.US)
            if (lower.contains("aistudio") || lower.contains("ais-dev-") || lower.contains("ais-pre-") ||
                lower.contains(".google.com") || lower.contains(".ai") || lower.contains(".com/") ||
                lower.startsWith("http://") || lower.startsWith("https://")) {
                return text
            }
        }
        for (i in 0 until node.childCount) {
            val child = try { node.getChild(i) } catch (_: Throwable) { null } ?: continue
            val found = searchNodeForUrl(child, depth + 1, maxDepth)
            if (found != null) return found
        }
        return null
    }

    private fun collectSampleVisibleTexts(
        node: AccessibilityNodeInfo?,
        collected: MutableList<String>,
        maxCount: Int,
        depth: Int,
        maxDepth: Int
    ) {
        if (node == null || depth > maxDepth || collected.size >= maxCount) return
        val text = node.text?.toString()?.trim()
        val desc = node.contentDescription?.toString()?.trim()
        val candidate = when {
            !text.isNullOrBlank() && text.length > 2 -> text
            !desc.isNullOrBlank() && desc.length > 2 -> desc
            else -> null
        }
        if (candidate != null && !collected.contains(candidate)) {
            if (candidate.length <= 120) {
                collected.add(candidate)
            }
        }
        for (i in 0 until node.childCount) {
            if (collected.size >= maxCount) break
            val child = try { node.getChild(i) } catch (_: Throwable) { null } ?: continue
            collectSampleVisibleTexts(child, collected, maxCount, depth + 1, maxDepth)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "FloatingBubbleService onDestroy")

        activeDetectionJob?.cancel()
        activeDetectionJob = null
        AppDetector.clearCache()

        polishCoordinator.cancel()
        isPendingInjectionOnSmartCompletion = false
        pendingCompletionTimeoutJob?.cancel()
        pendingCompletionTimeoutJob = null
        fgsDowngradeJob?.cancel()
        fgsDowngradeJob = null

        if (::sessionManager.isInitialized) {
            stopVoiceTyping()
            sessionManager.release()
        }
        FloatingBubbleManager.setNetworkProblem(false)
        if (::overlayWindowManager.isInitialized) {
            overlayWindowManager.onDestroy()
        }
        serviceScope.cancel()

        if (instance == this) {
            instance = null
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.d(TAG, "FloatingBubbleService onTaskRemoved: executing deterministic teardown")
        if (::sessionManager.isInitialized) {
            stopVoiceTyping()
            sessionManager.release()
        }
        FloatingBubbleManager.setNetworkProblem(false)
    }
}
