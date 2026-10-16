package com.example.service.floating

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.text.Spanned
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.example.service.VoxStreamInputMethodService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Dedicated text-injection engine for VoxStream:
 * - ACTION_SET_TEXT only for a genuinely empty, plain, non-notes field
 * - Existing content, spans, and notes apps are inserted at the caret (IME or paste)
 * - Intelligent hint & placeholder stripping via extractGenuineText
 * - Clipboard fallback ONLY when no focused editable field exists
 */
object FloatingTextInjector {

    private const val TAG = "FloatingTextInjector"

    private val KNOWN_PLACEHOLDERS = setOf(
        "ask gemini", "ask gemini…", "ask gemini...",
        "ask google", "ask google…", "ask google...",
        "search", "search…", "search...",
        "search or type url", "search or type web address",
        "type a message", "message", "send a message", "write a message",
        "write a comment…", "write a comment...", "add a comment…", "add a comment...",
        "take a note", "take a note…", "take a note...", "note", "note…", "note...", "title",
        "title…", "title...", "start typing", "start typing…", "start typing...",
        "write a note", "write a note…", "write a note...", "enter notes", "add note",
        "text note", "body"
    )

    /**
     * Checks if a package belongs to known note-taking apps requiring careful empty/placeholder handling
     * to avoid triggering "Empty note discarded".
     */
    fun isNoteAppPackage(pkg: String): Boolean {
        val p = pkg.lowercase()
        return p.contains("com.google.android.keep") ||
               p.contains("com.samsung.android.app.notes") ||
               p.contains("com.evernote") ||
               p.contains("com.microsoft.office.onenote") ||
               p.contains("notion") ||
               p.contains("notes")
    }

    /**
     * Checks that the target node specifically supports pure ACTION_SET_TEXT (0x200000).
     */
    fun supportsActionSetText(node: AccessibilityNodeInfo): Boolean {
        if ((node.actions and AccessibilityNodeInfo.ACTION_SET_TEXT) != 0) return true
        if (node.actionList?.any { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT } == true) return true
        if (node.isEditable) return true
        val className = node.className?.toString() ?: ""
        return className.contains("EditText", ignoreCase = true) ||
               className.contains("NoteEditText", ignoreCase = true) ||
               className.contains("ComposeEditableText", ignoreCase = true)
    }

    /**
     * Helper to determine if a field is rich or unsafe to rewrite with ACTION_SET_TEXT:
     * - node.text is a Spanned and has at least one span of any kind
     * - the package is a known note or rich-text app
     * - the node has a child node
     * - the node does not support ACTION_SET_TEXT
     */
    fun isRichOrUnsafeToRewrite(node: AccessibilityNodeInfo): Boolean {
        // 1. node.text is a Spanned and has at least one span of any kind
        val rawText = node.text
        if (rawText is Spanned) {
            try {
                val spans = rawText.getSpans(0, rawText.length, Any::class.java)
                if (spans != null && spans.isNotEmpty()) {
                    return true
                }
            } catch (_: Throwable) {}
        }

        // 2. the package is a known note or rich-text app (use the existing isNoteAppPackage)
        val pkg = try { node.packageName?.toString() ?: "" } catch (_: Throwable) { "" }
        if (isNoteAppPackage(pkg)) {
            return true
        }

        // 3. the node has a child node
        try {
            if (node.childCount > 0) {
                return true
            }
        } catch (_: Throwable) {}

        // 4. the node does not support ACTION_SET_TEXT
        if (!supportsActionSetText(node)) {
            return true
        }

        return false
    }

    /**
     * ACTION_SET_TEXT replaces the entire field and does not emit real input events.
     * It is safe only when the field is genuinely empty, plain, and not a notes app.
     * Existing text (including short mentions) must be inserted at the caret instead.
     */
    fun canSafelyRewriteWithSetText(node: AccessibilityNodeInfo): Boolean {
        val pkg = try { node.packageName?.toString() ?: "" } catch (_: Throwable) { "" }
        if (isNoteAppPackage(pkg)) return false
        if (isRichOrUnsafeToRewrite(node)) return false
        if (extractGenuineText(node).isNotEmpty()) return false
        return true
    }

