package com.example.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.example.service.floating.FloatingTextInjector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

open class VoxStreamAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "VoxStreamAccessService"
        const val VERIFICATION_ATTEMPTS = 2
        const val VERIFY_WAIT_MS = 150L
        /** Verification may poll, but a slow read must not be treated as a failed insert. */
        const val VERIFY_POLL_BUDGET_MS = 600L
        var instance: VoxStreamAccessibilityService? = null
            internal set
    }

    private var lastFocusedEditableNode: AccessibilityNodeInfo? = null

    @Volatile
    var lastSeenClassName: String? = null

    private val delayedCheckRunnable = Runnable {
        checkAndNotifyKeyboard()
    }

    private val packageReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: android.content.Intent?) {
            if (context != null) {
                AppDetector.refreshWebApkInventory(context)
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        FloatingBubbleManager.setAccessibilityConnected(true)
        Log.d(TAG, "VoxStreamAccessibilityService connected and active")

        AppDetector.init(this)
        try {
            val packageFilter = android.content.IntentFilter().apply {
                addAction(android.content.Intent.ACTION_PACKAGE_ADDED)
                addAction(android.content.Intent.ACTION_PACKAGE_REMOVED)
                addAction(android.content.Intent.ACTION_PACKAGE_REPLACED)
                addDataScheme("package")
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(packageReceiver, packageFilter, RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(packageReceiver, packageFilter)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error registering package update receiver: ${e.message}")
        }

        val info = serviceInfo ?: AccessibilityServiceInfo()
        info.eventTypes = AccessibilityEvent.TYPE_VIEW_FOCUSED or
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOWS_CHANGED or
                AccessibilityEvent.TYPE_VIEW_CLICKED or
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED or
                AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED or
                AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC

        // FLAG_RETRIEVE_INTERACTIVE_WINDOWS: Required to extract window titles across all active foreground applications
        // FLAG_REPORT_VIEW_IDS: Required to resolve view resource IDs in target editable fields for direct text injection
        // FLAG_INCLUDE_NOT_IMPORTANT_VIEWS: Required to access nested or custom editor view hierarchies in rich text and messaging inputs
        // FLAG_INPUT_METHOD_EDITOR (API 33+): binds an input connection so commitText can type into the focused editor
        var flags = info.flags or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            flags = flags or AccessibilityServiceInfo.FLAG_INPUT_METHOD_EDITOR
            Log.i(TAG, "FLAG_INPUT_METHOD_EDITOR enabled (API ${Build.VERSION.SDK_INT})")
        } else {
            Log.i(TAG, "FLAG_INPUT_METHOD_EDITOR skipped; API ${Build.VERSION.SDK_INT} is below 33")
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
            val eventPkg = event.packageName?.toString()
            if (!eventPkg.isNullOrBlank() && !AppContextResolver.isIgnoredPackage(this, eventPkg)) {
                val winTitle = getActiveApplicationWindow()?.title?.toString()
                    ?: event.text.firstOrNull()?.toString()
                val resolvedApp = AppDetector.resolve(
                    context = this,
                    packageName = eventPkg,
                    className = lastSeenClassName,
                    windowTitle = winTitle
                )
                FloatingBubbleManager.updateLearnedAppContext(resolvedApp.appName, resolvedApp.category)
                com.example.data.AppDetectionLogRepository.logEvent(
                    com.example.data.AppDetectionEvent(
                        rawPackageName = eventPkg,
                        rawWindowTitle = winTitle,
                        packageManagerLabel = try {
                            packageManager.getApplicationLabel(packageManager.getApplicationInfo(eventPkg, 0)).toString()
                        } catch (_: Throwable) { null },
                        resolvedAppName = resolvedApp.appName,
                        classificationSource = if (resolvedApp.isLocallyResolved) "4_SIGNAL_CASCADE" else "AI_MODEL",
                        finalCategory = resolvedApp.category
                    )
                )
            }
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
                checkSensitivityState(event)
            }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> {
                val inputFocused = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                if (inputFocused != null && isEditableNode(inputFocused)) {
                    lastFocusedEditableNode = inputFocused
                } else {
                    val eventPkg = event.packageName?.toString()
                    // Never clear lastFocusedEditableNode due to our own overlay or system popups
                    if (eventPkg != null && eventPkg != packageName && !AppContextResolver.isIgnoredPackage(this, eventPkg)) {
                        try {
                            if (lastFocusedEditableNode?.refresh() == false) {
                                lastFocusedEditableNode = null
                            }
                        } catch (_: Exception) {
                            lastFocusedEditableNode = null
                        }
                    }
                }
                checkAndNotifyKeyboard()
                checkSensitivityState(event)
            }
        }
    }

    private fun checkSensitivityState(event: AccessibilityEvent? = null) {
        if (!FloatingBubbleManager.isSmartSafeModeEnabled.value) {
            FloatingBubbleManager.updateSensitivity(false, "smart_safe_mode_disabled")
            return
        }

        val pkg = getActivePackageName() ?: event?.packageName?.toString()
        if (AppContextResolver.isIgnoredPackage(this, pkg)) {
            return
        }

        val focusedNode = try { findFocus(AccessibilityNodeInfo.FOCUS_INPUT) } catch (_: Throwable) { null }
            ?: lastFocusedEditableNode
            ?: event?.source
        val activeRoot = try { rootInActiveWindow ?: getActiveApplicationWindow()?.root } catch (_: Throwable) { null }
        val winTitle = try { getActiveApplicationWindow()?.title?.toString() } catch (_: Throwable) { null }
            ?: event?.text?.firstOrNull()?.toString()
        val resolvedAppName = FloatingBubbleManager.currentResolvedAppContext.value?.name

        val isSensitive = SafeModeClassifier.isSensitiveContext(
            context = this,
            packageName = pkg,
            focusedNode = focusedNode,
            rootNode = activeRoot,
            className = lastSeenClassName,
            windowTitle = winTitle,
            resolvedAppName = resolvedAppName
        )

        FloatingBubbleManager.updateSensitivity(isSensitive, "context_check: pkg=$pkg, sensitive=$isSensitive")
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

        if (lastReportedKeyboardVisible != isImePresent) {
            Log.d(TAG, "Keyboard visibility changed -> $isImePresent | Seen windows: [$windowTypesSeen]")
            lastReportedKeyboardVisible = isImePresent
            FloatingBubbleManager.notifyKeyboardVisibility(isImePresent)
        }
    }

    private fun isEditableNode(node: AccessibilityNodeInfo): Boolean {
        if (node.isEditable) return true
        val className = node.className?.toString() ?: ""
        val viewId = try { node.viewIdResourceName?.lowercase() ?: "" } catch (_: Throwable) { "" }
        if (className.contains("EditText", ignoreCase = true) ||
            className.contains("AutoCompleteTextView", ignoreCase = true) ||
            className.contains("NoteEditText", ignoreCase = true) ||
            className.contains("TextInputEditText", ignoreCase = true) ||
            className.contains("ExtractEditText", ignoreCase = true) ||
            className.contains("ComposeEditableText", ignoreCase = true) ||
            viewId.contains("edit_note_text") ||
            viewId.contains("editor_title") ||
            viewId.contains("note_text") ||
            viewId.contains("content") ||
            viewId.contains("input") ||
            viewId.contains("search") ||
            viewId.contains("message")
        ) {
            return true
        }
        return FloatingTextInjector.supportsActionSetText(node)
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
        try {
            unregisterReceiver(packageReceiver)
        } catch (_: Exception) {}
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
     * Injects transcribed text. The clipboard is never written here.
     *
     * P1, API 33+: the accessibility service input connection `commitText`. No clipboard.
     * P2: the headless companion IME [VoxStreamInputMethodService.commitText], when it is active.
     * P3: caret-aware [injectTextSafely], only when P1 and P2 are both unavailable.
     * [injectionMethod] is `NO_TARGET_NODE` only when none of those targets exist. The caller
     * may copy to the clipboard in that case alone.
     */
    open suspend fun injectTextDetailed(newText: String): com.example.data.InjectionEvent {
        val startTime = android.os.SystemClock.elapsedRealtime()
        val wordCount = newText.trim().split("\\s+".toRegex()).count { it.isNotBlank() }
        val rawPreview = newText.take(40)

        if (newText.isEmpty()) {
            return com.example.data.InjectionEvent(
                textLength = 0,
                wordCount = 0,
                rawTextPreview = "",
                finalOutcome = "FAILED",
                injectionMethod = "NONE",
                resultDetails = "SKIPPED: Text is empty"
            )
        }

        if (FloatingBubbleManager.isCurrentAppSensitive.value) {
            Log.w(TAG, "injectText blocked: Smart Safe Mode is active")
            val targetPkg = try { getActivePackageName() } catch (_: Throwable) { null } ?: "Unknown"
            val appName = try {
                AppContextResolver.resolve(this, targetPkg)?.appName ?: "App"
            } catch (_: Throwable) {
                "App"
            }
            return com.example.data.InjectionEvent(
                targetPackage = targetPkg,
                targetAppName = appName,
                textLength = newText.length,
                wordCount = wordCount,
                rawTextPreview = rawPreview,
                finalOutcome = "FAILED",
                injectionMethod = "BLOCKED_SAFE_MODE",
                resultDetails = "BLOCKED: Smart Safe Mode active"
            )
        }

        val p1 = commitWithAccessibilityInputConnection(newText)
        val p2 = if (p1 == null) commitWithCompanionIme(newText) else null

        var targetNode: AccessibilityNodeInfo? = null
        val (finalOutcome, injectionMethod, resultDetails) = when {
            p1 == true -> {
                Log.i(TAG, "Injection priority P1: accessibility InputConnection.commitText succeeded")
                Triple("SUCCESS", "ACCESSIBILITY_INPUT_CONNECTION", "SUCCESS: P1 accessibility input connection commitText")
            }
            p1 == false -> {
                Log.w(TAG, "Injection priority P1: commitText did not complete. Clipboard suppressed.")
                Triple("FAILED", "ACCESSIBILITY_INPUT_CONNECTION", "FAILED: P1 commitText did not complete")
            }
            p2 == true -> {
                Log.i(TAG, "Injection priority P2: companion IME commitText succeeded")
                Triple("SUCCESS", "COMPANION_IME", "SUCCESS: P2 companion IME commitText")
            }
            p2 == false -> {
                Log.w(TAG, "Injection priority P2: commitText returned false. Clipboard suppressed.")
                Triple("FAILED", "COMPANION_IME", "FAILED: P2 commitText returned false")
            }
            else -> {
                Log.i(TAG, "Injection priority P3: P1 and P2 unavailable, trying caret-aware node write")
                targetNode = getActiveEditableNode()
                if (targetNode != null) {
                    try {
                        targetNode.refresh()
                    } catch (e: Throwable) {
                        Log.w(TAG, "Pre-write node refresh threw: ${e.message}")
                    }
                    try {
                        if (!targetNode.isFocused) {
                            targetNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                        }
                    } catch (_: Throwable) {}

                    val writeResult = writeRangeAndVerify(targetNode, newText, replaceRange = false, attempts = 2)
                    if (writeResult.outcome == AxWriteOutcome.SUCCESS) {
                        Log.i(TAG, "Injection priority P3: caret-aware ACTION_SET_TEXT succeeded")
                        Triple("SUCCESS", "DIRECT_ACTION_SET_TEXT", "SUCCESS: ${writeResult.details}")
                    } else {
                        Log.w(TAG, "Injection priority P3 failed (${writeResult.details}). Clipboard suppressed.")
                        Triple("FAILED", "DIRECT_ACTION_SET_TEXT", "FAILED: ${writeResult.details}")
                    }
                } else {
                    Log.i(TAG, "Injection priority: no target field. Clipboard fallback is allowed.")
                    Triple("FAILED", "NO_TARGET_NODE", "FAILED: No active editable node found")
                }
            }
        }

        val elapsed = android.os.SystemClock.elapsedRealtime() - startTime
        val nodeToInspect = targetNode ?: lastFocusedEditableNode
        val nodePkg = try { nodeToInspect?.packageName?.toString() ?: getActivePackageName() ?: "Unknown" } catch (_: Throwable) { "Unknown" }
        val appName = try { AppContextResolver.resolve(this, nodePkg)?.appName ?: "App" } catch (_: Throwable) { "App" }

        return com.example.data.InjectionEvent(
            targetPackage = nodePkg,
            targetAppName = appName,
            targetNodeClass = nodeToInspect?.className?.toString(),
            isFocused = nodeToInspect?.isFocused ?: false,
            isEditable = nodeToInspect?.let { isEditableNode(it) } ?: false,
            windowId = nodeToInspect?.windowId ?: -1,
            textLength = newText.length,
            wordCount = wordCount,
            injectionMethod = injectionMethod,
            resultDetails = resultDetails,
            finalOutcome = finalOutcome,
            durationMs = elapsed,
            rawTextPreview = rawPreview
        )
    }

    /**
     * P1. On API 33+, commits on the main looper through
     * [android.accessibilityservice.InputMethod.AccessibilityInputConnection].
     * That `commitText` returns void, so a call that does not throw is success.
     * Returns null when the connection is unavailable, and false when the call throws.
     * A non-null connection means an editor still holds input focus.
     */
    protected open suspend fun commitWithAccessibilityInputConnection(text: String): Boolean? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Log.d(TAG, "P1 unavailable: accessibility input connection requires API 33")
            return null
        }
        return withContext(Dispatchers.Main.immediate) {
            commitAccessibilityInputConnectionOnMain(text)
        }
    }

    private fun commitAccessibilityInputConnectionOnMain(text: String): Boolean? {
        var connectionBound = false
        return try {
            val method = inputMethod
            if (method == null) {
                Log.d(TAG, "P1 unavailable: accessibility inputMethod is null")
                null
            } else {
                val connection = method.currentInputConnection
                if (connection == null) {
                    Log.d(TAG, "P1 unavailable: currentInputConnection is null (editor focus was lost)")
                    null
                } else {
                    connectionBound = true
                    val focusedNode = try {
                        findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let { isEditableNode(it) } == true
                    } catch (_: Throwable) {
                        false
                    }
                    if (!focusedNode) {
                        Log.w(TAG, "P1: committing while no editable FOCUS_INPUT node is visible; input connection is still bound")
                    }
                    connection.commitText(text, 1, null)
                    Log.i(TAG, "P1 commitText invoked focusedNode=$focusedNode len=${text.length}")
                    true
                }
            }
        } catch (e: Throwable) {
            if (connectionBound) {
                Log.w(TAG, "P1 commitText threw: ${e.message}", e)
                false
            } else {
                Log.w(TAG, "P1 unavailable: ${e.message}", e)
                null
            }
        }
    }

    /**
     * P2. Commits through the headless companion IME when that keyboard is the active one.
     * Returns null when it is not active. Runs on the main looper.
     */
    protected open suspend fun commitWithCompanionIme(text: String): Boolean? {
        if (!VoxStreamInputMethodService.isServiceActive) {
            Log.d(TAG, "P2 unavailable: companion IME is not active")
            return null
        }
        return withContext(Dispatchers.Main.immediate) {
            val committed = VoxStreamInputMethodService.commitText(text)
            Log.i(TAG, "P2 companion IME commitText result=$committed len=${text.length}")
            committed
        }
    }

    /**
     * Checks if there is an active focused editable node on screen.
     */
    fun hasActiveEditableNode(): Boolean {
        return getActiveEditableNode() != null
    }

    /**
     * Inserts [textToInsert] into [targetNode] with cursor-aware ACTION_SET_TEXT,
     * ACTION_SET_SELECTION, and a two-step edit nudge. Never touches the clipboard.
     * A focused field that fails to accept the write does not copy to the clipboard.
     */
    suspend fun writeRangeAndVerify(
        targetNode: AccessibilityNodeInfo,
        textToInsert: String,
        replaceRange: Boolean = false,
        attempts: Int = VERIFICATION_ATTEMPTS
    ): NodeWriteResult {
        if (textToInsert.isBlank()) {
            Log.w(TAG, "writeRangeAndVerify: textToInsert is blank, aborting to prevent empty note discard")
            return NodeWriteResult(
                outcome = AxWriteOutcome.FAILED,
                details = "Skipped: text to insert is blank",
                writtenText = textToInsert
            )
        }

        if (replaceRange) {
            Log.d(TAG, "replaceRange requested; text is still merged at the caret")
        }

        if (FloatingTextInjector.isRichField(targetNode)) {
            Log.w(TAG, "Rich field: ACTION_SET_TEXT keeps surrounding text. Spans, mention pills, and child chips may be flattened.")
        }

        return writeWithSetText(targetNode, textToInsert, attempts)
    }

    /**
     * Cursor-aware ACTION_SET_TEXT. Retries the merge only when the action returns false
     * and the node text is unchanged. A partial nudge that already changed the field is not
     * merged again, because that would insert the transcript twice.
     * A successful write is not repeated.
     */
    private suspend fun writeWithSetText(
        targetNode: AccessibilityNodeInfo,
        textToInsert: String,
        attempts: Int
    ): NodeWriteResult {
        try {
            targetNode.refresh()
        } catch (e: Throwable) {
            Log.w(TAG, "Error refreshing targetNode before write: ${e.message}")
        }

        val textBefore = try { targetNode.text?.toString() ?: "" } catch (_: Throwable) { "" }
        val initialText = extractGenuineText(targetNode)
        val initialLen = initialText.length

        var attemptsUsed = 1
        var wrote = injectTextSafely(targetNode, textToInsert)
        if (!wrote && attempts > 1) {
            try {
                targetNode.refresh()
            } catch (_: Throwable) {}
            val textAfter = try { targetNode.text?.toString() ?: "" } catch (_: Throwable) { "" }
            if (textAfter == textBefore) {
                Log.w(TAG, "ACTION_SET_TEXT returned false and the field is unchanged. Retrying the merge once.")
                attemptsUsed = 2
                wrote = injectTextSafely(targetNode, textToInsert)
            } else {
                Log.w(TAG, "ACTION_SET_TEXT changed the field before failing. Not merging the transcript again.")
            }
        }
        if (!wrote) {
            return NodeWriteResult(
                outcome = AxWriteOutcome.FAILED,
                details = "injectTextSafely returned false",
                writtenText = textToInsert,
                initialTextLength = initialLen,
                verificationAttemptsUsed = attemptsUsed
            )
        }

        val observed = pollTextLanded(targetNode, initialText, textToInsert)
        if (!observed) {
            Log.w(TAG, "ACTION_SET_TEXT returned true but text was not observed within ${VERIFY_POLL_BUDGET_MS}ms. Not writing again.")
            return NodeWriteResult(
                outcome = AxWriteOutcome.FAILED,
                details = "Verification failed after ACTION_SET_TEXT; write was not repeated",
                writtenText = textToInsert,
                initialTextLength = initialLen,
                verificationAttemptsUsed = attemptsUsed
            )
        }
        val details = if (attemptsUsed > 1) {
            "Verified on retry attempt: text landed"
        } else {
            "Verified on attempt 1: text landed"
        }
        return NodeWriteResult(
            outcome = AxWriteOutcome.SUCCESS,
            details = details,
            writtenText = textToInsert,
            initialTextLength = initialLen,
            finalTextLength = initialLen + textToInsert.trim().length,
            verificationAttemptsUsed = attemptsUsed
        )
    }

    private suspend fun pollTextLanded(
        targetNode: AccessibilityNodeInfo,
        initialText: String,
        insertedText: String
    ): Boolean {
        val deadline = android.os.SystemClock.elapsedRealtime() + VERIFY_POLL_BUDGET_MS
        while (true) {
            try {
                targetNode.refresh()
            } catch (e: Throwable) {
                Log.w(TAG, "Post-write refresh failed: ${e.message}")
            }
            val refreshedRaw = try { targetNode.text?.toString() ?: "" } catch (_: Throwable) { "" }
            val refreshedGenuine = try { extractGenuineText(targetNode) } catch (_: Throwable) { "" }
            if (verifyTextLanded(
                    initialText = initialText,
                    insertedText = insertedText,
                    refreshedRaw = refreshedRaw,
                    refreshedGenuine = refreshedGenuine,
                    targetNode = targetNode
                )
            ) {
                return true
            }
            val now = android.os.SystemClock.elapsedRealtime()
            if (now >= deadline) return false
            val slice = minOf(VERIFY_WAIT_MS, deadline - now)
            delay(slice)
        }
    }

    private fun verifyTextLanded(
        initialText: String,
        insertedText: String,
        refreshedRaw: String,
        refreshedGenuine: String,
        targetNode: AccessibilityNodeInfo? = null
    ): Boolean {
        val trimmedInsert = insertedText.trim()
        if (trimmedInsert.isEmpty()) return true

        // 1. Check if raw text or genuine text contains the inserted text
        if (refreshedRaw.contains(trimmedInsert) || refreshedGenuine.contains(trimmedInsert)) {
            return true
        }

        // 2. Check if length grew reasonably
        if (refreshedGenuine.length >= (initialText.length + trimmedInsert.length - 1) && refreshedGenuine.isNotBlank()) {
            return true
        }

        // 3. For longer transcripts, check head / tail snippet
        if (trimmedInsert.length > 20) {
            val head = trimmedInsert.take(15)
            val tail = trimmedInsert.takeLast(15)
            if (refreshedRaw.contains(head) || refreshedGenuine.contains(head) ||
                refreshedRaw.contains(tail) || refreshedGenuine.contains(tail)) {
                return true
            }
        }

        // 4. In rich text nodes or containers with child nodes, inspect children
        if (targetNode != null && targetNode.childCount > 0) {
            if (searchNodeText(targetNode, trimmedInsert)) {
                return true
            }
        }

        return false
    }

    private fun searchNodeText(node: AccessibilityNodeInfo, target: String): Boolean {
        if (node.text?.toString()?.contains(target) == true) return true
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = searchNodeText(child, target)
            try { child.recycle() } catch (_: Throwable) {}
            if (found) return true
        }
        return false
    }

    /**
     * Whisperflow's core commit method extracted from smali:
     * - Falls back to findFocus(FOCUS_INPUT) if accessibilityNodeInfo is null.
     * - Dispatches writeRangeAndVerify with VERIFICATION_ATTEMPTS.
     * - Recycles the internally retrieved node safely to prevent binder exhaustion.
     */
    suspend fun commitViaActionSetText(
        str: String,
        accessibilityNodeInfo: AccessibilityNodeInfo? = null,
        verificationAttempts: Int = VERIFICATION_ATTEMPTS
    ): NodeWriteResult {
        val needsRecycle = accessibilityNodeInfo == null
        val targetNode = accessibilityNodeInfo ?: try {
            findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: run {
                Log.d(TAG, "commitViaActionSetText: no focused input node")
                return NodeWriteResult(AxWriteOutcome.FAILED, details = "No focused input node")
            }
        } catch (e: Exception) {
            Log.w(TAG, "commitViaActionSetText: findFocus threw: ${e.message}")
            return NodeWriteResult(AxWriteOutcome.FAILED, details = "findFocus threw: ${e.message}")
        }

        try {
            if (!isEditableNode(targetNode)) {
                Log.d(TAG, "commitViaActionSetText: target node is not editable")
                return NodeWriteResult(AxWriteOutcome.FAILED, details = "Node not editable")
            }

            return writeRangeAndVerify(
                targetNode = targetNode,
                textToInsert = str,
                replaceRange = false,
                attempts = verificationAttempts
            )
        } finally {
            if (needsRecycle) {
                try {
                    targetNode.recycle()
                } catch (_: Throwable) {}
            }
        }
    }

    /**
     * P1 accessibility input connection, then P2 companion IME, then P3 caret-aware node write.
     * See [injectTextDetailed].
     */
    suspend fun injectText(newText: String): Boolean {
        val trace = injectTextDetailed(newText)
        return trace.finalOutcome == "SUCCESS"
    }

    @androidx.annotation.VisibleForTesting(otherwise = androidx.annotation.VisibleForTesting.PRIVATE)
    internal fun commitTextViaInputMethod(text: String): Boolean {
        return VoxStreamInputMethodService.commitText(text)
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
                    ?: FloatingBubbleManager.currentForegroundPackage.value
                    ?: FloatingBubbleManager.lockedSessionContext.value
                // Pass A: Look for focused editable in application windows belonging to active package
                for (w in currentWindows) {
                    if (w.type == AccessibilityWindowInfo.TYPE_APPLICATION) {
                        val wRoot = w.root ?: continue
                        val pkg = wRoot.packageName?.toString()
                        if (activePkg != null && pkg != activePkg && !AppContextResolver.isIgnoredPackage(this, pkg)) {
                            // Only skip if activePkg was specifically established and doesn't match
                            if (w.isFocused || w.isActive) {
                                // Active or focused window should still be checked
                            } else {
                                continue
                            }
                        }
                        val inputF = wRoot.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                        if (inputF != null && isEditableNode(inputF)) {
                            Log.d(TAG, "Found target editable in APP window via FOCUS_INPUT")
                            lastFocusedEditableNode = inputF
                            return inputF
                        }
                        val focusedInTree = findFocusedEditableInTree(wRoot)
                        if (focusedInTree != null) {
                            Log.d(TAG, "Found target editable in APP window tree scan (focused)")
                            lastFocusedEditableNode = focusedInTree
                            return focusedInTree
                        }
                    }
                }

                // Pass B: If floating overlay held focus, look for any active visible editable node in APP windows
                for (w in currentWindows) {
                    if (w.type == AccessibilityWindowInfo.TYPE_APPLICATION) {
                        val wRoot = w.root ?: continue
                        val pkg = wRoot.packageName?.toString()
                        if (AppContextResolver.isIgnoredPackage(this, pkg)) continue
                        if (activePkg != null && pkg != activePkg && !w.isFocused && !w.isActive) continue
                        val anyEditable = findBestEditableInTree(wRoot)
                        if (anyEditable != null) {
                            Log.d(TAG, "Found target editable in APP window tree scan (active editable fallback)")
                            lastFocusedEditableNode = anyEditable
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
        if (isEditableNode(node) && (node.isVisibleToUser || node.isFocused || node.isFocusable)) {
            val viewId = try { node.viewIdResourceName?.lowercase() ?: "" } catch (_: Throwable) { "" }
            if (viewId.contains("edit_note_text") || viewId.contains("note_text") || viewId.contains("content") || node.isFocused) {
                return node
            }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findBestEditableInTree(child)
            if (result != null) return result
        }
        if (isEditableNode(node) && (node.isVisibleToUser || node.isFocused || node.isFocusable)) {
            return node
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
