package com.princeniithompson.rapcha.service

import android.os.SystemClock

/**
 * Pure settling state machine for keyboard/bubble show-hide transitions.
 *
 * - Show condition = IME window present OR focused editable confirmed recently (within [fallbackEditableExpiryMs]).
 * - Never hides on a single transient negative evaluation: requires at least 2 consecutive
 *   negative evaluations AND >= [hideSettleMs] elapsed since the first negative evaluation.
 */
class KeyboardSettleStateMachine(
    val hideSettleMs: Long = KEYBOARD_HIDE_SETTLE_MS,
    val secondWatchDelayMs: Long = KEYBOARD_SECOND_CHECK_MS,
    val fallbackEditableExpiryMs: Long = FALLBACK_EDITABLE_EXPIRY_MS
) {
    private var currentVisible = false
    private var consecutiveNegatives = 0
    private var firstNegativeElapsedMs = 0L

    fun isShowConditionMet(
        imeWindowPresent: Boolean,
        focusedEditableActive: Boolean,
        lastTypingOrFocusElapsedMs: Long,
        nowMs: Long
    ): Boolean {
        if (imeWindowPresent) return true
        if (!focusedEditableActive || lastTypingOrFocusElapsedMs <= 0L) return false
        return (nowMs - lastTypingOrFocusElapsedMs) <= fallbackEditableExpiryMs
    }

    fun evaluate(
        imeWindowPresent: Boolean,
        focusedEditableActive: Boolean,
        lastTypingOrFocusElapsedMs: Long,
        sessionProtected: Boolean,
        nowMs: Long
    ): Boolean {
        val positive = isShowConditionMet(
            imeWindowPresent = imeWindowPresent,
            focusedEditableActive = focusedEditableActive,
            lastTypingOrFocusElapsedMs = lastTypingOrFocusElapsedMs,
            nowMs = nowMs
        )
        if (positive) {
            currentVisible = true
            consecutiveNegatives = 0
            firstNegativeElapsedMs = 0L
            return true
        }
        if (sessionProtected && currentVisible) {
            consecutiveNegatives = 0
            firstNegativeElapsedMs = 0L
            return true
        }
        if (!currentVisible) {
            consecutiveNegatives = 0
            firstNegativeElapsedMs = 0L
            return false
        }
        if (consecutiveNegatives == 0) {
            consecutiveNegatives = 1
            firstNegativeElapsedMs = nowMs
            return true
        }
        consecutiveNegatives++
        if (consecutiveNegatives >= 2 && (nowMs - firstNegativeElapsedMs) >= hideSettleMs) {
            currentVisible = false
            consecutiveNegatives = 0
            firstNegativeElapsedMs = 0L
        }
        return currentVisible
    }

    fun reset(visible: Boolean = false) {
        currentVisible = visible
        consecutiveNegatives = 0
        firstNegativeElapsedMs = 0L
    }

    companion object {
        const val KEYBOARD_WATCH_MS = 450L
        const val KEYBOARD_HIDE_SETTLE_MS = 450L
        const val KEYBOARD_SECOND_CHECK_MS = 900L
        const val FALLBACK_EDITABLE_EXPIRY_MS = 1_500L
    }
}

/**
 * Pure decision function for the 5-second overlay visibility watchdog.
 * Pauses when the screen is off and waits [GRACE_TICKS] consecutive ticks before triggering recovery.
 */
object VisibilityWatchdogDecision {
    const val WATCHDOG_INTERVAL_MS = 5_000L
    const val GRACE_TICKS = 2

    enum class TickAction {
        OK,
        VIOLATION_WAIT,
        RECOVER
    }

    data class WatchdogState(val consecutiveViolations: Int = 0)

