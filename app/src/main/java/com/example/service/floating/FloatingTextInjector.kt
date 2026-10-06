package com.example.service.floating

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import com.example.service.FloatingBubbleManager
import com.example.service.VoxStreamAccessibilityService

/**
 * Dedicated text-injection engine for VoxStream:
 * - Direct node editing via ACTION_SET_TEXT (zero clipboard impact)
 * - Intelligent hint & placeholder stripping via extractGenuineText
 * - Preserves existing user drafts while safely ignoring hints (>15 chars safety check)
 * - Safe fallback to clipboard ONLY when literally no active text field is on screen
 */
object FloatingTextInjector {

    private const val TAG = "FloatingTextInjector"
    private val mainHandler = Handler(Looper.getMainLooper())

    private val KNOWN_PLACEHOLDERS = setOf(
        "ask gemini", "ask gemini…", "ask gemini...",
        "ask google", "ask google…", "ask google...",
        "search", "search…", "search...",
        "search or type url", "search or type web address",
        "type a message", "message", "send a message", "write a message",
        "write a comment…", "write a comment...", "add a comment…", "add a comment...",
        "take a note", "take a note…", "take a note...", "note", "note…", "note...", "title"
    )

    /**
     * Determines whether the given node contains real, genuine user text versus an empty
     * field or a placeholder/hint string.
     * Safety check: Ignores isShowingHintText when content length exceeds 15 characters,
     * ensuring that long text in apps like Chrome, AI Studio, or WhatsApp is never identified as a hint and overwritten.
     */
    fun extractGenuineText(node: AccessibilityNodeInfo): String {
        val rawText = node.text?.toString() ?: ""
        if (rawText.isBlank()) return ""

        // SAFETY CHECK: When content length exceeds 15 characters, ignore isShowingHintText
        // and placeholder checks. Long text in Chrome, WhatsApp, etc. is NEVER a hint.
        if (rawText.length > 15) {
            return rawText
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                if (node.isShowingHintText) return ""
            } catch (_: Throwable) {}
            try {
                val hintText = node.hintText?.toString()
                if (!hintText.isNullOrBlank() && rawText.trim().equals(hintText.trim(), ignoreCase = true)) return ""
            } catch (_: Throwable) {}
        }

        val trimmed = rawText.trim().lowercase()
        if (KNOWN_PLACEHOLDERS.contains(trimmed)) return ""

        return rawText
    }

    /**
     * Checks whether an accessibility node accepts text insertion or is an editable text field.
     */
    fun isNodeAcceptingText(node: AccessibilityNodeInfo): Boolean {
        if (node.isEditable) return true
        val className = node.className?.toString() ?: ""
        if (
            className.contains("EditText", ignoreCase = true) ||
            className.contains("ComposeEditableText", ignoreCase = true) ||
            className.contains("NoteEditText", ignoreCase = true) ||
            className.contains("TextInputEditText", ignoreCase = true)
        ) {
            return true
        }
        return node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT }
    }

    /**
     * Injects transcribed text directly into the target active editable field (ACTION_SET_TEXT):
     * 1. Uses extractGenuineText to preserve existing user drafts while safely ignoring hints.
     * 2. Determines cursor position safely without clobbering existing text.
     * 3. Splices the dictated text into the existing text at cursor.
     * 4. Performs direct text insertion via ACTION_SET_TEXT.
     * 5. Updates cursor position to sit immediately after the newly inserted text.
     */
    fun injectTextSafely(node: AccessibilityNodeInfo, dictatedText: String): Boolean {
        val currentText = extractGenuineText(node)
        val rawText = node.text?.toString() ?: ""

        var selectionStart = currentText.length
        var selectionEnd = currentText.length

        val selStart = try { node.textSelectionStart } catch (_: Throwable) { -1 }
        val selEnd = try { node.textSelectionEnd } catch (_: Throwable) { -1 }

        if (selStart in 0..rawText.length && selEnd in selStart..rawText.length) {
            selectionStart = selStart.coerceIn(0, currentText.length)
            selectionEnd = selEnd.coerceIn(0, currentText.length)
        }

        val beforeCursor = currentText.substring(0, selectionStart)
        val afterCursor = currentText.substring(selectionEnd)

        val space = if (beforeCursor.isNotEmpty() && !beforeCursor.endsWith(" ") && !beforeCursor.endsWith("\n")) " " else ""
        val textToInsert = space + dictatedText
        val newText = beforeCursor + textToInsert + afterCursor

        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText)
        }
        val success = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
        Log.d(TAG, "injectTextSafely ACTION_SET_TEXT result: $success, len=${newText.length}")

        if (success) {
            val newCursorPos = selectionStart + textToInsert.length
            val selectionArgs = Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, newCursorPos)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, newCursorPos)
            }
            node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selectionArgs)
            return true
        }

        return false
    }

    /**
     * Injects text directly into the active field via AccessibilityService.
     * Falls back to clipboard ONLY if no active text field is present on screen.
     */
    fun injectOrFallbackToClipboard(context: Context, text: String): Boolean {
        if (text.isBlank()) return false
        val startTime = android.os.SystemClock.elapsedRealtime()

        val a11y = VoxStreamAccessibilityService.instance
        val trace = if (a11y != null) {
            a11y.injectTextDetailed(text)
        } else {
            val wordCount = text.trim().split("\\s+".toRegex()).count { it.isNotBlank() }
            val currentPkg = FloatingBubbleManager.currentForegroundPackage.value ?: "Unknown"
            val appName = com.example.service.AppContextResolver.resolve(context, currentPkg)?.appName ?: "App"
            com.example.data.InjectionEvent(
                targetPackage = currentPkg,
                targetAppName = appName,
                textLength = text.length,
                wordCount = wordCount,
                rawTextPreview = text.take(40),
                injectionMethod = "FALLBACK_CLIPBOARD",
                resultDetails = "FAILED: Accessibility Service not connected",
                finalOutcome = "FAILED",
                durationMs = android.os.SystemClock.elapsedRealtime() - startTime
            )
        }

        if (trace.finalOutcome == "SUCCESS") {
            com.example.data.InjectionLogRepository.logInjection(trace)
            mainHandler.post {
                Toast.makeText(context, "Text inserted into active field!", Toast.LENGTH_SHORT).show()
            }
            return true
        } else {
            // Only fallback to clipboard if there is no active target node or service was disconnected
            val isNoNode = trace.targetNodeClass == null || trace.resultDetails.contains("No active editable node")
            if (isNoNode) {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                if (clipboard != null) {
                    val clip = ClipData.newPlainText("VoxStream Transcription", text)
                    clipboard.setPrimaryClip(clip)
                }
                val finalTrace = trace.copy(
                    injectionMethod = "FALLBACK_CLIPBOARD",
                    durationMs = maxOf(trace.durationMs, android.os.SystemClock.elapsedRealtime() - startTime)
                )
                com.example.data.InjectionLogRepository.logInjection(finalTrace)
                mainHandler.post {
                    Toast.makeText(
                        context,
                        "No active text field found — text copied to clipboard",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } else {
                // A text field was present on screen, but direct action was rejected (do NOT overwrite user's clipboard)
                com.example.data.InjectionLogRepository.logInjection(trace)
                mainHandler.post {
                    Toast.makeText(
                        context,
                        "Direct text injection could not be applied to this field",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            return false
        }
    }
}
