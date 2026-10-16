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
 * Clipboard-free text insertion for VoxStream (accessibility service, not a keyboard).
 * Every focused field is written with a cursor-aware ACTION_SET_TEXT, then ACTION_SET_SELECTION.
 * A two-step write nudges the app's text watcher so the replace is seen as a real edit.
 * The clipboard is used only when no focused field exists.
 *
 * Rich fields keep the characters around the caret. ACTION_SET_TEXT replaces the node with a
 * plain string, so spans, avatar pills, and child-node chips (Gemini) can be flattened.
 */
object FloatingTextInjector {

    private const val TAG = "FloatingTextInjector"

    /** Packages whose editors keep mention pills or other spans ACTION_SET_TEXT would flatten. */
    private val RICH_FIELD_PACKAGES = listOf(
        "com.google.android.apps.bard",
        "com.google.android.apps.gemini"
    )

    data class CursorMerge(val text: String, val cursor: Int)

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
     * Rich editors cannot survive a full [AccessibilityNodeInfo.ACTION_SET_TEXT] rewrite
     * without losing spans or mention pills. Notes apps are not rich: they use SET_TEXT.
     * A field is rich when it has spans, child nodes, or a known rich package such as Gemini.
     */
    fun isRichField(node: AccessibilityNodeInfo): Boolean {
        val rawText = node.text
        if (rawText is Spanned) {
            try {
                val spans = rawText.getSpans(0, rawText.length, Any::class.java)
                if (spans != null && spans.isNotEmpty()) return true
            } catch (_: Throwable) {}
        }
        try {
            if (node.childCount > 0) return true
        } catch (_: Throwable) {}
        val pkg = try { node.packageName?.toString()?.lowercase() ?: "" } catch (_: Throwable) { "" }
        return RICH_FIELD_PACKAGES.any { pkg.contains(it) }
    }

    fun isRichOrUnsafeToRewrite(node: AccessibilityNodeInfo): Boolean = isRichField(node)

    /**
     * True only when the node is displaying its hint: [AccessibilityNodeInfo.isShowingHintText]
     * is set and the visible text equals [AccessibilityNodeInfo.getHintText].
     * Real content, including short mentions, is never treated as a hint.
     */
    fun isActiveHint(node: AccessibilityNodeInfo): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        return try {
            if (!node.isShowingHintText) return false
            val rawText = node.text?.toString() ?: return false
            val hintText = node.hintText?.toString() ?: return false
            hintText.isNotBlank() && rawText.trim().equals(hintText.trim(), ignoreCase = true)
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Real user text. Blank text and an active hint (flag set and text equal to hintText) are empty.
     */
    fun extractGenuineText(node: AccessibilityNodeInfo): String {
        val rawText = node.text?.toString() ?: ""
        if (rawText.isBlank()) return ""
        if (isActiveHint(node)) return ""
        return rawText
    }

    /**
     * Builds before + transcript + after using the node's selection.
     * A single space is inserted when the character before the caret is not whitespace,
     * so dictated words do not glue to existing text. An active hint is treated as an empty field.
     */
    fun buildCursorMerge(node: AccessibilityNodeInfo, dictatedText: String): CursorMerge? {
        val transcript = dictatedText.trim()
        if (transcript.isEmpty()) return null
        val rawText = node.text?.toString() ?: ""
        if (extractGenuineText(node).isEmpty()) {
            return CursorMerge(transcript, transcript.length)
        }

        var start = rawText.length
        var end = rawText.length
        val selStart = try { node.textSelectionStart } catch (_: Throwable) { -1 }
        val selEnd = try { node.textSelectionEnd } catch (_: Throwable) { -1 }
        if (selStart in 0..rawText.length && selEnd in selStart..rawText.length) {
            start = selStart
            end = selEnd
        }
        val before = rawText.substring(0, start)
        val after = rawText.substring(end)
        val needsSpace = before.isNotEmpty() &&
            !before.last().isWhitespace() &&
            !transcript.startsWith(" ") &&
            !transcript.startsWith("\n")
        val insertion = if (needsSpace) " $transcript" else transcript
        val merged = before + insertion + after
        return CursorMerge(merged, (start + insertion.length).coerceAtMost(merged.length))
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
     * Inserts [dictatedText] with ACTION_SET_TEXT at the caret, then places the caret after it.
     * Every field gets a two-step write so text watchers observe a real character diff.
     * Does not touch the clipboard. On a rich field the surrounding characters are kept;
     * spans and mention pills may be flattened.
     */
    fun injectTextSafely(node: AccessibilityNodeInfo, dictatedText: String): Boolean {
        if (dictatedText.isBlank()) {
            Log.w(TAG, "injectTextSafely: dictatedText is blank, aborting to prevent clearing field / note discard")
            return false
        }
        val merge = buildCursorMerge(node, dictatedText)
        if (merge == null || merge.text.isBlank()) {
            Log.w(TAG, "injectTextSafely: resulting text is blank, aborting to prevent 'Empty note discarded'")
            return false
        }

        if (isRichField(node)) {
            Log.w(TAG, "Rich field: keeping surrounding characters. ACTION_SET_TEXT may flatten spans, mention pills, or child chips.")
        }

        try {
            if (!node.isFocused) {
                node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            }
        } catch (_: Throwable) {}

        val wrote = writeTextWithWatcherNudge(node, merge.text)
        if (!wrote) return false
        setCaret(node, merge.cursor)
        return true
    }

    /**
     * ACTION_SET_TEXT often does not produce a TextWatcher diff the app treats as a user edit.
     * Keep and Samsung Notes then discard a brand-new note ("Empty note discarded").
     * Writing everything except the last character, then the full string, produces an
     * onTextChanged event whose diff is that last character. The caller then sends
     * ACTION_SET_SELECTION, which nudges selection listeners.
     *
     * If the first write lands and the second returns false, the full string is retried once
     * here. Callers must not rebuild before + transcript + after on that partial text, or the
     * transcript is inserted twice.
     *
     * This is still uncertain when the accessibility node is not the EditText the app watches:
     * neither step reaches that TextWatcher, and the note can still be discarded.
     */
    private fun writeTextWithWatcherNudge(node: AccessibilityNodeInfo, fullText: String): Boolean {
        if (fullText.length >= 2) {
            val partial = fullText.dropLast(1)
            if (!performSetText(node, partial)) return false
            if (!performSetText(node, fullText)) {
                Log.w(TAG, "Second SET_TEXT of the edit nudge returned false. Retrying the full string once.")
                if (!performSetText(node, fullText)) return false
            }
            Log.d(TAG, "Edit nudge: two-step SET_TEXT len=${fullText.length}")
            return true
        }
        return performSetText(node, fullText)
    }

    private fun performSetText(node: AccessibilityNodeInfo, text: String): Boolean {
        if (text.isEmpty()) {
            Log.w(TAG, "Refusing empty ACTION_SET_TEXT")
            return false
        }
        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val success = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
        Log.d(TAG, "ACTION_SET_TEXT result=$success len=${text.length}")
        return success
    }

    private fun setCaret(node: AccessibilityNodeInfo, cursor: Int) {
        val selectionArgs = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, cursor)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, cursor)
        }
        try {
            node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selectionArgs)
        } catch (_: Throwable) {}
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