    fun onTick(
        state: WatchdogState,
        screenOn: Boolean,
        bubbleEnabled: Boolean,
        componentsInitialized: Boolean,
        hasOverlayPermission: Boolean,
        shouldBeVisible: Boolean,
        sessionActive: Boolean,
        isAttached: Boolean,
        windowVisibilityVisible: Boolean,
        keyboardVisible: Boolean
    ): Pair<WatchdogState, TickAction> {
        if (!screenOn || !bubbleEnabled || !componentsInitialized || !hasOverlayPermission) {
            return WatchdogState(0) to TickAction.OK
        }
        val missingAttachment = (shouldBeVisible || sessionActive) && !isAttached
        val hiddenWhileNeeded = shouldBeVisible && isAttached && !windowVisibilityVisible && keyboardVisible
        if (!missingAttachment && !hiddenWhileNeeded) {
            return WatchdogState(0) to TickAction.OK
        }
        val next = state.consecutiveViolations + 1
        return if (next >= GRACE_TICKS) {
            WatchdogState(0) to TickAction.RECOVER
        } else {
            WatchdogState(next) to TickAction.VIOLATION_WAIT
        }
    }
}

/**
 * Pure foreground-service type planner across Android API levels (Wispr evaluateFgsStartupPlan ladder).
 */
object FgsStartupDecision {
    enum class FgsPlan {
        LEGACY_NO_TYPE,
        TYPE_ZERO,
        SPECIAL_USE_ONLY,
        MICROPHONE_ONLY,
        SPECIAL_USE_AND_MICROPHONE
    }

    fun evaluatePlan(
        sdkInt: Int,
        isRecordingStarting: Boolean,
        hasRecordAudioPermission: Boolean,
        allowMicrophoneInCurrentState: Boolean
    ): FgsPlan {
        if (sdkInt < 29) return FgsPlan.LEGACY_NO_TYPE
        if (sdkInt < 34) {
            return if (isRecordingStarting && hasRecordAudioPermission && allowMicrophoneInCurrentState) {
                FgsPlan.MICROPHONE_ONLY
            } else {
                FgsPlan.TYPE_ZERO
            }
        }
        return if (isRecordingStarting && hasRecordAudioPermission && allowMicrophoneInCurrentState) {
            FgsPlan.SPECIAL_USE_AND_MICROPHONE
        } else {
            FgsPlan.SPECIAL_USE_ONLY
        }
    }

    /** v23: how FloatingBubbleManager.startBubbleService should start the bubble service. */
    enum class StartRoute {
        /** startForegroundService (foreground-allowed context, or no accessibility binding). */
        START_FOREGROUND,
        /** Accessibility service already bound the bubble; skip the refused background FGS start. */
        BIND_ONLY
    }

    /** Sources Android lets start a foreground service from the background (boot, update, user UI). */
    fun isExemptSource(source: String): Boolean {
        val s = source.lowercase()
        return s == "boot" || s == "update" || s == "locked_boot" ||
            s.startsWith("user_") || s.startsWith("keep_awake") || s.startsWith("notification")
    }

    fun startRoute(
        sdkInt: Int,
        boundByAccessibility: Boolean,
        uiForeground: Boolean,
        exemptSource: Boolean
    ): StartRoute {
        return when {
            !boundByAccessibility -> StartRoute.START_FOREGROUND
            sdkInt < 31 -> StartRoute.START_FOREGROUND
            uiForeground || exemptSource -> StartRoute.START_FOREGROUND
            else -> StartRoute.BIND_ONLY
        }
    }

    fun fallbackPlan(sdkInt: Int, failedPlan: FgsPlan): FgsPlan? {
        return when (failedPlan) {
            FgsPlan.SPECIAL_USE_AND_MICROPHONE -> FgsPlan.SPECIAL_USE_ONLY
            FgsPlan.MICROPHONE_ONLY -> if (sdkInt >= 29) FgsPlan.TYPE_ZERO else FgsPlan.LEGACY_NO_TYPE
            else -> null
        }
    }
}

/**
 * Prevents restart storms by enforcing a minimum interval between service respawn attempts.
 */
class RespawnThrottle(
    private val minIntervalMs: Long = RESPAWN_MIN_INTERVAL_MS,
    private val nowMs: () -> Long = { SystemClock.elapsedRealtime() }
) {
    private var lastAttemptMs = -1L

    @Synchronized
    fun tryAcquire(): Boolean {
        val now = nowMs()
        if (lastAttemptMs >= 0L && (now - lastAttemptMs) < minIntervalMs) {
            return false
        }
        lastAttemptMs = now
        return true
    }

    @Synchronized
    fun reset() {
        lastAttemptMs = -1L
    }

    companion object {
        const val RESPAWN_MIN_INTERVAL_MS = 5_000L
    }
}