    /**
     * Determines whether the given node contains real, genuine user text versus an empty
     * field or a placeholder/hint string.
     *
     * isShowingHintText is NOT enough to discard short text. Apps such as Gemini report
     * that flag while a mention like "@princeniithompson" or an avatar pill is present.
     * Only a blank field, an exact hint match, or a known placeholder is treated as empty.
     * Text longer than 25 characters is always kept.
     */
    fun extractGenuineText(node: AccessibilityNodeInfo): String {
        val rawText = node.text?.toString() ?: ""
        if (rawText.isBlank()) return ""

        // SAFETY CHECK: Long text in Chrome, WhatsApp, Keep, or Gemini is NEVER a hint.
        if (rawText.length > 25) {
            return rawText
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val hintText = node.hintText?.toString()
                if (!hintText.isNullOrBlank() && rawText.trim().equals(hintText.trim(), ignoreCase = true)) return ""
            } catch (_: Throwable) {}
        }

        val trimmed = rawText.trim().lowercase()
        if (KNOWN_PLACEHOLDERS.contains(trimmed)) return ""

        val viewId = try { node.viewIdResourceName?.lowercase() ?: "" } catch (_: Throwable) { "" }
        if (viewId.contains("editor_title") && trimmed == "title") return ""
        if (viewId.contains("edit_note_text") && (trimmed == "note" || trimmed == "take a note")) return ""

        // Do not drop short real text just because isShowingHintText is set.
        return rawText
    }

    /**
     * Checks whether an accessibility node accepts text insertion or is an editable text field.
     */
    fun isNodeAcceptingText(node: AccessibilityNodeInfo): Boolean {
        if (node.isEditable) return true
        val className = node.className?.toString() ?: ""
        val viewId = try { node.viewIdResourceName?.lowercase() ?: "" } catch (_: Throwable) { "" }
        if (
            className.contains("EditText", ignoreCase = true) ||
            className.contains("AutoCompleteTextView", ignoreCase = true) ||
            className.contains("ComposeEditableText", ignoreCase = true) ||
            className.contains("NoteEditText", ignoreCase = true) ||
            className.contains("TextInputEditText", ignoreCase = true) ||
            className.contains("ExtractEditText", ignoreCase = true) ||
            viewId.contains("edit_note_text") ||
            viewId.contains("editor_title") ||
            viewId.contains("note_text") ||
            viewId.contains("content")
        ) {
            return true
        }
        return supportsActionSetText(node)
    }

    /**
     * Writes dictated text with ACTION_SET_TEXT only when [canSafelyRewriteWithSetText] is true.
     * Refuses notes apps and any field that already has content so the caller can insert at the caret.
     * Never rebuilds beforeCursor + text + afterCursor.
     */
    fun injectTextSafely(node: AccessibilityNodeInfo, dictatedText: String): Boolean {
        if (dictatedText.isBlank()) {
            Log.w(TAG, "injectTextSafely: dictatedText is blank, aborting to prevent clearing field / note discard")
            return false
        }

        if (!canSafelyRewriteWithSetText(node)) {
            Log.d(TAG, "injectTextSafely: refusing ACTION_SET_TEXT (existing content, rich field, or notes app)")
            return false
        }

        val textToInsert = dictatedText.trim()
        if (textToInsert.isEmpty()) {
            Log.w(TAG, "injectTextSafely: resulting text is blank, aborting to prevent 'Empty note discarded'")
            return false
        }

        try {
            if (!node.isFocused) {
                node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            }
        } catch (_: Throwable) {}

        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, textToInsert)
        }
        val success = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
        Log.d(TAG, "injectTextSafely ACTION_SET_TEXT on empty plain field result: $success, len=${textToInsert.length}")

        if (success) {
            val newCursorPos = textToInsert.length
            val selectionArgs = Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, newCursorPos)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, newCursorPos)
            }
            try {
                node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selectionArgs)
            } catch (_: Throwable) {}
            return true
        }

        return false
    }

    /**
     * Injects text directly into the active field via [VoxStreamTextInserter].
     * Runs off the main thread so verification waits suspend instead of blocking the caller.
     * Falls back to clipboard ONLY if no active text field is present on screen.
     */
    suspend fun injectOrFallbackToClipboard(context: Context, text: String): Boolean {
        if (text.isBlank()) return false
        val inserter = VoxStreamTextInserter.getInstance(context)
        val result = withContext(Dispatchers.Default) {
            inserter.insertTranscript(text, VoxStreamInputMethodService.instance)
        }
        return result is VoxStreamTextInserter.InsertionResult.SuccessDirect ||
               result is VoxStreamTextInserter.InsertionResult.SuccessClipboardFallback
    }
}
