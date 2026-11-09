package com.princeniithompson.rapcha.service.floating

import android.content.Context
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import com.princeniithompson.rapcha.core.ApiConfig
import com.princeniithompson.rapcha.data.AppLogRepository
import com.princeniithompson.rapcha.data.DiagnosticSource
import com.princeniithompson.rapcha.data.DiagnosticType
import com.princeniithompson.rapcha.data.CaptureMode
import com.princeniithompson.rapcha.data.HistoryRepository
import com.princeniithompson.rapcha.data.VoiceSessionBuilder
import com.princeniithompson.rapcha.data.LogLevel
import com.princeniithompson.rapcha.service.AppCategory
import com.princeniithompson.rapcha.service.AppClassifier
import com.princeniithompson.rapcha.service.AppContextResolver
import com.princeniithompson.rapcha.service.FloatingBubbleManager
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
        onFinishPolish: () -> Unit,
        targetPackage: String? = null,
        targetHint: String? = null,
        lockedCategory: AppCategory? = null,
        lockedAppName: String? = null,
        stage1: Stage1Capture = Stage1Capture.VERBATIM
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
                    com.princeniithompson.rapcha.data.TranscriptPrivacy.summary(rawTranscript)
                )
                AppLogRepository.logEvent(
                    DiagnosticSource.BUBBLE,
                    DiagnosticType.POLISH_CALLED,
                    "Transcript length: ${rawTranscript.length} chars"
                )

                val resolved = when {
                    lockedCategory != null -> PolishTarget(lockedCategory, lockedAppName ?: "App")
                    com.princeniithompson.rapcha.config.RapchaConfig.IS_APP_DETECTION_ENABLED ->
                        resolvePolishTarget(context, targetPackage, targetHint)
                    else -> PolishTarget(AppCategory.OTHER, "App")
                }
                val category = resolved.category
                val targetAppName = resolved.appName

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
                    val isAiApp = category == AppCategory.AI
                    val aiMode = if (isAiApp) FloatingBubbleManager.selectedAiPolishMode.value else null

                    withContext(Dispatchers.IO) {
                        result = FloatingPolishClient.polishTranscript(
                            apiKey = apiKey,
                            rawTranscript = rawTranscript,
                            category = category,
                            appName = targetAppName,
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
                    AppLogRepository.addLog(
                        LogLevel.INFO,
                        "PolishAPI",
                        "Polish completed successfully!",
                        com.princeniithompson.rapcha.data.TranscriptPrivacy.summary(polishedText)
                    )
                    AppLogRepository.logEvent(
                        DiagnosticSource.BUBBLE,
                        DiagnosticType.POLISH_SUCCESS,
                        "Result: ${com.princeniithompson.rapcha.data.TranscriptPrivacy.summary(polishedText)}"
                    )
                    HistoryRepository.saveVoiceSession(
                        VoiceSessionBuilder.build(
                            sessionId = java.util.UUID.randomUUID().toString(),
                            timestamp = System.currentTimeMillis(),
                            appName = targetAppName,
                            stage1 = if (stage1 == Stage1Capture.SMART) CaptureMode.SMART else CaptureMode.VERBATIM,
                            threeStep = false,
                            usedLiveModel = false,
                            aiLyra = false,
                            stage1Text = rawTranscript,
                            deliveredText = polishedText,
                            lyraText = null,
                            unaryPolishText = polishedText,
                            sensitive = false
                        )
                    )
                    val placed = FloatingTextInjector.injectReporting(context, polishedText)
                    VocabSuggestions.onConfirmedPlacement(context, polishedText, placed)
                    Toast.makeText(context, "Polished ✨", Toast.LENGTH_SHORT).show()
                } else {
                    if (rawTranscript.isNotBlank()) {
                        HistoryRepository.saveVoiceSession(
                            VoiceSessionBuilder.build(
                                sessionId = java.util.UUID.randomUUID().toString(),
                                timestamp = System.currentTimeMillis(),
                                appName = targetAppName,
                                stage1 = if (stage1 == Stage1Capture.SMART) CaptureMode.SMART else CaptureMode.VERBATIM,
                                threeStep = false,
                                usedLiveModel = false,
                                aiLyra = false,
                                stage1Text = rawTranscript,
                                deliveredText = rawTranscript,
                                lyraText = null,
                                unaryPolishText = null,
                                sensitive = false
                            )
                        )
                        val placed = FloatingTextInjector.injectReporting(context, rawTranscript)
                        VocabSuggestions.onConfirmedPlacement(context, rawTranscript, placed)
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

    data class Stage2Target(
        val stage2Category: Stage2Category,
        val polishCategory: AppCategory,
        val appName: String
    )

    fun stage2Target(
        context: Context,
        targetPackage: String?,
        targetHint: String?,
        allowLiveHint: Boolean = true
    ): Stage2Target {
        val hint = when {
            targetHint != null -> targetHint
            allowLiveHint -> FloatingBubbleManager.lockedSessionContext.value
            else -> null
        }
        val pkg = if (looksLikePackage(targetPackage)) {
            targetPackage
        } else if (allowLiveHint) {
            listOf(FloatingBubbleManager.currentForegroundPackage.value).firstOrNull { looksLikePackage(it) }
        } else {
            null
        }
        val resolvedName = if (pkg != null) {
            try {
                AppContextResolver.resolve(context, pkg)?.appName
            } catch (_: Throwable) {
                null
            }
        } else {
            null
        }
        val registryName = pkg?.let { com.princeniithompson.rapcha.service.AppRegistry.NATIVE_APP_MAP[it]?.appName }
        val lookedUp = resolvedName ?: registryName
        val stage2 = Stage2CategoryMapper.map(pkg, lookedUp, hint)
        val classified = when {
            pkg != null -> AppClassifier.classify(pkg, lookedUp)
            else -> AppClassifier.classify(null, hint)
        }
        val polishCategory = if (stage2 == Stage2Category.AI) AppCategory.AI_CHAT else classified
        val appName = lookedUp ?: hint?.takeIf { it.isNotBlank() } ?: "App"
        return Stage2Target(stage2, polishCategory, appName)
    }

    private data class PolishTarget(val category: AppCategory, val appName: String)

    /**
     * Prefer the package captured when the session started. The locked context
     * string is a label such as "Detected: ChatGPT -> AI", not a package name.
     * AI apps use Lyra. Other categories keep the existing spice card.
     */
    private fun resolvePolishTarget(context: Context, targetPackage: String?, targetHint: String?): PolishTarget {
        val target = stage2Target(context, targetPackage, targetHint)
        return PolishTarget(target.polishCategory, target.appName)
    }

    private fun looksLikePackage(value: String?): Boolean {
        if (value.isNullOrBlank()) return false
        if (value.any { it.isWhitespace() }) return false
        return value.contains('.')
    }

    fun cancel() {
        polishDebounceJob?.cancel()
        polishDebounceJob = null
        lastPolishClickTime = 0L
        isPolishingInProgress.set(false)
    }
}
