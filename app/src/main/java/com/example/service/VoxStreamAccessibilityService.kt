package com.example.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

class VoxStreamAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "VoxStreamAccessService"
        var instance: VoxStreamAccessibilityService? = null
            private set
    }

    private var lastFocusedEditableNode: AccessibilityNodeInfo? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private val delayedCheckRunnable = Runnable {
        checkAndNotifyKeyboard()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        FloatingBubbleManager.setAccessibilityConnected(true)
        Log.d(TAG, "VoxStreamAccessibilityService connected and active")

        val info = serviceInfo ?: AccessibilityServiceInfo()
        info.eventTypes = AccessibilityEvent.TYPE_VIEW_FOCUSED or
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOWS_CHANGED or
                AccessibilityEvent.TYPE_VIEW_CLICKED or
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED or
                AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED or
                AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
        var flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            flags = flags or AccessibilityServiceInfo.FLAG_INPUT_METHOD_EDITOR
        }
        info.flags = flags
        info.notificationTimeout = 30
        serviceInfo = info

        // Check initial keyboard state
        checkAndNotifyKeyboard()

        // Ensure floating bubble is using accessibility overlay layer for highest z-order
        FloatingBubbleService.instance?.attachToAccessibilityService(this)
    }

    private var lastReportedKeyboardVisible: Boolean? = null

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val detectedPkg = getActivePackageName() ?: run {
            val eventPkg = event.packageName?.toString()
            if (!AppContextResolver.isIgnoredPackage(this, eventPkg)) eventPkg else null
        }

        if (detectedPkg != null) {
            FloatingBubbleManager.updateCurrentForegroundPackage(detectedPkg, this)
        }

        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                val source = event.source
                if (source != null && isEditableNode(source)) {
                    lastFocusedEditableNode = source
                }
                checkAndNotifyKeyboard()
            }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> {
                val inputFocused = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                if (inputFocused != null && isEditableNode(inputFocused)) {
                    lastFocusedEditableNode = inputFocused
                }
                checkAndNotifyKeyboard()
            }
        }
    }

    private fun checkAndNotifyKeyboard() {
        val currentWindows = try {
            windows ?: emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "Error querying windows", e)
            emptyList()
        }

        var isImePresent = false
        val windowTypesSeen = StringBuilder()

        for (window in currentWindows) {
            val typeStr = when (window.type) {
                AccessibilityWindowInfo.TYPE_APPLICATION -> "APP"
                AccessibilityWindowInfo.TYPE_INPUT_METHOD -> "IME"
                AccessibilityWindowInfo.TYPE_SYSTEM -> "SYSTEM"
                AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY -> "A11Y_OVERLAY"
                else -> "OTHER(${window.type})"
            }
            if (windowTypesSeen.isNotEmpty()) windowTypesSeen.append(", ")
            windowTypesSeen.append(typeStr)

            if (window.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                isImePresent = true
            }
        }

        if (lastReportedKeyboardVisible != isImePresent) {
            Log.d(TAG, "Keyboard visibility changed -> $isImePresent | Seen windows: [$windowTypesSeen]")
            lastReportedKeyboardVisible = isImePresent
            FloatingBubbleManager.notifyKeyboardVisibility(isImePresent)
        }
    }

    private fun isEditableNode(node: AccessibilityNodeInfo): Boolean {
        if (node.isEditable) return true
        val className = node.className?.toString() ?: ""
        if (className.contains("EditText", ignoreCase = true) ||
            className.contains("AutoCompleteTextView", ignoreCase = true) ||
            className.contains("NoteEditText", ignoreCase = true) ||
            className.contains("TextInputEditText", ignoreCase = true)
        ) {
            return true
        }
        return node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT }
    }

    override fun onInterrupt() {
        Log.w(TAG, "VoxStreamAccessibilityService interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance == this) {
            instance = null
            FloatingBubbleManager.setAccessibilityConnected(false)
        }
        lastFocusedEditableNode = null
        Log.d(TAG, "VoxStreamAccessibilityService destroyed")
    }

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

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            try {
                if (node.isShowingHintText) return ""
            } catch (e: Throwable) {}
            try {
                val hintText = node.hintText?.toString()
                if (!hintText.isNullOrBlank() && rawText.trim().equals(hintText.trim(), ignoreCase = true)) return ""
            } catch (e: Throwable) {}
        }

        val trimmed = rawText.trim().lowercase()
        val knownPlaceholders = setOf(
            "ask gemini", "ask gemini…", "ask gemini...",
            "ask google", "ask google…", "ask google...",
            "search", "search…", "search...",
            "search or type url", "search or type web address",
            "type a message", "message", "send a message", "write a message",
            "write a comment…", "write a comment...", "add a comment…", "add a comment...",
            "take a note", "take a note…", "take a note...", "note", "note…", "note...", "title"
        )
        if (knownPlaceholders.contains(trimmed)) return ""

        return rawText
    }

    /**
     * Injects transcribed text into the target active editable field via direct node editing (ACTION_SET_TEXT):
     * 1. Uses extractGenuineText to preserve existing user drafts while safely ignoring hints like "Ask Gemini".
     * 2. Determines cursor position safely without clobbering existing text.
     * 3. Splices the dictated text into the existing text at cursor.
     * 4. Performs direct text insertion via ACTION_SET_TEXT (zero clipboard touch).
     * 5. Updates cursor position to sit immediately after the newly inserted text.
     */
    fun injectTextSafely(node: AccessibilityNodeInfo, dictatedText: String): Boolean {
        // Extract genuine user text, safely ignoring isShowingHintText when length > 15 chars
        val currentText = extractGenuineText(node)
        val rawText = node.text?.toString() ?: ""

        // Determine current cursor position to insert text correctly
        var selectionStart = currentText.length
        var selectionEnd = currentText.length

        val selStart = try { node.textSelectionStart } catch (_: Throwable) { -1 }
        val selEnd = try { node.textSelectionEnd } catch (_: Throwable) { -1 }

        if (selStart in 0..rawText.length && selEnd in selStart..rawText.length) {
            // Ensure bounds are safe relative to currentText
            selectionStart = selStart.coerceIn(0, currentText.length)
            selectionEnd = selEnd.coerceIn(0, currentText.length)
        }

        // Splice the dictated text into the existing text at the cursor
        val beforeCursor = currentText.substring(0, selectionStart)
        val afterCursor = currentText.substring(selectionEnd)

        // Add a space if needed based on spacing logic
        val space = if (beforeCursor.isNotEmpty() && !beforeCursor.endsWith(" ") && !beforeCursor.endsWith("\n")) " " else ""
        val textToInsert = space + dictatedText

        val newText = beforeCursor + textToInsert + afterCursor

        // Perform direct node editing via ACTION_SET_TEXT
        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText)
        }
        val success = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
        Log.d(TAG, "injectTextSafely ACTION_SET_TEXT result: $success, len=${newText.length}")

        if (success) {
            // Update the cursor position to sit immediately after the newly inserted text
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
     * Injects transcribed text into the target active editable field using the Hybrid Input Engine:
     * 1. Priority 1 (Android 13+ AccessibilityInputConnection):
     *    Uses inputMethod.currentInputConnection.commitText(text, 1, null) directly at the blinking caret!
     * 2. Priority 2 (Companion VoxStreamInputMethodService):
     *    Uses VoxStreamInputMethodService.commitText(text) via the live InputConnection.
     * 3. Priority 3 (Direct Caret ActionSetText):
     *    Uses injectTextSafely(targetNode, newText) with cursor bounds checking and length > 15 safety.
     * 4. Zero clipboard usage: Leaves user's clipboard and keyboard history completely untouched!
     */
    fun injectText(newText: String): Boolean {
        if (newText.isEmpty()) return false
        if (FloatingBubbleManager.isCurrentAppSensitive.value) {
            Log.w(TAG, "injectText blocked: Smart Safe Mode is active")
            return false
        }

        // Priority 1: Modern Android 13+ (API 33+) AccessibilityInputConnection
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                val a11yIm = inputMethod
                val a11yIc = a11yIm?.currentInputConnection
                if (a11yIc != null) {
                    a11yIc.commitText(newText, 1, null)
                    Log.d(TAG, "AccessibilityInputConnection.commitText completed successfully (caret-level, zero-clipboard)")
                    return true
                }
            } catch (e: Throwable) {
                Log.w(TAG, "AccessibilityInputConnection injection error: ${e.message}")
            }
        }

        // Priority 2: Companion VoxStreamInputMethodService
        if (VoxStreamInputMethodService.commitText(newText)) {
            Log.d(TAG, "VoxStreamInputMethodService committed text successfully")
            return true
        }

        // Priority 3: Direct node editing via injectTextSafely
        val targetNode = getActiveEditableNode()
        if (targetNode != null) {
            val targetPkg = targetNode.packageName?.toString() ?: ""
            Log.d(TAG, "Fallback to direct node injection for $targetPkg")
            return injectTextSafely(targetNode, newText)
        }

        return false
    }

    private fun performPasteInjection(targetNode: AccessibilityNodeInfo, newText: String): Boolean {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val originalClip = try {
            clipboard?.primaryClip
        } catch (e: Exception) {
            Log.w(TAG, "Could not read existing clipboard: ${e.message}")
            null
        }

        Log.d(TAG, "Executing paste-injection for pkg=${targetNode.packageName}, textLen=${newText.length}")

        // Focus the node FIRST so the target app has input focus before touching clipboard
        try {
            targetNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            targetNode.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
        } catch (e: Exception) {
            Log.w(TAG, "Notice requesting focus before paste: ${e.message}")
        }

        val dictationClip = ClipData.newPlainText("VoxStream Dictation", newText)
        // Mark as sensitive on Android 13+ to prevent clipboard toasts
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                val extras = android.os.PersistableBundle().apply { putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE, true) }
                dictationClip.description.extras = extras
            } catch (e: Throwable) {}
        }

        try {
            clipboard?.setPrimaryClip(dictationClip)
        } catch (e: Exception) {
            Log.e(TAG, "Failed setting dictation clip", e)
            return false
        }

        // Primary paste attempt
        val initialPasteResult = targetNode.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        Log.d(TAG, "Initial ACTION_PASTE dispatch: $initialPasteResult")

        // Schedule self-clearing / restoration after 150ms:
        // - If the user had previous/pinned text, it is restored back to the clipboard!
        // - If the clipboard was empty beforehand, the temporary dictation deletes itself.
        mainHandler.postDelayed({
            safeRestoreOriginalClipboard(clipboard, originalClip, newText)
        }, 150L)

        return true
    }

    /**
     * Safely restores the original clipboard ONLY IF the current clipboard is STILL
     * the temporary dictation clip set by VoxStream.
     * Prevents race conditions where a user copied new data during the verification window.
     */
    private fun safeRestoreOriginalClipboard(clipboard: ClipboardManager?, originalClip: ClipData?, expectedDictationText: String) {
        try {
            val currentClip = try { clipboard?.primaryClip } catch (e: Exception) { null }

            val isStillOurDictationClip = if (currentClip != null && currentClip.itemCount > 0) {
                val currentText = currentClip.getItemAt(0)?.text?.toString() ?: ""
                val label = currentClip.description?.label?.toString() ?: ""
                currentText == expectedDictationText || label == "VoxStream Dictation"
            } else {
                false
            }

            if (isStillOurDictationClip) {
                if (originalClip != null) {
                    clipboard?.setPrimaryClip(originalClip)
                    Log.d(TAG, "Restored original user clipboard safely (no collision detected)")
                } else {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        clipboard?.clearPrimaryClip()
                    } else {
                        clipboard?.setPrimaryClip(ClipData.newPlainText("", ""))
                    }
                    Log.d(TAG, "Cleared temporary dictation clip from clipboard")
                }
            } else {
                Log.i(TAG, "User or external app copied new data during paste verification window. Preserving user's new clipboard content without overwriting!")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error in safeRestoreOriginalClipboard: ${e.message}")
        }
    }

    private fun getActiveEditableNode(): AccessibilityNodeInfo? {
        // 1. Try system input focus directly
        try {
            val inputFocused = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (inputFocused != null && isEditableNode(inputFocused)) {
                Log.d(TAG, "Found target editable via findFocus(FOCUS_INPUT)")
                return inputFocused
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error finding FOCUS_INPUT: ${e.message}")
        }

        // 2. Try rootInActiveWindow
        try {
            val root = rootInActiveWindow
            if (root != null) {
                val rootFocused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                if (rootFocused != null && isEditableNode(rootFocused)) {
                    Log.d(TAG, "Found target editable via rootInActiveWindow FOCUS_INPUT")
                    return rootFocused
                }
                val found = findFocusedEditableInTree(root)
                if (found != null) {
                    Log.d(TAG, "Found target editable via rootInActiveWindow tree scan")
                    return found
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error checking rootInActiveWindow: ${e.message}")
        }

        // 3. Search across all interactive application windows (e.g. Google Keep, WhatsApp, Chrome)
        try {
            val currentWindows = windows
            if (!currentWindows.isNullOrEmpty()) {
                // Pass A: Look for focused editable in application windows
                for (w in currentWindows) {
                    if (w.type == AccessibilityWindowInfo.TYPE_APPLICATION) {
                        val wRoot = w.root ?: continue
                        val inputF = wRoot.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                        if (inputF != null && isEditableNode(inputF)) {
                            Log.d(TAG, "Found target editable in APP window via FOCUS_INPUT")
                            return inputF
                        }
                        val focusedInTree = findFocusedEditableInTree(wRoot)
                        if (focusedInTree != null) {
                            Log.d(TAG, "Found target editable in APP window tree scan (focused)")
                            return focusedInTree
                        }
                    }
                }

                // Pass B: If floating overlay held focus, look for any active visible editable node in APP windows
                for (w in currentWindows) {
                    if (w.type == AccessibilityWindowInfo.TYPE_APPLICATION) {
                        val wRoot = w.root ?: continue
                        val anyEditable = findBestEditableInTree(wRoot)
                        if (anyEditable != null) {
                            Log.d(TAG, "Found target editable in APP window tree scan (active editable fallback)")
                            return anyEditable
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error searching interactive windows: ${e.message}")
        }

        // 4. Fallback to last recorded node if still valid and editable
        val last = lastFocusedEditableNode
        if (last != null) {
            try {
                if (last.refresh() && isEditableNode(last)) {
                    Log.d(TAG, "Found target editable via refreshed lastFocusedEditableNode")
                    return last
                }
            } catch (e: Exception) {
                Log.w(TAG, "Last focused node refresh failed", e)
            }
        }
        return null
    }

    private fun findFocusedEditableInTree(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isFocused && isEditableNode(node)) {
            return node
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findFocusedEditableInTree(child)
            if (result != null) return result
        }
        return null
    }

    private fun findBestEditableInTree(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (isEditableNode(node) && node.isVisibleToUser) {
            return node
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findBestEditableInTree(child)
            if (result != null) return result
        }
        return null
    }

    fun getActiveApplicationWindow(): AccessibilityWindowInfo? {
        val currentWindows = try { windows } catch (e: Throwable) { null }
        if (!currentWindows.isNullOrEmpty()) {
            for (window in currentWindows) {
                if (window.type == AccessibilityWindowInfo.TYPE_APPLICATION && (window.isFocused || window.isActive)) {
                    return window
                }
            }
            for (window in currentWindows) {
                if (window.type == AccessibilityWindowInfo.TYPE_APPLICATION) {
                    return window
                }
            }
        }
        return null
    }

    fun getActivePackageName(): String? {
        // 1. Inspect windows for real TYPE_APPLICATION window
        val currentWindows = try { windows } catch (e: Throwable) { null }
        if (!currentWindows.isNullOrEmpty()) {
            // Check focused or active application window first
            for (window in currentWindows) {
                if (window.type == AccessibilityWindowInfo.TYPE_APPLICATION && (window.isFocused || window.isActive)) {
                    val pkg = window.root?.packageName?.toString()
                    if (!AppContextResolver.isIgnoredPackage(this, pkg)) {
                        return pkg
                    }
                }
            }
            // Check any application window in z-order
            for (window in currentWindows) {
                if (window.type == AccessibilityWindowInfo.TYPE_APPLICATION) {
                    val pkg = window.root?.packageName?.toString()
                    if (!AppContextResolver.isIgnoredPackage(this, pkg)) {
                        return pkg
                    }
                }
            }
        }

        // 2. Check last focused editable node if it belongs to a valid target app
        val lastNodePkg = lastFocusedEditableNode?.packageName?.toString()
        if (!AppContextResolver.isIgnoredPackage(this, lastNodePkg)) {
            return lastNodePkg
        }

        // 3. Check root in active window
        val rootPkg = rootInActiveWindow?.packageName?.toString()
        if (!AppContextResolver.isIgnoredPackage(this, rootPkg)) {
            return rootPkg
        }

        return null
    }

    /**
     * Safely extracts visible conversation context from the locked target application window.
     * Collects the last 2-3 visible non-editable text snippets (e.g. preceding AI replies).
     */
    fun extractRecentConversationContext(): String? {
        if (FloatingBubbleManager.isCurrentAppSensitive.value) {
            Log.w(TAG, "extractRecentConversationContext blocked: Smart Safe Mode is active")
            return null
        }
        val targetNode = getActiveEditableNode()
        val root = rootInActiveWindow ?: targetNode ?: return null
        val snippets = mutableListOf<String>()

        try {
            fun traverse(node: AccessibilityNodeInfo, depth: Int) {
                if (depth > 6 || snippets.size >= 4) return
                val text = node.text?.toString()?.trim()
                if (!text.isNullOrBlank() && text.length > 8 && node != targetNode) {
                    val cls = node.className?.toString() ?: ""
                    if (!cls.contains("Button", ignoreCase = true) &&
                        !cls.contains("EditText", ignoreCase = true) &&
                        !cls.contains("ImageView", ignoreCase = true)
                    ) {
                        snippets.add(text.take(300))
                    }
                }
                for (i in 0 until node.childCount) {
                    val child = node.getChild(i) ?: continue
                    traverse(child, depth + 1)
                }
            }
            traverse(root, 0)
        } catch (e: Exception) {
            Log.w(TAG, "Notice traversing conversation context: ${e.message}")
        }

        return if (snippets.isNotEmpty()) snippets.takeLast(3).joinToString("\n---\n") else null
    }
}
