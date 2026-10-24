package com.princeniithompson.rapcha.service.floating

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethod
import com.princeniithompson.rapcha.data.AppLogRepository
import com.princeniithompson.rapcha.data.DiagnosticSource
import com.princeniithompson.rapcha.data.DiagnosticType
import com.princeniithompson.rapcha.data.InjectionEvent
import com.princeniithompson.rapcha.data.InjectionLogRepository
import com.princeniithompson.rapcha.service.AppContextResolver
import com.princeniithompson.rapcha.service.FloatingBubbleManager
import com.princeniithompson.rapcha.service.RapchaAccessibilityService
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Production-ready text insertion engine for Rapcha based on Whisperflow's architecture:
 * 1. Mutex-protected insertion loop ensuring zero race conditions during rapid typing/dictation.
 * 2. Asks the accessibility service to insert, in order: API 33+ input connection, companion IME, then a caret-aware node write.
 * 3. Never reads or writes the clipboard while any of those targets exists.
 * 4. Copies to the clipboard only when no target exists on screen.
 * 5. Silent copy with subtle floating ring indicator on fallback (zero intrusive toasts).
 */
class RapchaTextInserter(private val context: Context) {

    // 1. Mutex to prevent race conditions during rapid typing/dictation
    private val insertionMutex = Mutex()
    private var lastDirectMethod: String = "failed"

    sealed interface DirectInjectionResult {
        object Success : DirectInjectionResult
        data class FocusedFieldFailed(val reason: String) : DirectInjectionResult
        object NoFocusedField : DirectInjectionResult
    }

    /**
     * The Main Entry Point:
     * 1. Inserts via the accessibility service priority (input connection, companion IME, then node write).
     * 2. Only falls back to clipboard when there is no target at all.
     * 3. Never uses clipboard if a focused editable field exists (even if the write fails).
     */
    suspend fun insertTranscript(
        transcript: String,
        inputMethod: Any? = null
    ): InsertionResult = insertionMutex.withLock {
        if (transcript.isBlank()) {
            return@withLock InsertionResult.Failed
        }

        // Step A: Attempt direct caret-level injection
        val directResult = tryDirectInjection(transcript, inputMethod)

        when (directResult) {
            is DirectInjectionResult.Success -> {
                Log.d(TAG, "Direct injection succeeded")
                logDiagnosticInsertion(lastDirectMethod, success = true)
                return@withLock InsertionResult.SuccessDirect
            }
            is DirectInjectionResult.FocusedFieldFailed -> {
                // Requirement 3: Never use clipboard if a focused editable field exists (even if the write fails)
                Log.w(TAG, "Focused editable field exists but write failed: ${directResult.reason}. Clipboard fallback strictly suppressed.")
                logDiagnosticInsertion(lastDirectMethod, success = false)
                return@withLock InsertionResult.Failed
            }
            is DirectInjectionResult.NoFocusedField -> {
                // Requirement 2: Only fall back to clipboard when there is NO focused editable text field at all
                Log.d(TAG, "No focused editable text field detected on screen. Falling back to clipboard.")
                val fallbackSuccess = fallbackToClipboard(transcript)
                logDiagnosticInsertion(if (fallbackSuccess) "clipboard" else "failed", success = fallbackSuccess)
                return@withLock if (fallbackSuccess) {
                    InsertionResult.SuccessClipboardFallback
                } else {
                    InsertionResult.Failed
                }
            }
        }
    }

    /**
     * Overload supporting explicit InputMethod parameter as in the Whisperflow pattern.
     */
    suspend fun insertTranscript(
        transcript: String,
        inputMethod: InputMethod?
    ): InsertionResult = insertTranscript(transcript, inputMethod as Any?)

    /**
     * Overload supporting explicit InputConnection parameter for testing or direct IME pipelines.
     */
    suspend fun insertTranscript(
        transcript: String,
        inputConnection: InputConnection?,
        inputMethod: Any?
    ): InsertionResult = insertTranscript(transcript, inputConnection ?: inputMethod)

