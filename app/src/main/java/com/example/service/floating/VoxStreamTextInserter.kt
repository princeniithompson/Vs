package com.example.service.floating

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethod
import com.example.data.InjectionEvent
import com.example.data.InjectionLogRepository
import com.example.service.AppContextResolver
import com.example.service.FloatingBubbleManager
import com.example.service.VoxStreamAccessibilityService
import com.example.service.VoxStreamInputMethodService
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Production-ready text insertion engine for VoxStream based on Whisperflow's architecture:
 * 1. Mutex-protected insertion loop ensuring zero race conditions during rapid typing/dictation.
 * 2. Attempts direct caret-level injection into the active text field first (IME InputConnection & AccessibilityService ACTION_SET_TEXT / ACTION_PASTE).
 * 3. Keeps user's clipboard 100% clean during normal voice typing.
 * 4. Conditional clipboard fallback ONLY when no editable field exists on screen.
 * 5. Silent copy with subtle floating ring indicator on fallback (zero intrusive toasts).
 */
class VoxStreamTextInserter(private val context: Context) {

    // 1. Mutex to prevent race conditions during rapid typing/dictation
    private val insertionMutex = Mutex()
    private val mainHandler = Handler(Looper.getMainLooper())

    sealed interface DirectInjectionResult {
        object Success : DirectInjectionResult
        data class FocusedFieldFailed(val reason: String) : DirectInjectionResult
        object NoFocusedField : DirectInjectionResult
    }

    /**
     * The Main Entry Point:
     * 1. Attempts direct insertion via pure ACTION_SET_TEXT / IME InputConnection.
     * 2. Only falls back to clipboard when there is NO focused editable text field at all.
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
                return@withLock InsertionResult.SuccessDirect
            }
            is DirectInjectionResult.FocusedFieldFailed -> {
                // Requirement 3: Never use clipboard if a focused editable field exists (even if the write fails)
                Log.w(TAG, "Focused editable field exists but write failed: ${directResult.reason}. Clipboard fallback strictly suppressed.")
                return@withLock InsertionResult.Failed
            }
            is DirectInjectionResult.NoFocusedField -> {
                // Requirement 2: Only fall back to clipboard when there is NO focused editable text field at all
                Log.d(TAG, "No focused editable text field detected on screen. Falling back to clipboard.")
                val fallbackSuccess = fallbackToClipboard(transcript)
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
     * Tries to inject text directly using the active InputConnection or Accessibility Service pure ACTION_SET_TEXT.
     * Differentiates whether a field existed (write failed) vs NO field on screen.
     */
    private fun tryDirectInjection(text: String, inputMethod: Any?): DirectInjectionResult {
        // Step 1: Attempt IME / InputConnection commit (Whisperflow IME path)
        try {
            val candidateIme = inputMethod ?: VoxStreamInputMethodService.instance
            if (candidateIme != null) {
                val inputConnection: InputConnection? = when (candidateIme) {
                    is InputConnection -> candidateIme
                    is android.inputmethodservice.InputMethodService -> candidateIme.currentInputConnection
                    else -> {
                        try {
                            candidateIme.javaClass.getMethod("getCurrentInputConnection").invoke(candidateIme) as? InputConnection
                        } catch (_: Throwable) {
                            null
                        }
                    }
                }
                if (inputConnection != null) {
                    val committed = inputConnection.commitText(text, 1)
                    if (committed) {
                        Log.d(TAG, "Direct injection succeeded via InputConnection")
                        logInjectionTrace(text, "INPUT_CONNECTION", "SUCCESS: Committed via InputConnection", "SUCCESS")
                        return DirectInjectionResult.Success
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "InputConnection direct commit exception: ${e.message}")
        }

        // Step 2: Attempt Accessibility Service direct node editing (pure ACTION_SET_TEXT)
        val a11y = VoxStreamAccessibilityService.instance
        if (a11y != null) {
            val trace = a11y.injectTextDetailed(text)
            InjectionLogRepository.logInjection(trace)

            if (trace.finalOutcome == "SUCCESS") {
                Log.d(TAG, "Direct injection succeeded via Accessibility Service (${trace.injectionMethod})")
                return DirectInjectionResult.Success
            }

            if (trace.injectionMethod == "DIRECT_ACTION_SET_TEXT") {
                // Focused field existed, but writing/verification failed!
                Log.w(TAG, "Focused editable field existed but ACTION_SET_TEXT failed (${trace.resultDetails})")
                return DirectInjectionResult.FocusedFieldFailed(trace.resultDetails)
            }

            if (trace.injectionMethod == "BLOCKED_SAFE_MODE") {
                Log.w(TAG, "Direct injection blocked by Smart Safe Mode")
                return DirectInjectionResult.FocusedFieldFailed("Smart Safe Mode Active")
            }

            // trace.injectionMethod == "NO_TARGET_NODE"
            return DirectInjectionResult.NoFocusedField
        }

        return DirectInjectionResult.NoFocusedField
    }

    /**
     * Strict Fallback: Copies to clipboard ONLY when no text field or InputConnection is available.
     * Silent copy with subtle floating ring indicator (no intrusive toasts).
     */
    private fun fallbackToClipboard(text: String): Boolean {
        return try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            if (clipboard == null) {
                Log.e(TAG, "ClipboardManager unavailable for fallback")
                return false
            }

            val clip = ClipData.newPlainText("VoxStream Transcription", text)
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
        private const val TAG = "VoxStreamTextInserter"

        @Volatile
        private var instance: VoxStreamTextInserter? = null

        fun getInstance(context: Context): VoxStreamTextInserter {
            return instance ?: synchronized(this) {
                instance ?: VoxStreamTextInserter(context.applicationContext).also { instance = it }
            }
        }
    }
}
