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
import com.example.service.floating.FloatingTextInjector

open class VoxStreamAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "VoxStreamAccessService"
        const val VERIFICATION_ATTEMPTS = 2
        const val VERIFY_WAIT_MS = 200L // 150-250 ms window
        var instance: VoxStreamAccessibilityService? = null
            internal set
    }

    private var lastFocusedEditableNode: AccessibilityNodeInfo? = null
    private val mainHandler = Handler(Looper.getMainLooper())

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
        val flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS

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
     * Injects transcribed text directly into the target active editable field using direct node editing (ACTION_SET_TEXT):
     * 1. Resolves active focused editable node (via FOCUS_INPUT, rootInActiveWindow, or window search).
     * 2. Executes direct node injection via injectTextSafely (ACTION_SET_TEXT) with cursor & hint handling.
     * 3. Zero clipboard interference when a text field is present.
     */
    open fun injectTextDetailed(newText: String): com.example.data.InjectionEvent {
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

        val targetNode = getActiveEditableNode()
        val (finalOutcome, injectionMethod, resultDetails) = if (targetNode != null) {
            // Requirement 4: Refresh the focused node before writing
            try {
                targetNode.refresh()
            } catch (e: Throwable) {
                Log.w(TAG, "Pre-write node refresh threw: ${e.message}")
            }

            // Requirement 4: Check that the node supports ACTION_SET_TEXT (0x200000)
            val supportsActionSetText = FloatingTextInjector.supportsActionSetText(targetNode)
            if (!supportsActionSetText) {
                Log.w(TAG, "Focused node exists but does NOT support ACTION_SET_TEXT (actions=0x${Integer.toHexString(targetNode.actions)}, class=${targetNode.className})")
                // Requirement 3: Never use clipboard if a focused editable field exists (even if write fails)
                Triple("FAILED", "DIRECT_ACTION_SET_TEXT", "FAILED: Focused node does not support ACTION_SET_TEXT (actions=0x${Integer.toHexString(targetNode.actions)})")
            } else {
                try {
                    if (!targetNode.isFocused) {
                        targetNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                    }
                } catch (_: Throwable) {}

                // Requirement 1 & 5: Pure ACTION_SET_TEXT with 150-250ms verification and single retry
                val writeResult = writeRangeAndVerify(targetNode, newText, replaceRange = false, attempts = 2)
                if (writeResult.outcome == AxWriteOutcome.SUCCESS) {
                    Triple("SUCCESS", "DIRECT_ACTION_SET_TEXT", "SUCCESS: Pure ACTION_SET_TEXT verified (${writeResult.details})")
                } else {
                    // Requirement 3: Never use clipboard if a focused editable field exists (even if write fails)
                    Log.w(TAG, "Pure ACTION_SET_TEXT write/verification failed (${writeResult.details}). Clipboard fallback strictly suppressed.")
                    Triple("FAILED", "DIRECT_ACTION_SET_TEXT", "FAILED: ACTION_SET_TEXT failed or verification failed (${writeResult.details})")
                }
            }
        } else {
            // Requirement 2: Only fall back to clipboard when there is NO focused editable text field at all
            Log.d(TAG, "No focused editable text field found on screen")
            Triple("FAILED", "NO_TARGET_NODE", "FAILED: No active editable node found")
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
     * Directly pastes text into the targeted node via ACTION_PASTE, temporarily placing
     * the transcribed text on the clipboard and immediately restoring previous user clipboard
     * contents. This provides 100% reliable direct text injection into apps like Google Keep,
     * Samsung Notes, and rich-text editors that reject ACTION_SET_TEXT.
     */
    fun performDirectPasteIntoNode(node: AccessibilityNodeInfo, text: String): Boolean {
        return try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return false
            val previousClip = clipboard.primaryClip

            // Temporarily set the dictation text on clipboard
            val tempClip = ClipData.newPlainText("VoxStream Direct Insertion", text)
            clipboard.setPrimaryClip(tempClip)

            // Ensure the target node is focused
            try {
                node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            } catch (_: Throwable) {}

            // Trigger ACTION_PASTE on the target editable node
            val pasted = node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            Log.d(TAG, "performDirectPasteIntoNode ACTION_PASTE result: $pasted")

            // Restore previous user clipboard after target editor has consumed the paste event
            mainHandler.postDelayed({
                try {
                    if (previousClip != null) {
                        clipboard.setPrimaryClip(previousClip)
                    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        clipboard.clearPrimaryClip()
                    }
                    Log.d(TAG, "Restored previous clipboard state after ACTION_PASTE")
                } catch (e: Exception) {
                    Log.w(TAG, "Notice restoring previous clip: ${e.message}")
                }
            }, 300L)

            pasted
        } catch (e: Exception) {
            Log.w(TAG, "performDirectPasteIntoNode failed: ${e.message}", e)
            false
        }
    }

    /**
     * Checks if there is an active focused editable node on screen.
     */
    fun hasActiveEditableNode(): Boolean {
        return getActiveEditableNode() != null
    }

    /**
     * Verifies that the target node accepted the written text by:
     * 1. Refreshing the node before writing and verifying ACTION_SET_TEXT support.
     * 2. Executing pure ACTION_SET_TEXT with note app placeholder safety.
     * 3. Waiting ~150–250 ms (VERIFY_WAIT_MS = 200L).
     * 4. Refreshing the node again and verifying that the text actually landed.
     * 5. If verification fails, retrying the write + verify once.
     */
    fun writeRangeAndVerify(
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

        val pkgName = try { targetNode.packageName?.toString()?.lowercase() ?: "" } catch (_: Throwable) { "" }
        val isNoteApp = FloatingTextInjector.isNoteAppPackage(pkgName)

        // Requirement 4: Refresh the focused node before writing
        try {
            targetNode.refresh()
        } catch (e: Throwable) {
            Log.w(TAG, "Error refreshing targetNode before write: ${e.message}")
        }

        // Requirement 4: Check that the node supports ACTION_SET_TEXT (0x200000)
        val supportsActionSetText = FloatingTextInjector.supportsActionSetText(targetNode)
        if (!supportsActionSetText) {
            Log.w(TAG, "writeRangeAndVerify: Target node does NOT support ACTION_SET_TEXT: actions=0x${Integer.toHexString(targetNode.actions)}, class=${targetNode.className}")
            return NodeWriteResult(
                outcome = AxWriteOutcome.FAILED,
                details = "Node does not support ACTION_SET_TEXT (actions=0x${Integer.toHexString(targetNode.actions)})",
                writtenText = textToInsert
            )
        }

        val initialText = extractGenuineText(targetNode)
        val initialRaw = targetNode.text?.toString() ?: ""
        val initialLen = initialText.length

        Log.d(TAG, "Pre-write check: supportsActionSetText=true, initialTextLen=$initialLen, rawLen=${initialRaw.length}, isNoteApp=$isNoteApp, pkg=$pkgName")

        fun executeWriteAndVerify(attemptNumber: Int): Pair<Boolean, String> {
            Log.d(TAG, "Executing pure ACTION_SET_TEXT attempt #$attemptNumber (isNoteApp=$isNoteApp)...")

            // Ensure focus is on node
            try {
                if (!targetNode.isFocused) {
                    targetNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                }
            } catch (_: Throwable) {}

            val directSuccess = injectTextSafely(targetNode, textToInsert)
            if (!directSuccess) {
                val args = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, textToInsert.trim())
                }
                val rawSuccess = try {
                    targetNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                } catch (e: Throwable) {
                    Log.w(TAG, "Raw performAction(ACTION_SET_TEXT) failed on attempt #$attemptNumber: ${e.message}")
                    false
                }
                if (!rawSuccess) {
                    return Pair(false, "performAction(ACTION_SET_TEXT) returned false")
                }
            }

            // Requirement 5: Wait ~150–250 ms after writing
            try {
                Thread.sleep(VERIFY_WAIT_MS)
            } catch (_: InterruptedException) {}

            // Requirement 5: Refresh the node again and verify the text actually landed
            val refreshed = try {
                targetNode.refresh()
            } catch (e: Throwable) {
                Log.w(TAG, "Post-write refresh failed on attempt #$attemptNumber: ${e.message}")
                false
            }

            val refreshedRaw = targetNode.text?.toString() ?: ""
            val refreshedGenuine = extractGenuineText(targetNode)

            val landed = verifyTextLanded(
                initialText = initialText,
                insertedText = textToInsert,
                refreshedRaw = refreshedRaw,
                refreshedGenuine = refreshedGenuine
            )

            Log.d(TAG, "Post-write attempt #$attemptNumber: refreshed=$refreshed, landed=$landed, rawLen=${refreshedRaw.length}, genuineLen=${refreshedGenuine.length}, rawPreview='${refreshedRaw.take(30)}'")
            return Pair(landed, "rawLen=${refreshedRaw.length}, genuineLen=${refreshedGenuine.length}")
        }

        // Attempt 1
        val (attempt1Success, details1) = executeWriteAndVerify(1)
        if (attempt1Success) {
            Log.d(TAG, "Pure ACTION_SET_TEXT successfully verified on attempt 1")
            return NodeWriteResult(
                outcome = AxWriteOutcome.SUCCESS,
                details = "Verified on attempt 1: $details1",
                writtenText = textToInsert,
                initialTextLength = initialLen,
                finalTextLength = initialLen + textToInsert.length,
                verificationAttemptsUsed = 1
            )
        }

        // Requirement 5: If verification fails, retry the write + verify once
        Log.w(TAG, "Attempt 1 verification failed ($details1). Retrying write + verify once per requirement...")
        try {
            targetNode.refresh()
            if (!targetNode.isFocused) {
                targetNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            }
        } catch (_: Throwable) {}

        val (retrySuccess, retryDetails) = executeWriteAndVerify(2)
        if (retrySuccess) {
            Log.d(TAG, "Pure ACTION_SET_TEXT successfully verified on retry attempt (attempt 2)")
            return NodeWriteResult(
                outcome = AxWriteOutcome.SUCCESS,
                details = "Verified on retry attempt: $retryDetails",
                writtenText = textToInsert,
                initialTextLength = initialLen,
                finalTextLength = initialLen + textToInsert.length,
                verificationAttemptsUsed = 2
            )
        }

        Log.w(TAG, "Pure ACTION_SET_TEXT verification failed after retry: text did not land ($retryDetails)")
        return NodeWriteResult(
            outcome = AxWriteOutcome.FAILED,
            details = "Verification failed after retry: text did not land ($retryDetails)",
            writtenText = textToInsert,
            initialTextLength = initialLen,
            verificationAttemptsUsed = 2
        )
    }

    private fun verifyTextLanded(
        initialText: String,
        insertedText: String,
        refreshedRaw: String,
        refreshedGenuine: String
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

    fun injectText(newText: String): Boolean {
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
