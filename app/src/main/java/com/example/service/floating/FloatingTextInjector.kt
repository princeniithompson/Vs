package com.example.service.floating

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Spanned
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
     * Determines whether the given node contains real, genuine user text versus an empty
     * field or a placeholder/hint string.
     * Safety check: Ignores isShowingHintText when content length exceeds 25 characters,
     * ensuring that long text in apps like Chrome, AI Studio, or WhatsApp is never identified as a hint and overwritten.
     * Also inspects Google Keep and Samsung Notes placeholder IDs.
     */
    fun extractGenuineText(node: AccessibilityNodeInfo): String {
        val rawText = node.text?.toString() ?: ""
        if (rawText.isBlank()) return ""

        // SAFETY CHECK: When content length exceeds 25 characters, ignore isShowingHintText
        // and placeholder checks. Long text in Chrome, WhatsApp, Keep is NEVER a hint.
        if (rawText.length > 25) {
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

        val viewId = try { node.viewIdResourceName?.lowercase() ?: "" } catch (_: Throwable) { "" }
        if (viewId.contains("editor_title") && trimmed == "title") return ""
        if (viewId.contains("edit_note_text") && (trimmed == "note" || trimmed == "take a note")) return ""

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
     * Injects transcribed text directly into the target active editable field (pure ACTION_SET_TEXT):
     * 1. Uses extractGenuineText to preserve existing user drafts while safely ignoring hints.
     * 2. Special handling for Note Apps (Google Keep, Samsung Notes):
     *    - If field was empty or placeholder, writes clean text without leading whitespace.
     *    - Never sends empty or blank string to prevent "Empty note discarded".
     * 3. Sets selection to the end of inserted text to ensure active focus and note commit.
     */
    fun injectTextSafely(node: AccessibilityNodeInfo, dictatedText: String): Boolean {
        if (dictatedText.isBlank()) {
            Log.w(TAG, "injectTextSafely: dictatedText is blank, aborting to prevent clearing field / note discard")
            return false
        }

        val currentText = extractGenuineText(node)
        val rawText = node.text?.toString() ?: ""
        val pkgName = try { node.packageName?.toString()?.lowercase() ?: "" } catch (_: Throwable) { "" }
        val isNote = isNoteAppPackage(pkgName)

        val isFieldEmptyOrPlaceholder = currentText.isEmpty()
        val textToInsert: String
        val newText: String
        val newCursorPos: Int

        if (isFieldEmptyOrPlaceholder) {
            // Note apps or empty fields: write strictly the clean dictated text
            textToInsert = dictatedText.trim()
            newText = textToInsert
            newCursorPos = newText.length
            Log.d(TAG, "Empty/placeholder field detected (isNoteApp=$isNote, pkg=$pkgName). Writing clean text without leading space.")
        } else {
            // Field has genuine existing user draft: splice at current caret position
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
            textToInsert = space + dictatedText
            newText = beforeCursor + textToInsert + afterCursor
            newCursorPos = selectionStart + textToInsert.length
        }

        if (newText.isBlank()) {
            Log.w(TAG, "injectTextSafely: resulting text is blank, aborting to prevent 'Empty note discarded'")
            return false
        }

        // Ensure target node is focused before performing ACTION_SET_TEXT
        try {
            if (!node.isFocused) {
                node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            }
        } catch (_: Throwable) {}

        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText)
        }
        val success = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
        Log.d(TAG, "injectTextSafely pure ACTION_SET_TEXT result: $success, len=${newText.length}, isNoteApp=$isNote")

        if (success) {
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
     * Falls back to clipboard ONLY if no active text field is present on screen.
     * Silent copy with subtle floating ring indicator on fallback (zero intrusive toasts).
     */
    fun injectOrFallbackToClipboard(context: Context, text: String): Boolean {
        if (text.isBlank()) return false
        val inserter = VoxStreamTextInserter.getInstance(context)
        val result = kotlinx.coroutines.runBlocking {
            inserter.insertTranscript(text, com.example.service.VoxStreamInputMethodService.instance)
        }
        return result is VoxStreamTextInserter.InsertionResult.SuccessDirect ||
               result is VoxStreamTextInserter.InsertionResult.SuccessClipboardFallback
    }
}
