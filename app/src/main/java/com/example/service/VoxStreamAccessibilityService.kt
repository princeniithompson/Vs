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

    @Volatile
    var lastSeenClassName: String? = null

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

        // FLAG_REPORT_VIEW_IDS: Required to resolve view resource IDs in target editable fields for direct text injection
        // FLAG_INCLUDE_NOT_IMPORTANT_VIEWS: Required to access nested or custom editor view hierarchies in rich text and messaging inputs
        // FLAG_INPUT_METHOD_EDITOR: Connects directly to the active editor's InputConnection (Wispr Flow architecture)
        var flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
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

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            lastSeenClassName = event.className?.toString()
        }

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
                } else {
                    try {
                        if (lastFocusedEditableNode?.refresh() == false) {
                            lastFocusedEditableNode = null
                        }
                    } catch (_: Exception) {
                        lastFocusedEditableNode = null
                    }
                }
                checkAndNotifyKeyboard()
            }
        }
    }

    private fun checkAndNotifyKeyboard() {
        val currentWindows = try {
            getWindows() ?: emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "Error querying getWindows()", e)
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

        // Fallback: If getWindows() does not report IME directly without interactive windows flag,
        // verify if an editable input node currently has focus
        if (!isImePresent) {
            val inputFocused = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (inputFocused != null && isEditableNode(inputFocused)) {
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
        return com.example.service.floating.FloatingTextInjector.extractGenuineText(node)
    }

    fun injectTextSafely(node: AccessibilityNodeInfo, dictatedText: String): Boolean {
        return com.example.service.floating.FloatingTextInjector.injectTextSafely(node, dictatedText)
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

        // Cancel any pending asynchronous paste before starting a new injection
        com.example.service.floating.FloatingTextInjector.cancelPendingPaste(mainHandler)

        // Priority 1: Direct Android 13+ AccessibilityInputConnection (Wispr Flow architecture)
        if (commitTextViaInputMethod(newText)) {
            Log.d(TAG, "AccessibilityInputConnection committed text successfully")
            return true
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
            Log.d(TAG, "Executing direct node injection for $targetPkg")
            val injected = injectTextSafely(targetNode, newText)
            if (injected) return true

            // Fallback: If direct node edit failed on custom editors (e.g. rich text), use paste injection
            if (AppClassifier.isPasteRequired(targetPkg)) {
                return com.example.service.floating.FloatingTextInjector.performPasteInjection(targetNode, newText, this, mainHandler)
            }
        }

        return false
    }

    @androidx.annotation.VisibleForTesting(otherwise = androidx.annotation.VisibleForTesting.PRIVATE)
    internal fun commitTextViaInputMethod(text: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Log.d(TAG, "InputConnection unavailable: Android version < 13")
            return false
        }

        return try {
            val im = inputMethod
            if (im == null || !im.currentInputStarted) {
                Log.d(TAG, "InputConnection unavailable: inputMethod is null or not started")
                return false
            }

            val ic = im.currentInputConnection
            if (ic == null) {
                Log.d(TAG, "InputConnection unavailable: currentInputConnection is null")
                return false
            }

            val target = lastFocusedEditableNode ?: getActiveEditableNode()
            target?.performAction(AccessibilityNodeInfo.ACTION_FOCUS)

            val beforeSurrounding = try {
                ic.getSurroundingText(text.length + 64, 64, 0)?.text?.toString()
            } catch (_: Throwable) {
                null
            }
            val beforeNodeText = target?.text?.toString()

            ic.commitText(text, 1, null)

            val afterSurrounding = try {
                ic.getSurroundingText(text.length + 64, 64, 0)?.text?.toString()
            } catch (_: Throwable) {
                null
            }
            target?.refresh()
            val afterNodeText = target?.text?.toString()

            val commitSucceeded = when {
                afterSurrounding != null && afterSurrounding.contains(text) -> true
                afterNodeText != null && afterNodeText.contains(text) -> true
                afterSurrounding != null && beforeSurrounding != null && afterSurrounding != beforeSurrounding -> true
                afterNodeText != null && beforeNodeText != null && afterNodeText != beforeNodeText -> true
                afterSurrounding != null && beforeSurrounding != null && afterSurrounding == beforeSurrounding -> {
                    Log.w(TAG, "InputConnection commit rejected: surrounding text unchanged after commit")
                    false
                }
                else -> {
                    Log.d(TAG, "InputConnection commit dispatched cleanly without explicit text feedback (preventing duplicate insertion)")
                    true
                }
            }

            if (commitSucceeded) {
                Log.d(TAG, "InputConnection commit succeeded, len=${text.length}")
                true
            } else {
                Log.w(TAG, "InputConnection commit failed: editor did not reflect committed text")
                false
            }
        } catch (e: Throwable) {
            Log.w(TAG, "InputConnection commit threw an exception: ${e.message}", e)
            false
        }
    }

    private fun getActiveEditableNode(): AccessibilityNodeInfo? {
        // 1. Try system input focus directly
        try {
            val inputFocused = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (inputFocused != null && isEditableNode(inputFocused)) {
                Log.d(TAG, "Found target editable via findFocus(FOCUS_INPUT)")
                lastFocusedEditableNode = inputFocused
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
                    lastFocusedEditableNode = rootFocused
                    return rootFocused
                }
                val found = findFocusedEditableInTree(root)
                if (found != null) {
                    Log.d(TAG, "Found target editable via rootInActiveWindow tree scan")
                    lastFocusedEditableNode = found
                    return found
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error checking rootInActiveWindow: ${e.message}")
        }

        // 3. Search across getWindows() scoped strictly to the active supported package
        try {
            val currentWindows = try { getWindows() } catch (e: Throwable) { null }
            if (!currentWindows.isNullOrEmpty()) {
                val activePkg = getActivePackageName()
                // Pass A: Look for focused editable in application windows belonging to active package
                for (w in currentWindows) {
                    if (w.type == AccessibilityWindowInfo.TYPE_APPLICATION) {
                        val wRoot = w.root ?: continue
                        val pkg = wRoot.packageName?.toString()
                        if (activePkg != null && pkg != activePkg) continue
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
                        val pkg = wRoot.packageName?.toString()
                        if (activePkg != null && pkg != activePkg) continue
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
                } else {
                    lastFocusedEditableNode = null
                }
            } catch (e: Exception) {
                Log.w(TAG, "Last focused node refresh failed", e)
                lastFocusedEditableNode = null
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
        val currentWindows = try { getWindows() } catch (e: Throwable) { null }
        if (!currentWindows.isNullOrEmpty()) {
            val activePkg = getActivePackageName()
            for (window in currentWindows) {
                if (window.type == AccessibilityWindowInfo.TYPE_APPLICATION && (window.isFocused || window.isActive)) {
                    val root = window.root
                    if (activePkg == null || root?.packageName?.toString() == activePkg) {
                        return window
                    }
                }
            }
            for (window in currentWindows) {
                if (window.type == AccessibilityWindowInfo.TYPE_APPLICATION) {
                    val root = window.root
                    if (activePkg == null || root?.packageName?.toString() == activePkg) {
                        return window
                    }
                }
            }
        }
        return null
    }

    fun getActivePackageName(): String? {
        // 1. Check direct input focus first
        try {
            val focusedNode = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            val focusedPkg = focusedNode?.packageName?.toString()
            if (!focusedPkg.isNullOrEmpty() && !AppContextResolver.isIgnoredPackage(this, focusedPkg)) {
                return focusedPkg
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error checking focus for active package: ${e.message}")
        }

        // 2. Check root in active window
        val rootPkg = rootInActiveWindow?.packageName?.toString()
        if (!AppContextResolver.isIgnoredPackage(this, rootPkg)) {
            return rootPkg
        }

        // 3. Check last focused editable node if it belongs to a valid target app
        val last = lastFocusedEditableNode
        if (last != null) {
            try {
                if (last.refresh()) {
                    val lastNodePkg = last.packageName?.toString()
                    if (!AppContextResolver.isIgnoredPackage(this, lastNodePkg)) {
                        return lastNodePkg
                    }
                } else {
                    lastFocusedEditableNode = null
                }
            } catch (_: Exception) {
                lastFocusedEditableNode = null
            }
        }

        // 4. Inspect getWindows() for TYPE_APPLICATION window
        val currentWindows = try { getWindows() } catch (e: Throwable) { null }
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

    /**
     * Extracts rich AI screen context (chat messages, visible instructions, and draft input)
     * strictly when the foreground app is classified as an AI application.
     */
    fun extractAiScreenContext(): com.example.data.CapturedScreenContext? {
        if (FloatingBubbleManager.isCurrentAppSensitive.value) {
            Log.w(TAG, "extractAiScreenContext blocked: Sensitive app or Smart Safe Mode active")
            return null
        }

        val activePkg = getActivePackageName() ?: return null
        val appName = AppContextResolver.resolveAppName(this, activePkg)

        // Strict boundary: Only extract context if the app is classified as an AI app
        if (!AppClassifier.isAiChatApp(activePkg, appName)) {
            Log.d(TAG, "extractAiScreenContext skipped: $appName ($activePkg) is not an AI app")
            return null
        }

        val targetEditableNode = getActiveEditableNode()
        val focusedInputDraft = targetEditableNode?.let { extractGenuineText(it) }?.takeIf { it.isNotBlank() }

        val root = rootInActiveWindow ?: targetEditableNode ?: return null
        val conversationMessages = mutableListOf<String>()
        var systemInstructionText: String? = null

        try {
            fun traverseForAiContext(node: AccessibilityNodeInfo, depth: Int) {
                if (depth > 12 || conversationMessages.size >= 12) return

                val text = node.text?.toString()?.trim()
                val className = node.className?.toString() ?: ""
                val viewId = node.viewIdResourceName ?: ""

                if (!text.isNullOrBlank() && text.length >= 4 && node != targetEditableNode) {
                    val isInteractiveButton = className.contains("Button", ignoreCase = true) ||
                            viewId.contains("send", ignoreCase = true) ||
                            viewId.contains("attach", ignoreCase = true) ||
                            viewId.contains("menu", ignoreCase = true)

                    val isHeaderOrToolbar = viewId.contains("toolbar", ignoreCase = true) ||
                            viewId.contains("action_bar", ignoreCase = true) ||
                            viewId.contains("status", ignoreCase = true)

                    if (!isInteractiveButton && !isHeaderOrToolbar && !className.contains("ImageView", ignoreCase = true)) {
                        // Check if text looks like system/model instruction or prompt guidance
                        if (text.contains("Custom Instructions", ignoreCase = true) ||
                            text.contains("System Prompt", ignoreCase = true) ||
                            text.contains("You are a helpful assistant", ignoreCase = true)
                        ) {
                            if (systemInstructionText == null) {
                                systemInstructionText = text.take(400)
                            }
                        } else if (text.length > 8 && !conversationMessages.contains(text)) {
                            // Don't capture tiny UI labels or single-word indicators
                            conversationMessages.add(text.take(600))
                        }
                    }
                }

                for (i in 0 until node.childCount) {
                    val child = node.getChild(i) ?: continue
                    traverseForAiContext(child, depth + 1)
                }
            }

            traverseForAiContext(root, 0)
        } catch (e: Exception) {
            Log.w(TAG, "Error traversing AI screen context: ${e.message}")
        }

        val result = com.example.data.CapturedScreenContext(
            packageName = activePkg,
            appName = appName,
            conversationSnippets = conversationMessages.takeLast(10),
            systemInstructions = systemInstructionText,
            focusedInputText = focusedInputDraft
        )
        Log.d(TAG, "Extracted AI Screen Context from $appName: ${result.conversationSnippets.size} messages")
        return result
    }
}
