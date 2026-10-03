package com.example.service.floating

import android.content.Context
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import com.example.core.ApiConfig
import com.example.data.AppLogRepository
import com.example.data.DiagnosticSource
import com.example.data.DiagnosticType
import com.example.data.LogLevel
import com.example.service.AppCategory
import com.example.service.AppClassifier
import com.example.service.AppContextResolver
import com.example.service.FloatingBubbleManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Coordinates AI polishing for dictated transcripts:
 * - Tap debouncing and concurrency guarding
 * - Target application context resolution
 * - FloatingPolishClient invocation with category heuristics
 * - Diagnostics logging and automatic text injection fallback
 */
class FloatingPolishCoordinator {

    companion object {
        private const val TAG = "FloatingPolishCoord"
        private const val POLISH_DEBOUNCE_MS = 800L
        private const val MIN_POLISH_DISPLAY_TIME_MS = 1400L
    }

    val isPolishingInProgress = AtomicBoolean(false)

    @androidx.annotation.VisibleForTesting(otherwise = androidx.annotation.VisibleForTesting.PRIVATE)
    internal var lastPolishClickTime = 0L

    @androidx.annotation.VisibleForTesting(otherwise = androidx.annotation.VisibleForTesting.PRIVATE)
    internal var polishDebounceJob: Job? = null

    fun handlePolishRequest(
        context: Context,
        scope: CoroutineScope,
        rawTranscript: String,
        apiKey: String,
        onStartPolish: () -> Unit,
        onFinishPolish: () -> Unit
    ) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastPolishClickTime < POLISH_DEBOUNCE_MS) {
            Log.d(TAG, "Ignoring rapid Polish tap (debounced)")
            return
        }
        lastPolishClickTime = now

        if (!isPolishingInProgress.compareAndSet(false, true)) {
            Log.d(TAG, "Polish operation already in progress")
            return
        }

        if (rawTranscript.isBlank()) {
            Toast.makeText(context, "No text to polish", Toast.LENGTH_SHORT).show()
            isPolishingInProgress.set(false)
            onFinishPolish()
            return
        }

        onStartPolish()
        val polishStartTime = SystemClock.elapsedRealtime()

        polishDebounceJob?.cancel()
        polishDebounceJob = scope.launch {
            try {
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

                if (ApiConfig.isPlaceholder(apiKey)) {
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
                    val category = if (com.example.config.VoxStreamConfig.IS_APP_DETECTION_ENABLED) {
                        val appContext = AppContextResolver.resolve(context, currentPkg)
                        AppClassifier.classify(currentPkg, appContext?.appName)
                    } else {
                        AppCategory.OTHER
                    }
                    val appName = if (com.example.config.VoxStreamConfig.IS_APP_DETECTION_ENABLED) {
                        AppContextResolver.resolve(context, currentPkg)?.appName ?: "App"
                    } else "App"
                    val isAiApp = category == AppCategory.AI
                    val aiMode = if (isAiApp) FloatingBubbleManager.selectedAiPolishMode.value else null

                    withContext(Dispatchers.IO) {
                        result = FloatingPolishClient.polishTranscript(
                            apiKey = apiKey,
                            rawTranscript = rawTranscript,
                            category = category,
                            appName = appName,
                            aiPolishMode = aiMode
                        )
                    }
                }

                val elapsed = SystemClock.elapsedRealtime() - polishStartTime
                if (elapsed < MIN_POLISH_DISPLAY_TIME_MS) {
                    delay(MIN_POLISH_DISPLAY_TIME_MS - elapsed)
                }

                val polishedText = result?.text
                if (!polishedText.isNullOrBlank()) {
                    AppLogRepository.addLog(LogLevel.INFO, "PolishAPI", "Polish completed successfully!", polishedText)
                    AppLogRepository.logEvent(
                        DiagnosticSource.BUBBLE,
                        DiagnosticType.POLISH_SUCCESS,
                        "Result: ${polishedText.take(60)}..."
                    )
                    FloatingTextInjector.injectOrFallbackToClipboard(context, polishedText)
                    Toast.makeText(context, "Polished ✨", Toast.LENGTH_SHORT).show()
                } else {
                    if (rawTranscript.isNotBlank()) {
                        FloatingTextInjector.injectOrFallbackToClipboard(context, rawTranscript)
                    }
                    val errorMsg = result?.errorDetail ?: "Unknown error"
                    Log.e(TAG, "Polish failed: $errorMsg")
                    AppLogRepository.addLog(LogLevel.ERROR, "PolishAPI", "Polish failed completely: $errorMsg")
                    AppLogRepository.logEvent(
                        DiagnosticSource.BUBBLE,
                        DiagnosticType.POLISH_FAILED,
                        errorMsg
                    )
                    Toast.makeText(context, "Polish failed: $errorMsg", Toast.LENGTH_LONG).show()
                }
            } finally {
                isPolishingInProgress.set(false)
                onFinishPolish()
            }
        }
    }

    fun cancel() {
        polishDebounceJob?.cancel()
        polishDebounceJob = null
        lastPolishClickTime = 0L
        isPolishingInProgress.set(false)
    }
}
