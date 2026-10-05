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
import com.example.service.AppClassifier
import com.example.service.FloatingBubbleManager
import com.example.service.VoxStreamAccessibilityService
import com.example.service.VoxStreamInputMethodService

/**
 * Dedicated text-injection engine for VoxStream:
 * - Direct caret manipulation via ACTION_SET_TEXT (zero clipboard impact)
 * - Intelligent hint & placeholder stripping via extractGenuineText
 * - Paste fallback for non-standard editors with automated clipboard restoration
 * - Clipboard fallback when no input node is active
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
     * ensuring that long text in apps like Chrome or WhatsApp is never identified as a hint and overwritten.
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
     * Injects transcribed text into the target active editable field via direct node editing (ACTION_SET_TEXT):
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

    private val nextPasteOperationId = java.util.concurrent.atomic.AtomicLong(0)
    @Volatile
    private var activePasteOperationId = 0L
    private var savedUserOriginalClip: ClipData? = null

    fun getActivePasteOperationId(): Long = activePasteOperationId

    private var pendingPasteRunnable: Runnable? = null
    private var pendingRetryRunnable: Runnable? = null
    private var pendingRestoreRunnable: Runnable? = null

    /**
     * Cancels any pending asynchronous paste and clipboard-restoration callbacks
     * to ensure an asynchronous paste or old restoration never fires after another
     * injection method has already succeeded or a new injection has started.
     */
    fun cancelPendingPaste(handler: Handler = mainHandler) {
        activePasteOperationId = 0L
        pendingPasteRunnable?.let {
            handler.removeCallbacks(it)
            pendingPasteRunnable = null
        }
        pendingRetryRunnable?.let {
            handler.removeCallbacks(it)
            pendingRetryRunnable = null
        }
        pendingRestoreRunnable?.let {
            handler.removeCallbacks(it)
            pendingRestoreRunnable = null
        }
    }

    /**
     * Executes clipboard-assisted paste injection for custom/rich-text editors.
     * Restores previous clipboard content safely after a delayed window (~450ms).
     */
    fun performPasteInjection(
        targetNode: AccessibilityNodeInfo,
        newText: String,
        context: Context,
        handler: Handler = mainHandler
    ): Boolean {
        cancelPendingPaste(handler)

        val operationId = nextPasteOperationId.incrementAndGet()
        activePasteOperationId = operationId
        val operationLabel = "VoxStream Dictation #$operationId"

        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val currentClip = try {
            clipboard?.primaryClip
        } catch (e: Exception) {
            Log.w(TAG, "Could not read existing clipboard: ${e.message}")
            null
        }

        val isInternalDictationClip = currentClip?.description?.label?.toString()?.startsWith("VoxStream Dictation") == true
        val originalClip = if (isInternalDictationClip) {
            savedUserOriginalClip
        } else {
            savedUserOriginalClip = currentClip
            currentClip
        }

        Log.d(TAG, "Executing paste-injection #$operationId for pkg=${targetNode.packageName}, textLen=${newText.length}")

        // 1. Ensure target node receives both ACTION_FOCUS and ACTION_ACCESSIBILITY_FOCUS
        try {
            targetNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            targetNode.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
        } catch (e: Exception) {
            Log.w(TAG, "Notice requesting focus before paste: ${e.message}")
        }

        // 2. Short delay (≈100 ms) so editor is ready, then set clipboard, paste, and restore after ~450ms
        val focusReadyDelayMs = 100L
        val pasteRetryDelayMs = 100L
        val clipboardRestoreDelayMs = 450L

        val pasteRunnable = Runnable {
            pendingPasteRunnable = null
            if (activePasteOperationId != operationId) {
                Log.d(TAG, "Paste operation #$operationId cancelled before dispatch")
                return@Runnable
            }

            val dictationClip = ClipData.newPlainText(operationLabel, newText)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                try {
                    val extras = android.os.PersistableBundle().apply {
                        putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE, true)
                    }
                    dictationClip.description.extras = extras
                } catch (_: Throwable) {}
            }

            try {
                clipboard?.setPrimaryClip(dictationClip)
            } catch (e: Exception) {
                Log.e(TAG, "Failed setting dictation clip for #$operationId", e)
                return@Runnable
            }

            var initialPasteResult = false
            try {
                initialPasteResult = targetNode.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                Log.d(TAG, "Initial ACTION_PASTE dispatch for #$operationId: $initialPasteResult")
            } catch (e: Exception) {
                Log.w(TAG, "Initial ACTION_PASTE dispatch error for #$operationId: ${e.message}")
            }

            if (!initialPasteResult) {
                // Retry once after a short delay
                val retryRunnable = Runnable {
                    pendingRetryRunnable = null
                    if (activePasteOperationId != operationId) return@Runnable
                    try {
                        targetNode.refresh()
                        val retryPasteResult = targetNode.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                        Log.d(TAG, "Retry ACTION_PASTE dispatch for #$operationId: $retryPasteResult")
                    } catch (e: Exception) {
                        Log.w(TAG, "Retry ACTION_PASTE dispatch error for #$operationId: ${e.message}")
                    }
                }
                pendingRetryRunnable = retryRunnable
                handler.postDelayed(retryRunnable, pasteRetryDelayMs)
            }

            val restoreRunnable = Runnable {
                pendingRestoreRunnable = null
                safeRestoreOriginalClipboard(clipboard, originalClip, newText, operationId, operationLabel)
            }
            pendingRestoreRunnable = restoreRunnable
            handler.postDelayed(restoreRunnable, clipboardRestoreDelayMs)
        }

        pendingPasteRunnable = pasteRunnable
        handler.postDelayed(pasteRunnable, focusReadyDelayMs)

        return true
    }

    private fun safeRestoreOriginalClipboard(
        clipboard: ClipboardManager?,
        originalClip: ClipData?,
        expectedDictationText: String,
        operationId: Long,
        operationLabel: String
    ) {
        if (activePasteOperationId != operationId) {
            Log.i(TAG, "Paste operation #$operationId is obsolete (active is #$activePasteOperationId). Skipping restoration.")
            return
        }

        try {
            val currentClip = try { clipboard?.primaryClip } catch (_: Exception) { null }

            val isStillOurDictationClip = if (currentClip != null && currentClip.itemCount > 0) {
                val currentText = currentClip.getItemAt(0)?.text?.toString() ?: ""
                val label = currentClip.description?.label?.toString() ?: ""
                label == operationLabel && currentText == expectedDictationText
            } else {
                false
            }

            if (isStillOurDictationClip) {
                if (originalClip != null) {
                    clipboard?.setPrimaryClip(originalClip)
                    Log.d(TAG, "Restored original user clipboard safely for operation #$operationId")
                } else {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        clipboard?.clearPrimaryClip()
                    } else {
                        clipboard?.setPrimaryClip(ClipData.newPlainText("", ""))
                    }
                    Log.d(TAG, "Cleared temporary dictation clip from clipboard for operation #$operationId")
                }
            } else {
                Log.i(TAG, "User or new operation copied new data during paste window. Preserving current clipboard.")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error in safeRestoreOriginalClipboard: ${e.message}")
        } finally {
            if (activePasteOperationId == operationId) {
                activePasteOperationId = 0L
                savedUserOriginalClip = null
            }
        }
    }

    /**
     * Injects text into the active field, or falls back to clipboard if no active field is accessible.
     */
    fun injectOrFallbackToClipboard(context: Context, text: String): Boolean {
        if (text.isBlank()) return false

        val injected = VoxStreamAccessibilityService.instance?.injectText(text) ?: false

        if (injected) {
            mainHandler.post {
                Toast.makeText(context, "Text inserted into active field!", Toast.LENGTH_SHORT).show()
            }
            return true
        } else {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            if (clipboard != null) {
                val clip = ClipData.newPlainText("VoxStream Transcription", text)
                clipboard.setPrimaryClip(clip)
            }
            mainHandler.post {
                Toast.makeText(
                    context,
                    "Copied to clipboard (no active text field found)",
                    Toast.LENGTH_LONG
                ).show()
            }
            return false
        }
    }
}