    /**
     * Inserts through [RapchaAccessibilityService.injectTextDetailed].
     * That method owns P1, P2, and P3. A passed InputConnection is not a separate path.
     */
    private suspend fun tryDirectInjection(text: String, inputMethod: Any?): DirectInjectionResult {
        if (inputMethod != null) {
            Log.d(TAG, "Insertion is decided by the accessibility service priority, not the passed InputConnection")
        }

        val a11y = RapchaAccessibilityService.instance
        if (a11y != null) {
            val trace = a11y.injectTextDetailed(text)
            InjectionLogRepository.logInjection(trace)
            lastDirectMethod = diagnosticMethod(trace.injectionMethod, trace.finalOutcome == "SUCCESS")

            if (trace.finalOutcome == "SUCCESS") {
                Log.d(TAG, "Direct injection succeeded via Accessibility Service (${trace.injectionMethod})")
                return DirectInjectionResult.Success
            }

            // Clipboard fallback is allowed only when no focused editable field exists.
            if (trace.injectionMethod == "NO_TARGET_NODE") {
                return DirectInjectionResult.NoFocusedField
            }

            Log.w(TAG, "Focused field or blocked write (${trace.injectionMethod}): ${trace.resultDetails}. Clipboard fallback strictly suppressed.")
            return DirectInjectionResult.FocusedFieldFailed(trace.resultDetails)
        }

        lastDirectMethod = "failed"
        return DirectInjectionResult.NoFocusedField
    }

    private fun diagnosticMethod(injectionMethod: String, success: Boolean): String {
        if (!success) return "failed"
        return when (injectionMethod) {
            "ACCESSIBILITY_INPUT_CONNECTION" -> "commitText"
            "COMPANION_IME" -> "IME"
            "DIRECT_ACTION_SET_TEXT" -> "node"
            else -> "failed"
        }
    }

    private fun logDiagnosticInsertion(method: String, success: Boolean) {
        val label = if (success) method else "failed"
        val pkg = FloatingBubbleManager.currentForegroundPackage.value?.takeIf { it.isNotBlank() } ?: "unknown"
        AppLogRepository.logEvent(
            DiagnosticSource.BUBBLE,
            DiagnosticType.TEXT_INSERTED,
            "method=$label package=$pkg"
        )
    }

    /**
     * Strict Fallback: Copies to clipboard ONLY when no focused editable field is available.
     * Silent copy with subtle floating ring indicator (no intrusive toasts).
     */
    private fun fallbackToClipboard(text: String): Boolean {
        return try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            if (clipboard == null) {
                Log.e(TAG, "ClipboardManager unavailable for fallback")
                return false
            }

            val clip = ClipData.newPlainText("Rapcha Transcription", text)
            clipboard.setPrimaryClip(clip)
            Log.d(TAG, "Strict Fallback: Copied transcript to clipboard (${text.length} chars)")

            // Silent visual feedback on the floating ring + subtle haptic tick
            FloatingBubbleManager.triggerClipboardFallbackIndicator()
            FloatingHapticManager.trigger(context, FloatingHapticType.CLIPBOARD_FALLBACK)

            logInjectionTrace(
                text = text,
                method = "FALLBACK_CLIPBOARD",
                details = "Copied to clipboard (no active editable node detected on screen)",
                outcome = "SUCCESS"
            )
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error in fallbackToClipboard: ${e.message}", e)
            false
        }
    }

    private fun logInjectionTrace(text: String, method: String, details: String, outcome: String) {
        val currentPkg = FloatingBubbleManager.currentForegroundPackage.value ?: "Unknown"
        val appName = AppContextResolver.resolve(context, currentPkg)?.appName ?: "App"
        val wordCount = text.trim().split("\\s+".toRegex()).count { it.isNotBlank() }

        val event = InjectionEvent(
            targetPackage = currentPkg,
            targetAppName = appName,
            textLength = text.length,
            wordCount = wordCount,
            rawTextPreview = text.take(40),
            injectionMethod = method,
            resultDetails = details,
            finalOutcome = outcome,
            durationMs = 30L
        )
        InjectionLogRepository.logInjection(event)
    }

    sealed interface InsertionResult {
        object SuccessDirect : InsertionResult
        object SuccessClipboardFallback : InsertionResult
        object Failed : InsertionResult
    }

    companion object {
        private const val TAG = "RapchaTextInserter"

        @Volatile
        private var instance: RapchaTextInserter? = null

        fun getInstance(context: Context): RapchaTextInserter {
            return instance ?: synchronized(this) {
                instance ?: RapchaTextInserter(context.applicationContext).also { instance = it }
            }
        }
    }
}