/**
 * Controls persisted JobScheduler self-healing bounds.
 */
object RecoveryJobPolicy {
    const val JOB_ID = 2147440624
    const val MIN_LATENCY_MS = 30_000L
    const val DEFAULT_DELAY_MS = 60_000L
    const val DEADLINE_WINDOW_MS = 15 * 60_000L
    const val REPEAT_LATENCY_MS = 15 * 60_000L
    const val MAX_CONSECUTIVE_SUCCESSES = 3

    fun clampLatencyMs(requestedDelayMs: Long): Long = requestedDelayMs.coerceAtLeast(MIN_LATENCY_MS)

    fun deadlineMs(clampedLatencyMs: Long): Long = clampedLatencyMs + DEADLINE_WINDOW_MS

    fun shouldReschedule(
        bubbleEnabled: Boolean,
        hasOverlayPermission: Boolean,
        @Suppress("UNUSED_PARAMETER") consecutiveSuccesses: Int
    ): Boolean {
        return bubbleEnabled && hasOverlayPermission
    }
}

/**
 * Keeps the bubble service and active dictation alive when MainActivity is swiped from Recents.
 */
object TaskRemovalPolicy {
    fun shouldReleaseSessionOnTaskRemoved(bubbleEnabled: Boolean): Boolean = !bubbleEnabled

    fun shouldRebuildOverlayOnTaskRemoved(
        bubbleEnabled: Boolean,
        hasOverlayPermission: Boolean
    ): Boolean = bubbleEnabled && hasOverlayPermission
}

/**
 * Distinguishes real text input fields from generic layout/container nodes whose IDs contain words like "content" or "search".
 */
object EditableNodePolicy {
    private val EDIT_CLASS_TOKENS = listOf(
        "EditText",
        "AutoCompleteTextView",
        "NoteEditText",
        "TextInputEditText",
        "ExtractEditText",
        "ComposeEditableText"
    )
    private val CONTAINER_CLASS_TOKENS = listOf(
        "Layout",
        "RecyclerView",
        "ListView",
        "ScrollView",
        "ViewGroup",
        "WebView"
    )
    private val SPECIFIC_NOTE_IDS = listOf(
        "edit_note_text",
        "editor_title",
        "note_text"
    )

    fun isEditableCandidate(
        isEditable: Boolean,
        className: String?,
        viewIdResourceName: String?,
        supportsActionSetText: Boolean
    ): Boolean {
        if (isEditable) return true
        val cls = className.orEmpty()
        if (EDIT_CLASS_TOKENS.any { cls.contains(it, ignoreCase = true) }) {
            return true
        }
        if (supportsActionSetText) return true
        val viewId = viewIdResourceName?.lowercase().orEmpty()
        val isContainer = CONTAINER_CLASS_TOKENS.any { cls.contains(it, ignoreCase = true) }
        return !isContainer && SPECIFIC_NOTE_IDS.any { viewId.contains(it) }
    }
}

/**
 * Pure visibility guards to prevent lockscreen flashes, launcher leaks, and 2-second hide lag when the IME closes.
 */
object KeyboardVisibilityGuard {
    fun shouldSuppressForDeviceState(
        screenInteractive: Boolean,
        deviceLocked: Boolean
    ): Boolean = !screenInteractive || deviceLocked

    fun shouldClearFallbackFocusOnTransition(
        wasImeVisible: Boolean,
        isImeVisible: Boolean,
        focusEventJustConfirmed: Boolean,
        activeWindowIsLauncherOrIgnored: Boolean,
        focusNodePackage: String?,
        activePackage: String?
    ): Boolean {
        if (isImeVisible || focusEventJustConfirmed) return false
        if (wasImeVisible) return true
        if (activeWindowIsLauncherOrIgnored) return true
        if (!focusNodePackage.isNullOrBlank() &&
            !activePackage.isNullOrBlank() &&
            !focusNodePackage.equals(activePackage, ignoreCase = true)
        ) {
            return true
        }
        return false
    }
}
