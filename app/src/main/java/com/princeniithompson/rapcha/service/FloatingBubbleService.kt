package com.princeniithompson.rapcha.service

import android.Manifest
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.princeniithompson.rapcha.MainActivity
import com.princeniithompson.rapcha.core.ApiConfig
import com.princeniithompson.rapcha.data.AppLogRepository
import com.princeniithompson.rapcha.data.ConnectionState
import com.princeniithompson.rapcha.data.CustomVocabularyRepository
import com.princeniithompson.rapcha.data.DiagnosticSource
import com.princeniithompson.rapcha.data.DiagnosticType
import com.princeniithompson.rapcha.data.DictationEndedReason
import com.princeniithompson.rapcha.data.LyraReviewPolicy
import com.princeniithompson.rapcha.data.HistoryRepository
import com.princeniithompson.rapcha.data.VoiceSessionBuilder
import com.princeniithompson.rapcha.data.RetryClipStore
import com.princeniithompson.rapcha.service.floating.DictationAlerts
import com.princeniithompson.rapcha.service.floating.FloatingDictationSessionManager
import com.princeniithompson.rapcha.service.floating.OfflineRetryCoordinator
import com.princeniithompson.rapcha.service.floating.FloatingHapticManager
import com.princeniithompson.rapcha.core.LiteMode
import com.princeniithompson.rapcha.service.floating.FloatingHapticType
import com.princeniithompson.rapcha.service.floating.FloatingNotificationManager
import com.princeniithompson.rapcha.service.floating.FloatingOverlayWindowManager
import com.princeniithompson.rapcha.service.floating.FloatingPolishCoordinator
import com.princeniithompson.rapcha.service.floating.LyraReviewPrompt
import com.princeniithompson.rapcha.service.floating.LivePolishOutcome
import com.princeniithompson.rapcha.service.floating.LiveStreamPaste
import com.princeniithompson.rapcha.service.floating.LivePolishProtocol
import com.princeniithompson.rapcha.service.floating.LiveTextPolishClient
import com.princeniithompson.rapcha.service.floating.HybridPolishPolicy
import com.princeniithompson.rapcha.service.floating.LockedPolishSection
import com.princeniithompson.rapcha.service.floating.SectionPolishMode
import com.princeniithompson.rapcha.service.floating.Stage2LivePolish
import com.princeniithompson.rapcha.service.floating.Stage2LivePrompt
import com.princeniithompson.rapcha.service.floating.Stage2Outcome
import com.princeniithompson.rapcha.service.floating.Stage2PolishSettings
import com.princeniithompson.rapcha.service.floating.DeliveryHalt
import com.princeniithompson.rapcha.service.floating.DeliverySession
import com.princeniithompson.rapcha.service.floating.PolishDelivery
import com.princeniithompson.rapcha.service.floating.Stage1Capture
import com.princeniithompson.rapcha.service.floating.Stage2WarmLine
import com.princeniithompson.rapcha.service.floating.ThreeStepPolicy
import com.princeniithompson.rapcha.service.floating.ThreeStepPolishSettings
import com.princeniithompson.rapcha.service.floating.FloatingTextInjector
import com.princeniithompson.rapcha.service.floating.RapchaTextInserter
import com.princeniithompson.rapcha.service.floating.OfflineRetryRules
import com.princeniithompson.rapcha.service.floating.RetryNetworkWatcher
import com.princeniithompson.rapcha.service.floating.VocabSuggestions
import com.princeniithompson.rapcha.websocket.GeminiLiveWebSocketClient
import com.princeniithompson.rapcha.websocket.InterimPreviewMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Thin coordinator service for the floating voice bubble overlay.
 * Coordinates between overlay window management, dictation session recording,
 * AI polishing, and text injection.
 */
class FloatingBubbleService : Service() {

    enum class RingTap { HIDE, CONTINUE }

    companion object {
        private const val TAG = "FloatingBubbleService"
        const val ACTION_RETRY_CLIP = "com.princeniithompson.rapcha.action.RETRY_CLIP"
        const val ACTION_SHOW = "com.princeniithompson.rapcha.action.SHOW_BUBBLE"
        const val ACTION_RESTORE = "com.princeniithompson.rapcha.action.RESTORE_BUBBLE"
        const val ACTION_REPAIR_A11Y = "com.princeniithompson.rapcha.action.REPAIR_A11Y"
        const val EXTRA_CLIP_ID = "retry_clip_id"
        const val EXTRA_FROM_HISTORY = "from_history"
        const val EXTRA_START_SOURCE = "start_source"

        var instance: FloatingBubbleService? = null
            private set

        @Suppress("UNUSED_PARAMETER")
        internal fun decideRingTap(
            recording: Boolean,
            keyboardVisible: Boolean,
            fieldFocused: Boolean
        ): RingTap {
            // A blinking cursor with no keyboard is the same as nothing focused: hide, don't start.
            return if (!recording && !keyboardVisible) RingTap.HIDE else RingTap.CONTINUE
        }

        /** Field focus alone does not start a session. The keyboard has to be on screen. */
        internal fun startsRecordingOnTap(
            recording: Boolean,
            keyboardVisible: Boolean,
            fieldFocused: Boolean
        ): Boolean {
            return !recording && keyboardVisible &&
                decideRingTap(recording, keyboardVisible, fieldFocused) == RingTap.CONTINUE
        }

        /** The field-focus path may reveal the bubble only when the IME window is already up. */
        internal fun fieldFocusRevealsBubble(keyboardVisible: Boolean): Boolean = keyboardVisible

        /**
         * The short keyboard re-check hides the bubble whenever the IME window is gone,
         * including when an editable field is still focused. An active session stays up.
         */
        internal fun keyboardRecheckShouldHide(imeVisible: Boolean, sessionActive: Boolean): Boolean {
            return !imeVisible && !sessionActive
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var overlayWindowManager: FloatingOverlayWindowManager
    private lateinit var sessionManager: FloatingDictationSessionManager
    val polishCoordinator = FloatingPolishCoordinator()
    private val offlineRetry = OfflineRetryCoordinator()
    private val lyraFinishGeneration = AtomicInteger(0)
    private var liveSessionId: String? = null

    @androidx.annotation.VisibleForTesting(otherwise = androidx.annotation.VisibleForTesting.PRIVATE)
    internal var lastPolishClickTime: Long
        get() = polishCoordinator.lastPolishClickTime
        set(value) { polishCoordinator.lastPolishClickTime = value }

    @androidx.annotation.VisibleForTesting(otherwise = androidx.annotation.VisibleForTesting.PRIVATE)
    internal var polishDebounceJob: Job?
        get() = polishCoordinator.polishDebounceJob
        set(value) { polishCoordinator.polishDebounceJob = value }

    @Volatile
    private var isPendingInjectionOnSmartCompletion = false

    @Volatile
    private var activeDelivery: DeliverySession? = null

    /** Foreground app captured when the session starts, before the bubble can cover it. */
    @Volatile
    private var completeTargetPackage: String? = null
    @Volatile
    private var sessionLimitAutoFinish = false
    private var pendingCompletionTimeoutJob: Job? = null
    private var activeDetectionJob: Job? = null
    private var fgsDowngradeJob: Job? = null
    private var connectionLostHapticSent = false
    private var networkWatcher: RetryNetworkWatcher? = null
    private var networkBackNotifiedFor: String? = null
    private var watchdogJob: Job? = null
    private var detachedRecoverTicks = 0
    private var watchdogState = VisibilityWatchdogDecision.WatchdogState()
    private var screenInteractive = true
    private var screenReceiverRegistered = false

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> screenInteractive = false
                Intent.ACTION_SCREEN_ON -> screenInteractive = true
            }
        }
    }

    /**
     * v23: RapchaAccessibilityService binds here (BIND_AUTO_CREATE). A bound service created by
     * our running accessibility service is not a background foreground-service start, so Android
     * 12+ cannot refuse it, and the bubble lives as long as the accessibility binding.
     */
    inner class LocalBinder : android.os.Binder() {
        val service: FloatingBubbleService get() = this@FloatingBubbleService
    }

    private val localBinder = LocalBinder()

    /** True once startForeground succeeded at least once for this service instance. */
    @Volatile
    private var promotedToForeground = false

    override fun onBind(intent: Intent?): IBinder = localBinder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!FloatingBubbleManager.isBubbleEnabledSync(this) ||
            !FloatingBubbleManager.canDrawOverlays(this)
        ) {
            val reason = if (!FloatingBubbleManager.isBubbleEnabledSync(this)) {
                com.princeniithompson.rapcha.diagnostics.BubbleHealthLog.HIDE_USER_HIDDEN
            } else {
                com.princeniithompson.rapcha.diagnostics.BubbleHealthLog.HIDE_OVERLAY_PERMISSION_MISSING
            }
            com.princeniithompson.rapcha.diagnostics.BubbleHealthLog.recordLastStopReason(
                this,
                com.princeniithompson.rapcha.diagnostics.BubbleHealthLog.STOP_INTENTIONAL
            )
            com.princeniithompson.rapcha.diagnostics.BubbleHealthLog.recordHideReason(this, reason)
            // v23: a bound service survives stopSelf(); drop the accessibility binding too.
            RapchaAccessibilityService.instance?.unbindBubbleService("disabled_or_no_overlay")
            stopSelf()
            return START_NOT_STICKY
        }

        val currentlyRecording = ::sessionManager.isInitialized && sessionManager.isRecording
        if (currentlyRecording) {
            startMicrophoneForeground()
        } else if (!enterSpecialUseForeground()) {
            // v23: Android 12+ refused the foreground promotion (background start). The overlay
            // window does not need a foreground service while we hold SYSTEM_ALERT_WINDOW, so keep
            // running unpromoted and stay START_STICKY instead of stopping ourselves. The recovery
            // job retries the promotion later; the user can also tap the restore notification.
            BubbleRecoveryJobScheduler.schedule(this, RecoveryJobPolicy.DEFAULT_DELAY_MS)
            if (!RapchaAccessibilityService.isBubbleBound()) {
                FloatingNotificationManager.postRestoreNotification(this)
            }
            com.princeniithompson.rapcha.diagnostics.BubbleHealthLog.record(
                context = this,
                type = DiagnosticType.FGS_FALLBACK,
                message = "site=start_command action=continue_unpromoted bound=${RapchaAccessibilityService.isBubbleBound()}"
            )
        }
        BubbleRecoveryJobScheduler.scheduleIfMissing(this, RecoveryJobPolicy.REPEAT_LATENCY_MS)

        val startSource = intent?.getStringExtra(EXTRA_START_SOURCE)
        if (intent == null) {
            com.princeniithompson.rapcha.diagnostics.BubbleHealthLog.recordLastRestartReason(
                this,
                "System START_STICKY restart"
            )
            rebuildOverlayIfNeeded("sticky_restart")
        } else {
            if (!startSource.isNullOrBlank()) {
                com.princeniithompson.rapcha.diagnostics.BubbleHealthLog.recordLastRestartReason(
                    this,
                    "Started ($startSource)"
                )
            }
            when (intent.action) {
                ACTION_SHOW -> {
                    FloatingNotificationManager.cancelRestoreNotification(this)
                    rebuildOverlayIfNeeded("action_show")
                    if (FloatingBubbleManager.isKeyboardVisible.value || A11yWatchdog.repairKind.value != A11yRepairKind.NONE) {
                        overlayWindowManager.showImmediately("action_show")
                    }
                }
                ACTION_RESTORE -> {
                    FloatingNotificationManager.cancelRestoreNotification(this)
                    rebuildOverlayIfNeeded("action_restore")
                    // v23: no main-thread getWindows() binder call here; use the state the
                    // accessibility worker thread already computed, and ask it to re-evaluate.
                    val access = RapchaAccessibilityService.instance
                    access?.requestEvaluation()
                    val imeUp = FloatingBubbleManager.isKeyboardVisible.value ||
                        access?.isImeVisibleCached() == true
                    if (imeUp) {
                        FloatingBubbleManager.notifyKeyboardVisibility(true)
                    }
                    overlayWindowManager.restoreOverlayVisibility(
                        isKeyboardOpen = imeUp || A11yWatchdog.repairKind.value != A11yRepairKind.NONE
                    )
                }
                ACTION_REPAIR_A11Y -> {
                    openRepairDestination()
                }
                ACTION_RETRY_CLIP -> if (::sessionManager.isInitialized) {
                    onRetryClicked(
                        clipId = intent.getStringExtra(EXTRA_CLIP_ID),
                        fromHistory = intent.getBooleanExtra(EXTRA_FROM_HISTORY, false)
                    )
                }
            }
        }
        return START_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        // v23: one shared background initializer (logs, diagnostics, render budget, retry clips)
        // started once per process. Nothing here touches the disk on the main thread.
        com.princeniithompson.rapcha.RapchaApplication.ensureBackgroundInit(this)
        com.princeniithompson.rapcha.diagnostics.BubbleHealthLog.markServiceRunning(this, true)
        if (!enterSpecialUseForeground()) {
            // v23: keep running unpromoted (overlay works with SYSTEM_ALERT_WINDOW alone).
            // Never stopSelf here: that also threw away START_STICKY.
            BubbleRecoveryJobScheduler.schedule(this, RecoveryJobPolicy.DEFAULT_DELAY_MS)
        }
        BubbleRecoveryJobScheduler.scheduleIfMissing(this, RecoveryJobPolicy.REPEAT_LATENCY_MS)
        Log.d(TAG, "FloatingBubbleService onCreate promoted=$promotedToForeground")
        serviceScope.launch(Dispatchers.IO) {
            try {
                // v24 Lite: skip the exit-reason lookup (diagnostics only).
                if (!com.princeniithompson.rapcha.core.LiteMode.enabled &&
                    com.princeniithompson.rapcha.diagnostics.BubbleHealthLog.consumeUncleanShutdownPossible(this@FloatingBubbleService)) {
                    val killReason = com.princeniithompson.rapcha.diagnostics.ProcessExitDiagnostics.lastUncleanKillReason(this@FloatingBubbleService)
                    if (killReason != null) {
                        com.princeniithompson.rapcha.diagnostics.BubbleHealthLog.recordLastRestartReason(
                            this@FloatingBubbleService,
                            "Restarted after $killReason"
                        )
                        com.princeniithompson.rapcha.diagnostics.BubbleHealthLog.record(
                            context = this@FloatingBubbleService,
                            type = DiagnosticType.RESTARTED_AFTER_KILL,
                            message = "exit_reason=$killReason"
                        )
                    }
                }
                // Heavy repository loads run here, after the overlay window is created below.
                CustomVocabularyRepository.init(this@FloatingBubbleService)
                HistoryRepository.init(this@FloatingBubbleService)
            } catch (t: Throwable) {
                Log.w(TAG, "Background service init failed: ${t.javaClass.simpleName}", t)
                AppLogRepository.logEvent(
                    DiagnosticSource.BUBBLE,
                    DiagnosticType.WARNING,
                    "service_async_init_failed kind=${t.javaClass.simpleName} detail=${t.message?.take(120)}"
                )
            }
        }
        FloatingBubbleManager.refreshLiveTwoStep(this)
        FloatingBubbleManager.onOfflineRetry = { onRetryClicked() }
        FloatingBubbleManager.onOfflineDiscard = { onDiscardClicked() }

        overlayWindowManager = FloatingOverlayWindowManager(this)
        sessionManager = FloatingDictationSessionManager(
            context = this,
            onTranscriptUpdated = { fullText ->
                overlayWindowManager.overlayTranscript.value = fullText
                if (!com.princeniithompson.rapcha.core.LiteMode.enabled) FloatingBubbleManager.updateSmartDefaultPolishMode(fullText)
            },
            onInterimReceived = { _ -> },
            onFinalSegmentReceived = { _ ->
                FloatingHapticManager.trigger(this, FloatingHapticType.FINAL_SENTENCE)
                if (isPendingInjectionOnSmartCompletion) {
                    Log.d(TAG, "Final transcript segment arrived while awaiting completion -> injecting")
                    isPendingInjectionOnSmartCompletion = false
                    pendingCompletionTimeoutJob?.cancel()
                    pendingCompletionTimeoutJob = null
                    performInjectionAndClose()
                }
            },
            onConnectionStateChanged = { state ->
                if (state is ConnectionState.Error && isPendingInjectionOnSmartCompletion) {
                    isPendingInjectionOnSmartCompletion = false
                    pendingCompletionTimeoutJob?.cancel()
                    pendingCompletionTimeoutJob = null
                    performInjectionAndClose()
                }
                refreshRetryOffer()
            },
            onAmplitudeChanged = { amp ->
                // v24 Lite: static bubble, so no per-chunk amplitude recomposition.
                if (!com.princeniithompson.rapcha.core.LiteMode.enabled) overlayWindowManager.overlayAudioAmplitude.value = amp
            },
            onError = { err ->
                val endedReasonTag = DictationEndedReason.fromMessage(err)

                AppLogRepository.logEvent(
                    DiagnosticSource.BUBBLE,
                    DiagnosticType.ERROR,
                    err
                )
                if (sessionManager.isRecording && !offlineRetry.isSticky()) {
                    stopVoiceTyping(endedReason = endedReasonTag, discardRetryClip = false)
                    serviceScope.launch {
                        sessionManager.awaitRetryClip()
                        if (RetryClipStore.peekDataBytes() > 0L) {
                            offlineRetry.holdAfterFailure()
                        }
                        refreshRetryOffer()
                    }
                }
            },
            onDurationTicked = { seconds ->
                if (::overlayWindowManager.isInitialized) {
                    overlayWindowManager.overlayElapsedSeconds.value = seconds
                }
                if (sessionManager.isRecording &&
                    (FloatingBubbleManager.isNetworkProblem.value || offlineRetry.isSticky())
                ) {
                    refreshRetryOffer()
                }
            },
            onSessionLimit = { _, finalizeNow ->
                serviceScope.launch(Dispatchers.Main) {
                    if (!finalizeNow) {
                        DictationAlerts.endingSoon(this@FloatingBubbleService)
                    } else if (sessionManager.isRecording) {
                        sessionLimitAutoFinish = true
                        if (FloatingBubbleManager.sectionPolishMode.value == SectionPolishMode.TWO_STEP_LIVE) {
                            onPolishClicked()
                        } else {
                            onConfirmClicked()
                        }
                    }
                }
            },
            onPreviewPhase = { phase ->
                overlayWindowManager.overlayPreviewPhase.value = phase
                if (phase == InterimPreviewMonitor.Phase.CONNECTION_LOST) {
                    if (!connectionLostHapticSent) {
                        connectionLostHapticSent = true
                        DictationAlerts.connectionDropped(this@FloatingBubbleService)
                    }
                } else if (phase == InterimPreviewMonitor.Phase.PREVIEW_DELAYED) {
                    // The stall is already in the diagnostic log. The bubble stays quiet.
                } else {
                    connectionLostHapticSent = false
                }
            }
        )

        try {
            initOverlayWindow()
            networkWatcher = RetryNetworkWatcher(this) { onNetworkBack() }.also { it.start() }
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing overlay window", e)
        }
        // v23: pending offline-retry clips are read from disk off the main thread, after the
        // overlay window already exists.
        serviceScope.launch(Dispatchers.IO) {
            try {
                offlineRetry.restorePending(this@FloatingBubbleService)
                RetryClipStore.currentId()?.let { clipId ->
                    if (offlineRetry.isSticky()) linkPendingHistory(clipId)
                }
                withContext(Dispatchers.Main) {
                    if (::overlayWindowManager.isInitialized &&
                        FloatingBubbleManager.offlineRetry.value != null &&
                        FloatingBubbleManager.isKeyboardVisible.value
                    ) {
                        overlayWindowManager.expandPanel()
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Offline retry restore failed", t)
                AppLogRepository.logEvent(
                    DiagnosticSource.BUBBLE,
                    DiagnosticType.WARNING,
                    "retry_restore_failed kind=${t.javaClass.simpleName}"
                )
            }
        }
        registerScreenReceiver()
        startWatchdogs()
    }

    private fun initOverlayWindow() {
        overlayWindowManager.initOverlay(
            onRingClick = { onRingClicked() },
            onCancelClick = { onCancelClicked() },
            onPolishClick = { onPolishClicked() },
            onCompleteClick = {
                val twoStep = FloatingBubbleManager.lockedPolishSection.value?.mode ==
                    com.princeniithompson.rapcha.service.floating.SectionPolishMode.TWO_STEP_LIVE
                if (com.princeniithompson.rapcha.service.floating.CompleteActionRoute.usesLiveFinish(twoStep)) {
                    onPolishClicked()
                } else {
                    onConfirmClicked()
                }
            },
            onRepairClick = { openRepairDestination() }
        )
    }

    private fun rebuildOverlayIfNeeded(reason: String) {
        if (!::overlayWindowManager.isInitialized) {
            overlayWindowManager = FloatingOverlayWindowManager(this)
        }
        if (overlayWindowManager.overlayView == null) {
            try {
                initOverlayWindow()
            } catch (e: Exception) {
                Log.e(TAG, "Failed rebuilding overlay window ($reason)", e)
            }
        } else if (!overlayWindowManager.isAttached()) {
            overlayWindowManager.attachWithRetry(reason)
        }
    }

    fun onRecoveryJobPing() {
        if (!FloatingBubbleManager.isBubbleEnabledSync(this) ||
            !FloatingBubbleManager.canDrawOverlays(this)
        ) {
            return
        }
        serviceScope.launch(Dispatchers.Main) {
            rebuildOverlayIfNeeded("recovery_job_ping")
            A11yWatchdog.check(this@FloatingBubbleService)
        }
    }

    private fun openRepairDestination() {
        val destination = if (A11yWatchdog.repairKind.value == A11yRepairKind.PERMISSION_REVOKED) {
            FloatingNotificationManager.DEST_ACCESSIBILITY_GUIDE
        } else {
            FloatingNotificationManager.DEST_KEEP_AWAKE
        }
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(FloatingNotificationManager.EXTRA_OPEN_DESTINATION, destination)
        }
        try {
            startActivity(intent)
        } catch (e: Throwable) {
            Log.w(TAG, "Falling back to system accessibility settings", e)
            com.princeniithompson.rapcha.ui.protection.DeviceProtectionManager.openAccessibilitySettings(this)
        }
    }

    private fun registerScreenReceiver() {
        if (screenReceiverRegistered) return
        screenInteractive = (getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isInteractive ?: true
        try {
            val filter = IntentFilter(Intent.ACTION_SCREEN_OFF).apply {
                addAction(Intent.ACTION_SCREEN_ON)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(screenReceiver, filter, RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(screenReceiver, filter)
            }
            screenReceiverRegistered = true
        } catch (t: Throwable) {
            com.princeniithompson.rapcha.diagnostics.BubbleHealthLog.record(
                context = this,
                type = DiagnosticType.WARNING,
                message = "bubble_screen_receiver_failed kind=${t.javaClass.simpleName}"
            )
        }
    }

    private fun startWatchdogs() {
        watchdogJob?.cancel()
        watchdogJob = serviceScope.launch {
            var tickCounter = 0
            while (isActive) {
                delay(VisibilityWatchdogDecision.WATCHDOG_INTERVAL_MS)
                tickCounter++

                if (::overlayWindowManager.isInitialized) {
                    val (nextState, action) = VisibilityWatchdogDecision.onTick(
                        state = watchdogState,
                        screenOn = screenInteractive,
                        bubbleEnabled = FloatingBubbleManager.isBubbleEnabledSync(this@FloatingBubbleService),
                        componentsInitialized = componentsReady(),
                        hasOverlayPermission = FloatingBubbleManager.canDrawOverlays(this@FloatingBubbleService),
                        shouldBeVisible = overlayWindowManager.shouldBeVisible(),
                        sessionActive = isBubbleSessionActive(),
                        isAttached = overlayWindowManager.isAttached(),
                        windowVisibilityVisible = overlayWindowManager.isWindowVisible(),
                        keyboardVisible = FloatingBubbleManager.isKeyboardVisible.value
                    )
                    watchdogState = nextState
                    if (action == VisibilityWatchdogDecision.TickAction.RECOVER) {
                        val recoverReason = if (!overlayWindowManager.isAttached()) {
                            "detached_while_needed"
                        } else {
                            "invisible_while_keyboard_up"
                        }
                        com.princeniithompson.rapcha.diagnostics.BubbleHealthLog.record(
                            context = this@FloatingBubbleService,
                            type = DiagnosticType.WATCHDOG_RECOVER,
                            message = "reason=$recoverReason session_active=${isBubbleSessionActive()}"
                        )
                        if (isBubbleSessionActive()) {
                            overlayWindowManager.attachWithRetry("watchdog_session")
                            overlayWindowManager.updateOverlayVisibility(isSessionActive = true)
                        } else {
                            if (overlayWindowManager.overlayView == null) {
                                initOverlayWindow()
                            } else if (!overlayWindowManager.isAttached()) {
                                overlayWindowManager.attachWithRetry("watchdog_recover")
                            }
                            // v23: rebuilding the whole Compose overlay is expensive on low-end
                            // phones. Only do it when the window has really been dead for more
                            // than two watchdog ticks in a row, not on every visibility mismatch.
                            if (!overlayWindowManager.isAttached()) {
                                detachedRecoverTicks++
                                if (detachedRecoverTicks > 2) {
                                    detachedRecoverTicks = 0
                                    overlayWindowManager.onDestroy()
                                    overlayWindowManager = FloatingOverlayWindowManager(this@FloatingBubbleService)
                                    initOverlayWindow()
                                }
                            } else {
                                detachedRecoverTicks = 0
                            }
                            overlayWindowManager.restoreOverlayVisibility(
                                FloatingBubbleManager.isKeyboardVisible.value ||
                                    A11yWatchdog.repairKind.value != A11yRepairKind.NONE
                            )
                        }
                    }
                }

                if (tickCounter % 2 == 0) {
                    A11yWatchdog.check(this@FloatingBubbleService)
                }
            }
        }
    }

    private fun componentsReady(): Boolean {
        return ::overlayWindowManager.isInitialized && ::sessionManager.isInitialized
    }

    private fun startForegroundForPlan(notification: android.app.Notification, plan: FgsStartupDecision.FgsPlan) {
        when (plan) {
            FgsStartupDecision.FgsPlan.LEGACY_NO_TYPE -> {
                startForeground(FloatingNotificationManager.NOTIFICATION_ID, notification)
            }
            FgsStartupDecision.FgsPlan.TYPE_ZERO -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(FloatingNotificationManager.NOTIFICATION_ID, notification, 0)
                } else {
                    startForeground(FloatingNotificationManager.NOTIFICATION_ID, notification)
                }
            }
            FgsStartupDecision.FgsPlan.MICROPHONE_ONLY -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(
                        FloatingNotificationManager.NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                    )
                } else {
                    startForeground(FloatingNotificationManager.NOTIFICATION_ID, notification)
                }
            }
            FgsStartupDecision.FgsPlan.SPECIAL_USE_ONLY -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    startForeground(
                        FloatingNotificationManager.NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                    )
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(FloatingNotificationManager.NOTIFICATION_ID, notification, 0)
                } else {
                    startForeground(FloatingNotificationManager.NOTIFICATION_ID, notification)
                }
            }
            FgsStartupDecision.FgsPlan.SPECIAL_USE_AND_MICROPHONE -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    startForeground(
                        FloatingNotificationManager.NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                    )
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(
                        FloatingNotificationManager.NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                    )
                } else {
                    startForeground(FloatingNotificationManager.NOTIFICATION_ID, notification)
                }
            }
        }
    }

    /**
     * Must run before any other service work. A late or failed startForeground
     * is what produces ForegroundServiceDidNotStartInTime after an update.
     */
    private fun enterSpecialUseForeground(): Boolean {
        FloatingNotificationManager.createChannel(this)
        val notification = FloatingNotificationManager.buildNotification(this, "Floating voice bubble is active")
        val plan = FgsStartupDecision.evaluatePlan(
            sdkInt = Build.VERSION.SDK_INT,
            isRecordingStarting = false,
            hasRecordAudioPermission = false,
            allowMicrophoneInCurrentState = false
        )
        return try {
            startForegroundForPlan(notification, plan)
            promotedToForeground = true
            true
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to startForeground(plan=$plan)", e)
            com.princeniithompson.rapcha.diagnostics.BubbleHealthLog.record(
                context = this,
                type = DiagnosticType.FGS_REFUSED,
                message = "site=special_use plan=$plan kind=${e.javaClass.simpleName} detail=${e.message?.take(140)}"
            )
            false
        }
    }

    private fun startMicrophoneForeground(): Boolean {
        val hasMicPerm = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        val allowedState = MicrophoneForegroundPolicy.isAllowed(this)
        if (!hasMicPerm || !allowedState) {
            AppLogRepository.logEvent(
                DiagnosticSource.BUBBLE,
                DiagnosticType.WARNING,
                "microphone startForeground skipped: perm=$hasMicPerm allowed=$allowedState"
            )
            return false
        }
        val plan = FgsStartupDecision.evaluatePlan(
            sdkInt = Build.VERSION.SDK_INT,
            isRecordingStarting = true,
            hasRecordAudioPermission = hasMicPerm,
            allowMicrophoneInCurrentState = allowedState
        )
        val notification = FloatingNotificationManager.buildNotification(this, "Listening...")
        return try {
            startForegroundForPlan(notification, plan)
            true
        } catch (e: Throwable) {
            Log.e(TAG, "microphone startForeground failed(plan=$plan)", e)
            val fallback = FgsStartupDecision.fallbackPlan(Build.VERSION.SDK_INT, plan)
            if (fallback != null) {
                com.princeniithompson.rapcha.diagnostics.BubbleHealthLog.record(
                    context = this,
                    type = DiagnosticType.FGS_FALLBACK,
                    message = "from=$plan to=$fallback kind=${e.javaClass.simpleName} detail=${e.message?.take(120)}"
                )
                try {
                    val activeNotif = FloatingNotificationManager.buildNotification(this, "Floating voice bubble is active")
                    startForegroundForPlan(activeNotif, fallback)
                } catch (fallbackEx: Throwable) {
                    com.princeniithompson.rapcha.diagnostics.BubbleHealthLog.record(
                        context = this,
                        type = DiagnosticType.FGS_REFUSED,
                        message = "site=mic_fallback plan=$fallback kind=${fallbackEx.javaClass.simpleName}"
                    )
                }
            }
            false
        }
    }

    fun onSensitiveAppEntered(pkg: String?) {
        Log.d(TAG, "Safe Mode entered during session check for $pkg")
        if (!componentsReady()) return
        serviceScope.launch(Dispatchers.Main) {
            if (sessionManager.isRecording) {
                preserveSessionForSafeMode()
            } else {
                concealForSafeMode()
            }
        }
    }

    /**
     * Safe Mode cut the session before Complete. Release the mic, keep the words,
     * and copy them. Do not type into the field that is now on screen.
     */
    private suspend fun preserveSessionForSafeMode() {
        FloatingHapticManager.trigger(this, FloatingHapticType.TRANSCRIPTION_STOP)
        FloatingBubbleManager.setRecordingState(false)
        overlayWindowManager.overlayRecording.value = false
        overlayWindowManager.overlayPendingFinalizing.value = true
        try {
            sessionManager.releaseMicrophoneNow()
            dropMicrophoneForegroundNow()
            val text = sessionManager.finishActivityAndAwaitTranscript().trim()
            if (text.isBlank()) {
                if (RetryClipStore.peekDataBytes() > 0L) {
                    offlineRetry.holdAfterFailure()
                    refreshRetryOffer()
                } else if (sessionManager.sessionBytesSent > 32_000L) {
                    DictationAlerts.finalTextLost(this)
                }
                return
            }
            withContext(Dispatchers.IO) { sessionManager.settleRetryClip(discard = true) }
            offlineRetry.clearHold()
            val durationSec = sessionManager.durationSeconds
            val lockedCtx = FloatingBubbleManager.lockedSessionContext.value ?: "Rapcha"
            HistoryRepository.addHistoryItem(
                text = text,
                appContext = lockedCtx,
                durationSeconds = durationSec
            )
            when (SessionPlacement.forFinishedSession(fieldFocused = true, cutOffBeforeComplete = true)) {
                SessionPlacement.Path.CLIPBOARD -> {
                    val copied = RapchaTextInserter.getInstance(this).copyCutOffTranscript(text)
                    if (copied) {
                        DictationAlerts.textCopied(this)
                        VocabSuggestions.onConfirmedClipboard(this, text, copied = true)
                    } else {
                        DictationAlerts.placementFailed(this)
                    }
                }
                SessionPlacement.Path.INJECT -> Unit
            }
        } catch (e: Exception) {
            Log.e(TAG, "Safe Mode handoff failed", e)
            DictationAlerts.placementFailed(this)
        } finally {
            concealForSafeMode()
        }
    }

    private fun concealForSafeMode() {
        overlayWindowManager.collapsePanel()
        resetAndCollapse()
        overlayWindowManager.hideOverlayImmediately()
    }

    fun onSensitiveAppExited() {
        if (!componentsReady()) return
        serviceScope.launch(Dispatchers.Main) {
            if (offlineRetry.isHolding()) {
                refreshRetryOffer()
                overlayWindowManager.expandToFullSize()
                overlayWindowManager.expandPanel()
            }
            val isKeyboardOpen = FloatingBubbleManager.isKeyboardVisible.value
            overlayWindowManager.restoreOverlayVisibility(isKeyboardOpen = isKeyboardOpen)
        }
    }

    private fun onRingClicked() {
        val access = RapchaAccessibilityService.instance
        val keyboard = access?.isImeVisibleNow() == true
        val field = access?.hasFocusedEditableField() == true
        val recording = ::sessionManager.isInitialized && sessionManager.isRecording
        val netTag = if (com.princeniithompson.rapcha.core.LiteMode.enabled) "" else com.princeniithompson.rapcha.diagnostics.NetworkAndDeviceDiagnostics.currentTag(this)
        val outcome = when {
            FloatingBubbleManager.isCurrentAppSensitive.value -> "blocked_sensitive_app"
            FloatingBubbleManager.offlineRetry.value != null && !recording -> "opened_offline_retry"
            decideRingTap(recording, keyboard, field) == RingTap.HIDE -> "hid_no_keyboard"
            startsRecordingOnTap(recording, keyboard, field) -> "starting_session"
            else -> "already_recording"
        }
        AppLogRepository.logEvent(
            DiagnosticSource.BUBBLE,
            DiagnosticType.BUBBLE_TAP,
            "keyboard=$keyboard field=$field recording=$recording outcome=$outcome $netTag"
        )
        if (FloatingBubbleManager.isCurrentAppSensitive.value) {
            return
        }
        if (FloatingBubbleManager.offlineRetry.value != null && !recording) {
            overlayWindowManager.expandToFullSize()
            overlayWindowManager.expandPanel()
            return
        }
        if (decideRingTap(recording, keyboard, field) == RingTap.HIDE) {
            FloatingBubbleManager.noteBubbleHideSignal()
            overlayWindowManager.onKeyboardVisibilityChanged(isVisible = false, isSessionActive = false)
            return
        }
        if (startsRecordingOnTap(recording, keyboard, field)) {
            if (overlayWindowManager.overlayShrunk.value || !overlayWindowManager.overlayExpanded.value) {
                overlayWindowManager.expandToFullSize()
            }
            if (!overlayWindowManager.overlayExpanded.value) {
                overlayWindowManager.expandPanel()
            }
            startVoiceTyping()
        } else {
            FloatingHapticManager.trigger(this, FloatingHapticType.BUBBLE_HOLD)
        }
    }

    private fun startVoiceTyping() {
        fgsDowngradeJob?.cancel()
        fgsDowngradeJob = null

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            AppLogRepository.logEvent(
                DiagnosticSource.BUBBLE,
                DiagnosticType.WARNING,
                "session_blocked reason=missing_record_audio_permission"
            )
            Toast.makeText(this, "Microphone permission required. Please grant it in Rapcha.", Toast.LENGTH_LONG).show()
            val appIntent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(appIntent)
            return
        }

        val apiKey = getEffectiveApiKey()
        if (ApiConfig.isPlaceholder(apiKey)) {
            AppLogRepository.logEvent(
                DiagnosticSource.BUBBLE,
                DiagnosticType.WARNING,
                "session_blocked reason=missing_api_key"
            )
            Toast.makeText(this, "Gemini API key is required. Please set it in Rapcha app first.", Toast.LENGTH_LONG).show()
            if (::overlayWindowManager.isInitialized) {
                overlayWindowManager.collapsePanel()
            }
            return
        }

        offlineRetry.onNewSession()

        if (!startMicrophoneForeground()) {
            DictationAlerts.microphoneFailed(this)
            if (::sessionManager.isInitialized && sessionManager.isRecording) {
                stopVoiceTyping(endedReason = "microphone_foreground_denied")
            }
            return
        }

        val lite = com.princeniithompson.rapcha.core.LiteMode.enabled
        val offlineReason = if (lite) null else com.princeniithompson.rapcha.diagnostics.NetworkAndDeviceDiagnostics.checkOfflineOrAirplaneReason(this)
        if (offlineReason != null) {
            com.princeniithompson.rapcha.diagnostics.NetworkAndDeviceDiagnostics.recordNetworkFailure(
                source = DiagnosticSource.BUBBLE,
                stage = "session_start",
                reason = "starting_while_$offlineReason",
                context = this
            )
        }

        overlayWindowManager.expandToFullSize()
        FloatingBubbleManager.lockSessionContext(this)

        activeDetectionJob?.cancel()
        activeDetectionJob = null

        // v24 Lite: no app detection at all (no window lookup, no Gemini classify, no per-app polish).
        val a11y = if (lite) null else RapchaAccessibilityService.instance
        val pkg = if (lite) null else a11y?.getActivePackageName() ?: FloatingBubbleManager.currentForegroundPackage.value
        completeTargetPackage = pkg?.takeUnless {
            it == packageName || com.princeniithompson.rapcha.util.AppResolutionEngine.defaultInstance.isSystemOrIme(this, it)
        }
        if (pkg != null && pkg != packageName && !com.princeniithompson.rapcha.util.AppResolutionEngine.defaultInstance.isSystemOrIme(this, pkg)) {
            activeDetectionJob = serviceScope.launch {
                try {
                    // 1. IMPLEMENT DEBOUNCING: Wait 500ms and verify foreground package remains stable
                    delay(500L)
                    val currentPkg = a11y?.getActivePackageName() ?: FloatingBubbleManager.currentForegroundPackage.value
                    if (currentPkg != pkg) {
                        Log.d("AppDetector", "Package name changed from $pkg to $currentPkg. Skipping detection.")
                        return@launch
                    }

                    // Collect multi-signal evidence (Stage A)
                    val evidence = com.princeniithompson.rapcha.util.AppResolutionEngine.defaultInstance.collectEvidence(
                        context = this@FloatingBubbleService,
                        packageName = pkg
                    )

                    // Run hybrid detection (Stage B local check -> Stage C Gemini only if needed)
                    val result = kotlinx.coroutines.withTimeout(20000L) {
                        AppDetector.detectAppHybrid(evidence, this@FloatingBubbleService)
                    }

                    // Log success
                    val resolutionSource = if (result.isLocallyResolved) "Local" else "Gemini"
                    Log.d("AppDetector", "Detection succeeded ($resolutionSource): ${result.appName} -> ${result.category}")
                    
                    // Show final result visibly (small label on the bubble and toast)
                    val formatted = "Detected: ${result.appName} -> ${result.category}"
                    FloatingBubbleManager.setLockedSessionContext(formatted)
                    Toast.makeText(this@FloatingBubbleService, formatted, Toast.LENGTH_SHORT).show()
                } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
                    val reason = "Detection timed out after 20s"
                    Log.d("AppDetector", "Detection failed: $reason")
                    FloatingBubbleManager.setLockedSessionContext("Detection failed: $reason")
                    Toast.makeText(this@FloatingBubbleService, "Detection failed: $reason", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    val reason = e.message ?: e.javaClass.simpleName
                    Log.d("AppDetector", "Detection failed: $reason")
                    FloatingBubbleManager.setLockedSessionContext("Detection failed: $reason")
                    Toast.makeText(this@FloatingBubbleService, "Detection failed: $reason", Toast.LENGTH_SHORT).show()
                }
            }
        } else {
            FloatingBubbleManager.setLockedSessionContext(null)
        }

        val prefs = getSharedPreferences("voxstream_settings", Context.MODE_PRIVATE)
        val savedSmartMode = prefs.getBoolean("smart_mode", false)
        val override = Stage2PolishSettings.read(prefs)
        val threeStepEnabled = ThreeStepPolishSettings.read(prefs)
        val target = if (lite) {
            com.princeniithompson.rapcha.service.floating.FloatingPolishCoordinator.Stage2Target(
                com.princeniithompson.rapcha.service.floating.Stage2Category.OTHER,
                com.princeniithompson.rapcha.service.AppCategory.OTHER,
                "App"
            )
        } else {
            polishCoordinator.stage2Target(
                this,
                completeTargetPackage,
                null,
                allowLiveHint = false
            )
        }
        val aiApp = HybridPolishPolicy.isAiTarget(target.stage2Category, target.polishCategory)
        val quotaStatus = com.princeniithompson.rapcha.service.floating.PolishQuotaMonitor.status(this)
        val plan = if (lite) {
            LiteMode.smartOnlyPlan()
        } else {
            ThreeStepPolicy.resolve(
                override = override,
                aiApp = aiApp,
                threeStepEnabled = threeStepEnabled,
                savedSmartMode = savedSmartMode,
                quotaAvailable = quotaStatus.available
            )
        }
        val mode = plan.mode
        val liveCategory = HybridPolishPolicy.liveCategory(target.stage2Category, aiApp)
        val instruction = if (mode == SectionPolishMode.TWO_STEP_LIVE) {
            Stage2LivePrompt.systemInstruction(
                liveCategory,
                target.appName,
                CustomVocabularyRepository.getVocabulary()
            )
        } else {
            ""
        }
        val section = LockedPolishSection(
            mode = mode,
            stage2Category = if (mode == SectionPolishMode.TWO_STEP_LIVE) liveCategory else target.stage2Category,
            polishCategory = target.polishCategory,
            appName = target.appName,
            systemInstruction = instruction,
            stage1 = plan.stage1,
            threeStep = plan.threeStep
        )
        FloatingBubbleManager.lockSection(section)
        val stage2ModelTag = com.princeniithompson.rapcha.service.floating.Stage2PolishRoute.model(
            mode == SectionPolishMode.TWO_STEP_LIVE,
            quotaStatus.available
        ) ?: "none"
        // v24 Lite: no session-start diagnostics (device snapshot read on the mic tap).
        if (!lite) {
        val deviceTag = com.princeniithompson.rapcha.diagnostics.NetworkAndDeviceDiagnostics.compactDeviceTag(this)
        val netTag = com.princeniithompson.rapcha.diagnostics.NetworkAndDeviceDiagnostics.currentTag(this)
        AppLogRepository.logEvent(
            DiagnosticSource.BUBBLE,
            DiagnosticType.SESSION_START,
            "build=${com.princeniithompson.rapcha.BuildConfig.VERSION_NAME} code=${com.princeniithompson.rapcha.BuildConfig.VERSION_CODE} commit=${com.princeniithompson.rapcha.BuildConfig.GIT_COMMIT} $deviceTag $netTag section_lock=${mode.name} category=${section.stage2Category.name} app=${section.appName} override=${override.storedValue()} three_step=${if (plan.threeStep) "yes" else "no"} quota_ok=${if (quotaStatus.available) "yes" else "cooldown:${quotaStatus.reason.name}:${quotaStatus.cooldownRemainingMs / 1000L}s"} rpm=${quotaStatus.rpmUsed}/${quotaStatus.rpmLimit} rpd=${quotaStatus.rpdUsed}/${quotaStatus.rpdLimit} stage1=${plan.stage1.name} stage1_model=${GeminiLiveWebSocketClient.DEFAULT_MODEL} stage2_model=$stage2ModelTag"
        )
        }
        Stage2WarmLine.close()

        FloatingBubbleManager.setRecordingState(true)
        FloatingHapticManager.trigger(this, FloatingHapticType.TRANSCRIPTION_START)
        overlayWindowManager.expandPanel()

        isPendingInjectionOnSmartCompletion = false
        overlayWindowManager.overlayPendingFinalizing.value = false
        pendingCompletionTimeoutJob?.cancel()
        pendingCompletionTimeoutJob = null
        overlayWindowManager.overlayRecording.value = true

        val isSmartMode = plan.stage1 == Stage1Capture.SMART
        val selectedModel = if (mode == SectionPolishMode.TWO_STEP_LIVE) {
            GeminiLiveWebSocketClient.DEFAULT_MODEL
        } else {
            prefs.getString("selected_model", GeminiLiveWebSocketClient.DEFAULT_MODEL) ?: GeminiLiveWebSocketClient.DEFAULT_MODEL
        }

        liveSessionId = java.util.UUID.randomUUID().toString()
        sessionManager.capturePrefinal = mode == SectionPolishMode.TWO_STEP_LIVE
        sessionManager.prefinalListener = if (mode == SectionPolishMode.TWO_STEP_LIVE) {
            { frozen -> persistFrozenRaw(frozen) }
        } else {
            null
        }
        sessionManager.startSession(
            context = this,
            scope = serviceScope,
            apiKey = apiKey,
            model = selectedModel,
            smartMode = isSmartMode
        )
    }

    private fun stopVoiceTyping(endedReason: String = "completed", discardRetryClip: Boolean = false) {
        if (!sessionManager.isRecording) return
        FloatingHapticManager.trigger(this, FloatingHapticType.TRANSCRIPTION_STOP)
        FloatingBubbleManager.setRecordingState(false)
        overlayWindowManager.overlayRecording.value = false
        overlayWindowManager.resetInactivityTimer()

        sessionManager.stopSession(serviceScope, endedReason, discardRetryClip)
        downgradeMicrophoneForeground()
    }

    private fun downgradeMicrophoneForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            fgsDowngradeJob?.cancel()
            fgsDowngradeJob = serviceScope.launch {
                delay(450L)
                dropMicrophoneForegroundType()
            }
        }
    }

    /** Safe Mode must drop the microphone type before waiting on the network. */
    private fun dropMicrophoneForegroundNow() {
        fgsDowngradeJob?.cancel()
        fgsDowngradeJob = null
        dropMicrophoneForegroundType()
        AppLogRepository.logEvent(
            DiagnosticSource.BUBBLE,
            DiagnosticType.MIC_RELEASED,
            "reason=safe_mode"
        )
    }

    private fun dropMicrophoneForegroundType() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        try {
            startForeground(
                FloatingNotificationManager.NOTIFICATION_ID,
                FloatingNotificationManager.buildNotification(this, "Floating voice bubble is active"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } catch (e: Exception) {
            Log.w(TAG, "Could not revert FGS to specialUse: ${e.message}")
            AppLogRepository.logEvent(
                DiagnosticSource.BUBBLE,
                DiagnosticType.WARNING,
                "Could not revert foreground service to specialUse: ${e.message?.take(180)}"
            )
        }
    }

    private fun onConfirmClicked() {
        if (overlayWindowManager.overlayPendingFinalizing.value) return
        overlayWindowManager.overlayPendingFinalizing.value = true
        FloatingBubbleManager.setRecordingState(false)
        overlayWindowManager.overlayRecording.value = false

        pendingCompletionTimeoutJob?.cancel()
        pendingCompletionTimeoutJob = serviceScope.launch {
            val textToInject = sessionManager.finishActivityAndAwaitTranscript()
            if (!isActive) return@launch
            if (textToInject.isBlank()) {
                if (RetryClipStore.peekDataBytes() > 0L) {
                    offlineRetry.holdAfterFailure()
                    overlayWindowManager.overlayPendingFinalizing.value = false
                    sessionLimitAutoFinish = false
                    refreshRetryOffer()
                } else {
                    withContext(Dispatchers.IO) { sessionManager.settleRetryClip(discard = true) }
                    sessionLimitAutoFinish = false
                    resetAndCollapse()
                }
                return@launch
            }
            withContext(Dispatchers.IO) { sessionManager.settleRetryClip(discard = true) }
            offlineRetry.clearHold()
            performInjectionAndClose(textToInject)
        }
    }

    private fun onRetryClicked(clipId: String? = null, fromHistory: Boolean = false) {
        if (fromHistory) {
            AppLogRepository.logEvent(
                DiagnosticSource.BUBBLE,
                DiagnosticType.RETRY_HISTORY,
                "source=history"
            )
        }
        if (com.princeniithompson.rapcha.service.floating.RetryConnectivity.isOnline(this)) {
            DictationAlerts.cancel(this, DictationAlerts.RETRY_READY_NOTIFICATION_ID)
            networkBackNotifiedFor = null
        }
        val retryApiKey = getEffectiveApiKey()
        if (ApiConfig.isPlaceholder(retryApiKey)) {
            Toast.makeText(this, "Gemini API key is required. Please set it in Rapcha app first.", Toast.LENGTH_LONG).show()
            return
        }
        serviceScope.launch {
            offlineRetry.runRetry(
                context = this@FloatingBubbleService,
                apiKey = retryApiKey,
                vocabulary = CustomVocabularyRepository.getVocabulary(),
                prepareClip = {
                    val target = clipId ?: RetryClipStore.currentId()
                    val recordingThisClip = sessionManager.isRecording &&
                        (target == null || RetryClipStore.currentId() == target)
                    if (recordingThisClip) {
                        stopVoiceTyping(endedReason = "offline_retry", discardRetryClip = false)
                        sessionManager.awaitRetryClip()
                    } else if (target != null && RetryClipStore.currentId() == target) {
                        withContext(Dispatchers.IO) { RetryClipStore.finalizeIfCurrent(target) }
                    }
                    if (target != null) RetryClipStore.clipFile(target) else RetryClipStore.sessionWavFile()
                },
                onAcceptedText = { text ->
                    val finishedId = clipId ?: RetryClipStore.currentId()
                    withContext(Dispatchers.IO) {
                        if (finishedId != null) RetryClipStore.discard(finishedId)
                        else sessionManager.settleRetryClip(discard = true)
                    }
                    if (finishedId != null) HistoryRepository.removePendingRetry(finishedId)
                    networkBackNotifiedFor = null
                    DictationAlerts.cancel(this@FloatingBubbleService, DictationAlerts.RETRY_READY_NOTIFICATION_ID)
                    performInjectionAndClose(text)
                }
            )
        }
    }

    fun noteRetryClipDeleted(clipId: String) {
        if (!::sessionManager.isInitialized) return
        if (RetryClipStore.currentId() != clipId) return
        serviceScope.launch {
            if (sessionManager.isRecording && RetryClipStore.currentId() == clipId) {
                stopVoiceTyping(endedReason = "history_deleted", discardRetryClip = true)
                sessionManager.awaitRetryClip()
            } else {
                withContext(Dispatchers.IO) { RetryClipStore.discard(clipId) }
            }
            HistoryRepository.detachRetryClip(clipId)
            networkBackNotifiedFor = null
            DictationAlerts.cancel(this@FloatingBubbleService, DictationAlerts.RETRY_READY_NOTIFICATION_ID)
            offlineRetry.clearHold()
            resetAndCollapse()
        }
    }

    private fun onDiscardClicked() {
        val clipId = RetryClipStore.currentId()
        val durationMs = RetryClipStore.durationMsForBytes(RetryClipStore.peekDataBytes())
        AppLogRepository.logEvent(
            DiagnosticSource.BUBBLE,
            DiagnosticType.RETRY_DISCARDED,
            "duration_ms=$durationMs"
        )
        serviceScope.launch {
            if (sessionManager.isRecording) {
                stopVoiceTyping(endedReason = "retry_discarded", discardRetryClip = true)
                sessionManager.awaitRetryClip()
            } else {
                withContext(Dispatchers.IO) { sessionManager.settleRetryClip(discard = true) }
            }
            if (clipId != null) HistoryRepository.detachRetryClip(clipId)
            networkBackNotifiedFor = null
            DictationAlerts.cancel(this@FloatingBubbleService, DictationAlerts.RETRY_READY_NOTIFICATION_ID)
            offlineRetry.clearHold()
            pendingCompletionTimeoutJob?.cancel()
            pendingCompletionTimeoutJob = null
            overlayWindowManager.overlayPendingFinalizing.value = false
            isPendingInjectionOnSmartCompletion = false
            Toast.makeText(this@FloatingBubbleService, "Dictation discarded", Toast.LENGTH_SHORT).show()
            resetAndCollapse()
        }
    }

    private fun refreshRetryOffer() {
        if (!::sessionManager.isInitialized || !::overlayWindowManager.isInitialized) return
        val bytes = RetryClipStore.peekDataBytes()
        val networkProblem = FloatingBubbleManager.isNetworkProblem.value
        val recording = sessionManager.isRecording
        val shouldStick = !offlineRetry.isSticky() && OfflineRetryRules.shouldOffer(
            recording = recording,
            networkProblem = networkProblem,
            holdingFailure = false,
            busy = false,
            dataBytes = bytes,
            sticky = false
        )
        if ((shouldStick || offlineRetry.isHolding()) && bytes > 0L && !RetryClipStore.isUploadFrozen()) {
            RetryClipStore.freezeForUpload()
        }
        if (shouldStick) {
            offlineRetry.stick()
            sessionManager.abandonLiveKeepMicrophone()
            RetryClipStore.currentId()?.let { linkPendingHistory(it) }
        }
        val showing = offlineRetry.publish(
            context = this,
            recording = recording,
            networkProblem = networkProblem
        )
        if (showing && !overlayWindowManager.overlayExpanded.value) {
            overlayWindowManager.expandPanel()
        }
    }

    private fun linkPendingHistory(clipId: String) {
        HistoryRepository.init(this)
        val transcript = if (::sessionManager.isInitialized) sessionManager.getFullTranscriptText() else ""
        HistoryRepository.upsertPendingRetry(
            clipId = clipId,
            text = transcript,
            appContext = FloatingBubbleManager.lockedSessionContext.value ?: "Rapcha",
            durationSeconds = if (::sessionManager.isInitialized) sessionManager.durationSeconds else 0
        )
    }

    private fun onNetworkBack() {
        if (!offlineRetry.isSticky() && FloatingBubbleManager.offlineRetry.value == null) return
        val clipId = RetryClipStore.currentId() ?: return
        if (RetryClipStore.clipFile(clipId) == null && RetryClipStore.peekDataBytes() <= 0L) return
        offlineRetry.noteNetworkBack(android.os.SystemClock.elapsedRealtime())
        serviceScope.launch(Dispatchers.IO) {
            com.princeniithompson.rapcha.service.floating.TranscribeFallbackClient().warm(getEffectiveApiKey())
        }
        if (offlineRetry.takeOfflineArm()) {
            AppLogRepository.logEvent(
                DiagnosticSource.BUBBLE,
                DiagnosticType.RETRY_NETWORK_BACK,
                "notify=auto_start"
            )
            onRetryClicked(clipId)
            return
        }
        if (networkBackNotifiedFor == clipId) return
        networkBackNotifiedFor = clipId
        val retryIntent = Intent(this, FloatingBubbleService::class.java).apply {
            action = ACTION_RETRY_CLIP
            putExtra(EXTRA_CLIP_ID, clipId)
        }
        val tap = PendingIntent.getService(
            this,
            17,
            retryIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val posted = DictationAlerts.retryNetworkBack(this, tap)
        AppLogRepository.logEvent(
            DiagnosticSource.BUBBLE,
            DiagnosticType.RETRY_NETWORK_BACK,
            "notify=${if (posted) "posted" else "denied"}"
        )
    }

    internal fun isBubbleSessionActive(): Boolean {
        if (!::sessionManager.isInitialized || !::overlayWindowManager.isInitialized) return false
        return sessionManager.isRecording ||
            overlayWindowManager.overlayPendingFinalizing.value ||
            polishCoordinator.isPolishingInProgress.get() ||
            FloatingBubbleManager.offlineRetry.value != null
    }

    private fun performInjectionAndClose(textOverride: String? = null) {
        val startInjectTime = android.os.SystemClock.elapsedRealtime()
        val textToInject = textOverride?.trim().takeUnless { it.isNullOrEmpty() }
            ?: sessionManager.getFullTranscriptText()
        val durationSec = sessionManager.durationSeconds
        if (sessionManager.isRecording) {
            stopVoiceTyping(discardRetryClip = true)
        }

        if (textToInject.isBlank()) {
            if (sessionManager.sessionBytesSent > 32_000L) {
                DictationAlerts.finalTextLost(this)
            }
            sessionLimitAutoFinish = false
            resetAndCollapse()
            return
        }

        val lockedCtx = FloatingBubbleManager.lockedSessionContext.value ?: "AI · Rapcha"
        HistoryRepository.addHistoryItem(
            text = textToInject,
            appContext = lockedCtx,
            durationSeconds = durationSec
        )

        // Injection verifies off the main thread. Collapse only after it returns.
        serviceScope.launch {
            try {
                val placed = FloatingTextInjector.injectReporting(this@FloatingBubbleService, textToInject)
                VocabSuggestions.onConfirmedPlacement(this@FloatingBubbleService, textToInject, placed)
                if (sessionLimitAutoFinish) {
                    when (placed) {
                        is RapchaTextInserter.InsertionResult.SuccessClipboardFallback ->
                            DictationAlerts.sessionEnded(this@FloatingBubbleService, copied = true)
                        is RapchaTextInserter.InsertionResult.SuccessDirect ->
                            DictationAlerts.sessionEnded(this@FloatingBubbleService, copied = false)
                        is RapchaTextInserter.InsertionResult.Failed ->
                            FloatingHapticManager.trigger(this@FloatingBubbleService, FloatingHapticType.PROBLEM)
                    }
                }
                sessionLimitAutoFinish = false

                val elapsedMs = maxOf(300L, android.os.SystemClock.elapsedRealtime() - startInjectTime)
                val wordCount = textToInject.trim().split("\\s+".toRegex()).count { it.isNotBlank() }
                AppLogRepository.recordInjectionResult(
                    durationMs = elapsedMs,
                    wordCount = wordCount,
                    targetApp = lockedCtx,
                    transcriptText = textToInject,
                    source = DiagnosticSource.BUBBLE
                )
            } finally {
                resetAndCollapse()
            }
        }
    }

    private fun onPolishClicked() {
        val apiKey = getEffectiveApiKey()
        val locked = FloatingBubbleManager.lockedPolishSection.value
        if (locked?.mode == SectionPolishMode.TWO_STEP_LIVE) {
            if (overlayWindowManager.overlayPendingFinalizing.value) return
            overlayWindowManager.overlayPendingFinalizing.value = true
            FloatingBubbleManager.setRecordingState(false)
            overlayWindowManager.overlayRecording.value = false
            val generation = lyraFinishGeneration.incrementAndGet()
            offlineRetry.clearHold()
            serviceScope.launch {
                val endpoint = sessionManager.finishActivityForLyra()
                if (generation != lyraFinishGeneration.get()) {
                    resetAndCollapse()
                    return@launch
                }
                val frozenRaw = liveSessionId?.let { HistoryRepository.sessionById(it)?.stage("raw") }.orEmpty()
                AppLogRepository.logEvent(
                    DiagnosticSource.BUBBLE,
                    DiagnosticType.POLISH_TRACE,
                    "stage1_final completed=${if (endpoint.completed) "yes" else "no"} timed_out=${if (endpoint.timedOut) "yes" else "no"} ${com.princeniithompson.rapcha.data.TextFingerprint.describe("stage1", endpoint.text)} raw_chars=${frozenRaw.length}"
                )
                if (!endpoint.completed) {
                    if (frozenRaw.isNotBlank()) {
                        val placed = FloatingTextInjector.injectReporting(this@FloatingBubbleService, frozenRaw)
                        VocabSuggestions.onConfirmedPlacement(this@FloatingBubbleService, frozenRaw, placed)
                    }
                    resetAndCollapse()
                    return@launch
                }
                if (frozenRaw.isEmpty()) {
                    AppLogRepository.logEvent(
                        DiagnosticSource.BUBBLE,
                        DiagnosticType.POLISH_TRACE,
                        "prefinal_missing chars=0"
                    )
                }
                if (locked.threeStep && frozenRaw.isNotEmpty()) {
                    val id = liveSessionId ?: return@launch
                    val existing = HistoryRepository.sessionById(id)
                    HistoryRepository.commitSession(
                        com.princeniithompson.rapcha.data.VoiceSessionBuilder.captured(
                            sessionId = id,
                            timestamp = existing?.timestamp ?: System.currentTimeMillis(),
                            appName = locked.appName,
                            threeStep = true,
                            rawText = frozenRaw,
                            smartText = endpoint.text,
                            lyraText = null,
                            smartCompleted = true,
                            aiLyra = locked.stage2Category == com.princeniithompson.rapcha.service.floating.Stage2Category.AI,
                            sensitive = com.princeniithompson.rapcha.service.FloatingBubbleManager.isCurrentAppSensitive.value
                        )
                    )
                }
                val lyraInput = com.princeniithompson.rapcha.data.LyraHandoff.source(endpoint.text)
                AppLogRepository.logEvent(
                    DiagnosticSource.BUBBLE,
                    DiagnosticType.POLISH_TRACE,
                    "lyra_handoff ${com.princeniithompson.rapcha.data.TextFingerprint.describe("lyra_input", lyraInput)} matches_stage1=${if (com.princeniithompson.rapcha.data.LyraHandoff.matches(endpoint.text.trim(), lyraInput)) "yes" else "no"}"
                )
                polishWithLiveStage2(lyraInput, frozenRaw, apiKey, locked)
            }
            return
        }
        val rawTranscript = sessionManager.getFullTranscriptText()
        if (sessionManager.isRecording) {
            stopVoiceTyping(endedReason = "polished", discardRetryClip = true)
        } else if (FloatingBubbleManager.offlineRetry.value == null) {
            sessionManager.settleRetryClip(discard = true)
        }
        offlineRetry.clearHold()

        polishCoordinator.handlePolishRequest(
            context = this,
            scope = serviceScope,
            rawTranscript = rawTranscript,
            apiKey = apiKey,
            targetPackage = completeTargetPackage,
            targetHint = FloatingBubbleManager.lockedSessionContext.value,
            lockedCategory = locked?.polishCategory,
            lockedAppName = locked?.appName,
            onStartPolish = {
                overlayWindowManager.overlayPolishing.value = true
            },
            onFinishPolish = {
                overlayWindowManager.overlayPolishing.value = false
                resetAndCollapse()
            }
        )
    }

    /**
     * Live two-step Polish. The only model is models/gemini-3.8-live.
     * If that turn returns no text, the raw Stage-1 transcript is inserted.
     */
    private fun fieldIsReady(): Boolean {
        val a11y = com.princeniithompson.rapcha.service.RapchaAccessibilityService.instance ?: return false
        return a11y.hasActiveEditableNode()
    }

    private fun polishWithLiveStage2(
        stage1Final: String,
        frozenRaw: String,
        apiKey: String,
        locked: LockedPolishSection
    ) {
        val rawTranscript = stage1Final
        if (rawTranscript.isBlank()) {
            Toast.makeText(this, "No text to polish", Toast.LENGTH_SHORT).show()
            resetAndCollapse()
            return
        }
        overlayWindowManager.overlayPolishing.value = true
        val delivery = DeliverySession(
            id = android.os.SystemClock.elapsedRealtime(),
            originPackage = completeTargetPackage
        )
        activeDelivery = delivery
        val pasteGate = Mutex()
        val pasteGen = AtomicInteger(0)
        val pasteFinished = AtomicBoolean(false)
        serviceScope.launch {
            try {
                sessionManager.awaitLiveSocketClosed()
                com.princeniithompson.rapcha.websocket.GeminiLiveWebSocketClient.terminateActiveLiveSockets()
                val instruction = locked.systemInstruction.ifBlank {
                    Stage2LivePrompt.systemInstruction(
                        locked.stage2Category,
                        locked.appName,
                        CustomVocabularyRepository.getVocabulary()
                    )
                }
                var committed = ""
                fun refreshDelivery() {
                    val current = com.princeniithompson.rapcha.service.FloatingBubbleManager.currentForegroundPackage.value
                    val sensitive = com.princeniithompson.rapcha.service.FloatingBubbleManager.isCurrentAppSensitive.value
                    val launcher = !current.isNullOrBlank() &&
                        com.princeniithompson.rapcha.service.ForegroundAppPicker.isLauncherPackage(current)
                    delivery.note(
                        PolishDelivery.halt(
                            originPackage = delivery.originPackage,
                            currentPackage = current,
                            sensitive = sensitive,
                            cancelled = false,
                            launcher = launcher
                        )
                    )
                }
                suspend fun pasteLatest(
                    text: String,
                    final: Boolean,
                    clipboardWhenNoField: Boolean = false
                ): RapchaTextInserter.InsertionResult {
                    if (text.isBlank()) return RapchaTextInserter.InsertionResult.Failed
                    val gen = pasteGen.incrementAndGet()
                    if (final) pasteFinished.set(true)
                    return pasteGate.withLock {
                        if (activeDelivery !== delivery) {
                            return@withLock RapchaTextInserter.InsertionResult.Failed
                        }
                        refreshDelivery()
                        if (!delivery.acceptsInsert()) {
                            return@withLock RapchaTextInserter.InsertionResult.Failed
                        }
                        if (!final && (pasteFinished.get() || gen != pasteGen.get())) {
                            return@withLock RapchaTextInserter.InsertionResult.Failed
                        }
                        if (!final && !fieldIsReady()) {
                            return@withLock RapchaTextInserter.InsertionResult.Failed
                        }
                        when (val edit = LiveStreamPaste.plan(committed, text)) {
                            LiveStreamPaste.Edit.Skip -> {
                                if (committed.isNotEmpty()) {
                                    RapchaTextInserter.InsertionResult.SuccessDirect
                                } else {
                                    RapchaTextInserter.InsertionResult.Failed
                                }
                            }
                            is LiveStreamPaste.Edit.Append -> {
                                val placed = FloatingTextInjector.injectReporting(
                                    this@FloatingBubbleService,
                                    edit.suffix,
                                    clipboardText = text
                                )
                                if (placed is RapchaTextInserter.InsertionResult.SuccessDirect ||
                                    placed is RapchaTextInserter.InsertionResult.SuccessClipboardFallback
                                ) {
                                    committed = text
                                }
                                placed
                            }
                            is LiveStreamPaste.Edit.Replace -> {
                                val placed = FloatingTextInjector.rewriteReporting(
                                    this@FloatingBubbleService,
                                    edit.previousLength,
                                    edit.full
                                )
                                if (placed is RapchaTextInserter.InsertionResult.SuccessDirect ||
                                    placed is RapchaTextInserter.InsertionResult.SuccessClipboardFallback
                                ) {
                                    committed = text
                                }
                                placed
                            }
                        }
                    }
                }
                val pieces = com.princeniithompson.rapcha.service.floating.Stage2TranscriptChunker.chunks(rawTranscript)
                val flashLiteClient = com.princeniithompson.rapcha.service.floating.FlashLiteTextPolishClient(
                    context = this@FloatingBubbleService
                )
                var assembled = ""
                var livePieces = 0
                var failedPieces = 0
                var suspiciousPieces = 0
                var lastError: String? = null
                var lastTimedOut = false
                val stage1Dropped = sessionManager.consumeStage1Drop()
                for ((index, piece) in pieces.withIndex()) {
                    val piecePaste = withContext(Dispatchers.IO) {
                        Stage2LivePolish.execute(
                            rawTranscript = piece,
                            category = locked.stage2Category,
                            appName = locked.appName,
                            protectedTerms = CustomVocabularyRepository.getVocabulary(),
                            stage1Dropped = stage1Dropped,
                            apiKey = apiKey,
                            model = flashLiteClient,
                            systemInstruction = instruction,
                            partIndex = index + 1,
                            partCount = pieces.size,
                            sourceChars = rawTranscript.length,
                            logHeader = index == 0,
                            modelName = com.princeniithompson.rapcha.config.RapchaConfig.STAGE2_FLASH_LITE_MODEL,
                            log = { type, message ->
                                AppLogRepository.logEvent(DiagnosticSource.BUBBLE, type, message)
                            }
                        )
                    }
                    val pieceText = if (piecePaste.outcome == Stage2Outcome.SUCCESS &&
                        !PolishDelivery.suspiciousPiece(piece.length, piecePaste.text.length)
                    ) {
                        livePieces += 1
                        piecePaste.text
                    } else if (piecePaste.outcome == Stage2Outcome.SUCCESS) {
                        suspiciousPieces += 1
                        AppLogRepository.logEvent(
                            DiagnosticSource.BUBBLE,
                            DiagnosticType.POLISH_TRACE,
                            "suspicious_output=yes source_chars=${piece.length} output_chars=${piecePaste.text.length} action=kept_raw chunk=${index + 1}/${pieces.size}"
                        )
                        piece
                    } else {
                        failedPieces += 1
                        lastError = piecePaste.error
                        lastTimedOut = piecePaste.timedOut
                        piece
                    }
                    assembled = com.princeniithompson.rapcha.service.floating.Stage2TranscriptChunker.join(
                        assembled,
                        pieceText
                    )
                }
                val text = if (livePieces == 0 && failedPieces > 0) {
                    AppLogRepository.logEvent(
                        DiagnosticSource.BUBBLE,
                        DiagnosticType.STAGE2_LIVE_FALLBACK,
                        "fallback=stage1_smart model=${com.princeniithompson.rapcha.config.RapchaConfig.STAGE2_FLASH_LITE_MODEL} category=${locked.stage2Category} pieces=${pieces.size} reason=${lastError?.take(120).orEmpty()}"
                    )
                    Stage2LivePolish.rawOnFailure(rawTranscript)
                } else {
                    if (failedPieces > 0) {
                        AppLogRepository.logEvent(
                            DiagnosticSource.BUBBLE,
                            DiagnosticType.STAGE2_LIVE_FALLBACK,
                            "fallback=partial live=$livePieces failed=$failedPieces timed_out=${lastTimedOut} category=${locked.stage2Category}"
                        )
                    }
                    assembled
                }
                val liveWhole = livePieces > 0 && failedPieces == 0 && suspiciousPieces == 0 &&
                    locked.stage2Category == com.princeniithompson.rapcha.service.floating.Stage2Category.AI
                refreshDelivery()
                val id = liveSessionId ?: java.util.UUID.randomUUID().toString()
                val keptRaw = HistoryRepository.sessionById(id)?.stage("raw")?.takeIf { it.isNotBlank() } ?: frozenRaw
                val session = VoiceSessionBuilder.captured(
                    sessionId = id,
                    timestamp = HistoryRepository.sessionById(id)?.timestamp ?: System.currentTimeMillis(),
                    appName = locked.appName,
                    threeStep = locked.threeStep,
                    rawText = keptRaw,
                    smartText = if (locked.threeStep) rawTranscript else null,
                    lyraText = if (liveWhole) text else null,
                    smartCompleted = true,
                    aiLyra = locked.stage2Category == com.princeniithompson.rapcha.service.floating.Stage2Category.AI,
                    sensitive = delivery.halt == DeliveryHalt.SENSITIVE
                )
                HistoryRepository.commitSession(session, durationSeconds = sessionManager.durationSeconds)
                if (liveWhole) {
                    CustomVocabularyRepository.recordUsageFromTranscript(text)
                }
                if (text.isNotBlank()) {
                    val placed = if (delivery.acceptsInsert()) {
                        pasteLatest(text, final = true, clipboardWhenNoField = false)
                    } else {
                        RapchaTextInserter.InsertionResult.Failed
                    }
                    val verified = placed is RapchaTextInserter.InsertionResult.SuccessDirect && fieldIsReady()
                    val copy = PolishDelivery.mayCopyFinal(delivery.halt, delivery.copied) &&
                        (placed !is RapchaTextInserter.InsertionResult.SuccessDirect || !verified)
                    val delivered = if (copy) {
                        delivery.markCopied()
                        val copied = RapchaTextInserter.getInstance(this@FloatingBubbleService).copyNoField(text)
                        AppLogRepository.logEvent(
                            DiagnosticSource.BUBBLE,
                            DiagnosticType.DELIVERY,
                            "clipboard=once halt=${delivery.halt.name} outcome=${if (copied is RapchaTextInserter.InsertionResult.SuccessClipboardFallback) "set" else "failed"} verified=no"
                        )
                        copied
                    } else {
                        AppLogRepository.logEvent(
                            DiagnosticSource.BUBBLE,
                            DiagnosticType.DELIVERY,
                            "clipboard=skipped halt=${delivery.halt.name} verified=unverified field_present=${if (verified) "yes" else "no"}"
                        )
                        placed
                    }
                    if (delivered is RapchaTextInserter.InsertionResult.SuccessDirect ||
                        delivered is RapchaTextInserter.InsertionResult.SuccessClipboardFallback
                    ) {
                        VocabSuggestions.onConfirmedPlacement(this@FloatingBubbleService, text, delivered)
                    }
                    val notice = when {
                        delivery.halt == DeliveryHalt.CANCELLED -> null
                        delivery.halt == DeliveryHalt.SENSITIVE -> "Polish is in History."
                        delivered is RapchaTextInserter.InsertionResult.SuccessClipboardFallback ->
                            "Full result copied. The text field could not be confirmed."
                        livePieces > 0 && verified -> "Polished ✨"
                        livePieces > 0 -> "Polished text is in History."
                        else -> null
                    }
                    if (notice != null) {
                        Toast.makeText(this@FloatingBubbleService, notice, Toast.LENGTH_LONG).show()
                    }
                    val rated = if (delivery.halt == DeliveryHalt.SENSITIVE && !session.sensitive) {
                        val hidden = session.copy(sensitive = true)
                        HistoryRepository.replaceVoiceSession(session.sessionId, hidden)
                        hidden
                    } else {
                        session
                    }
                    if (LyraReviewPolicy.shouldPrompt(rated)) {
                        AppLogRepository.logEvent(
                            DiagnosticSource.BUBBLE,
                            DiagnosticType.POLISH_TRACE,
                            "lyra_review=prompted session=${rated.sessionId} mode=${if (locked.threeStep) "three-step" else "two-step"} app=${locked.appName}"
                        )
                        LyraReviewPrompt.show(this@FloatingBubbleService, rated)
                    }
                }
                AppLogRepository.logEvent(
                    DiagnosticSource.BUBBLE,
                    DiagnosticType.POLISH_TRACE,
                    "model_complete=yes live_pieces=$livePieces failed_pieces=$failedPieces stage1_chars=${rawTranscript.length} lyra_chars=${text.length} frozen_raw_chars=${frozenRaw.length} stage1_sha256=${com.princeniithompson.rapcha.data.TextFingerprint.sha256(rawTranscript)} three_step=${if (locked.threeStep) "yes" else "no"} halt=${delivery.halt.name}"
                )
            } finally {
                if (activeDelivery === delivery) activeDelivery = null
                overlayWindowManager.overlayPolishing.value = false
                resetAndCollapse()
            }
        }
    }

    private fun persistFrozenRaw(text: String) {
        val section = FloatingBubbleManager.lockedPolishSection.value ?: return
        if (section.mode != SectionPolishMode.TWO_STEP_LIVE) return
        val id = liveSessionId ?: return
        val raw = text.trim()
        if (raw.isEmpty()) return
        if (FloatingBubbleManager.isCurrentAppSensitive.value) {
            AppLogRepository.logEvent(
                DiagnosticSource.BUBBLE,
                DiagnosticType.POLISH_TRACE,
                "prefinal_frozen skipped=sensitive before=activity_end"
            )
            return
        }
        val existing = HistoryRepository.sessionById(id)
        HistoryRepository.commitSession(
            VoiceSessionBuilder.captured(
                sessionId = id,
                timestamp = existing?.timestamp ?: System.currentTimeMillis(),
                appName = section.appName,
                threeStep = section.threeStep,
                rawText = raw,
                smartText = null,
                lyraText = null,
                smartCompleted = false,
                aiLyra = section.stage2Category == com.princeniithompson.rapcha.service.floating.Stage2Category.AI,
                sensitive = false
            ),
            durationSeconds = if (::sessionManager.isInitialized) sessionManager.durationSeconds else 0
        )
        AppLogRepository.logEvent(
            DiagnosticSource.BUBBLE,
            DiagnosticType.POLISH_TRACE,
            "prefinal_frozen before=activity_end ${com.princeniithompson.rapcha.data.TextFingerprint.describe("raw", raw)}"
        )
    }

    private fun onCancelClicked() {
        lyraFinishGeneration.incrementAndGet()
        activeDelivery?.note(DeliveryHalt.CANCELLED)
        if (FloatingBubbleManager.offlineRetry.value != null || offlineRetry.isHolding()) {
            onDiscardClicked()
            return
        }
        val durationAtEnd = sessionManager.durationSeconds
        val chunksAtEnd = sessionManager.sessionChunksSent

        overlayWindowManager.overlayPendingFinalizing.value = false
        isPendingInjectionOnSmartCompletion = false
        pendingCompletionTimeoutJob?.cancel()
        pendingCompletionTimeoutJob = null

        val wasRecording = sessionManager.isRecording
        if (wasRecording) {
            stopVoiceTyping(endedReason = "user_cancelled", discardRetryClip = true)
        } else if (::sessionManager.isInitialized) {
            sessionManager.settleRetryClip(discard = true)
            sessionManager.logSessionEndOnce(
                "ended_reason: user_cancelled | Duration: ${durationAtEnd}s, Chunks: $chunksAtEnd"
            )
        }

        offlineRetry.clearHold()
        Toast.makeText(this, "Voice typing cancelled", Toast.LENGTH_SHORT).show()
        resetAndCollapse()
    }

    private fun resetAndCollapse() {
        activeDetectionJob?.cancel()
        activeDetectionJob = null
        AppDetector.clearSessionCache()

        FloatingBubbleManager.unlockSessionContext()
        FloatingBubbleManager.unlockSection()
        overlayWindowManager.overlayPendingFinalizing.value = false
        overlayWindowManager.overlayRecording.value = false
        isPendingInjectionOnSmartCompletion = false
        pendingCompletionTimeoutJob?.cancel()
        pendingCompletionTimeoutJob = null

        sessionManager.clearTranscripts()
        connectionLostHapticSent = false
        sessionLimitAutoFinish = false
        overlayWindowManager.overlayPreviewPhase.value = InterimPreviewMonitor.Phase.LISTENING
        overlayWindowManager.overlayPolishingMessage.value = null
        overlayWindowManager.collapsePanel()
        overlayWindowManager.updateOverlayVisibility(isSessionActive = false)
    }

    fun onKeyboardVisibilityChanged(isVisible: Boolean) {
        if (!componentsReady()) return
        if (isVisible && FloatingBubbleManager.offlineRetry.value != null) {
            overlayWindowManager.expandToFullSize()
            overlayWindowManager.expandPanel()
        }
        overlayWindowManager.onKeyboardVisibilityChanged(isVisible, isBubbleSessionActive())
    }

    fun onFieldFocusChanged(isFocused: Boolean) {
        if (!componentsReady()) return
        if (isFocused) {
            if (fieldFocusRevealsBubble(FloatingBubbleManager.isKeyboardVisible.value)) {
                overlayWindowManager.showImmediately()
            }
            return
        }
        if (keyboardRecheckShouldHide(FloatingBubbleManager.isKeyboardVisible.value, isBubbleSessionActive())) {
            overlayWindowManager.onKeyboardVisibilityChanged(isVisible = false, isSessionActive = false)
        }
    }

    /**
     * Fired by the ~300ms keyboard re-check. A focused field is not a reason to stay visible.
     */
    fun onKeyboardWatch(imeVisible: Boolean) {
        if (!componentsReady()) return
        if (keyboardRecheckShouldHide(imeVisible, isBubbleSessionActive())) {
            FloatingBubbleManager.noteBubbleHideSignal()
            overlayWindowManager.onKeyboardVisibilityChanged(isVisible = false, isSessionActive = false)
        }
    }

    private fun getEffectiveApiKey(): String {
        return ApiConfig.resolveApiKey(this)
    }

    private fun isBrowserOrContainerPackage(pkg: String): Boolean {
        val lower = pkg.lowercase(java.util.Locale.US)
        return lower == "com.google.android.googlequicksearchbox" ||
                lower.startsWith("org.chromium.webapk") ||
                lower.contains(".webapk") ||
                lower == "com.android.chrome" ||
                lower == "com.chrome.beta" ||
                lower == "com.chrome.dev" ||
                lower == "com.sec.android.app.sbrowser" ||
                lower == "com.microsoft.emmx" ||
                lower == "org.mozilla.firefox" ||
                lower == "com.brave.browser" ||
                lower == "com.opera.browser" ||
                lower.contains("chrome") ||
                lower.contains("browser") ||
                lower.contains("firefox") ||
                lower.contains("webkit")
    }

    private fun collectBrowserAndContainerEvidence(
        pkg: String,
        a11y: RapchaAccessibilityService?
    ): String? {
        if (a11y == null) return null
        val sb = StringBuilder()

        // 1. Window title
        val windowTitle = try {
            a11y.getActiveApplicationWindow()?.title?.toString()
        } catch (_: Throwable) {
            null
        }
        if (!windowTitle.isNullOrBlank()) {
            sb.append("Window title: ").append(windowTitle.trim())
        }

        // 2. Activity / class name
        val className = a11y.lastSeenClassName
        if (!className.isNullOrBlank()) {
            if (sb.isNotEmpty()) sb.append("\n")
            sb.append("Activity: ").append(className.trim())
        }

        // 3. Root node for URL and visible text samples
        val root = try {
            a11y.rootInActiveWindow
        } catch (_: Throwable) {
            null
        }

        if (root != null) {
            val url = findBrowserUrl(root)
            if (!url.isNullOrBlank()) {
                if (sb.isNotEmpty()) sb.append("\n")
                sb.append("URL: ").append(url.trim())
            }

            val visibleTexts = mutableListOf<String>()
            collectSampleVisibleTexts(root, visibleTexts, maxCount = 10, depth = 0, maxDepth = 6)
            if (visibleTexts.isNotEmpty()) {
                if (sb.isNotEmpty()) sb.append("\n")
                sb.append("Visible text samples:\n")
                visibleTexts.forEach { text ->
                    sb.append("- ").append(text).append("\n")
                }
            }
        }

        return if (sb.isNotEmpty()) sb.toString().trim() else null
    }

    private fun findBrowserUrl(rootNode: AccessibilityNodeInfo): String? {
        val urlViewIds = listOf(
            "com.android.chrome:id/url_bar",
            "com.chrome.beta:id/url_bar",
            "com.chrome.dev:id/url_bar",
            "com.sec.android.app.sbrowser:id/location_bar_edit_text",
            "com.microsoft.emmx:id/url_bar",
            "org.mozilla.firefox:id/url_bar_title",
            "url_bar",
            "location_bar",
            "search_box_text"
        )
        for (id in urlViewIds) {
            try {
                val nodes = rootNode.findAccessibilityNodeInfosByViewId(id)
                if (!nodes.isNullOrEmpty()) {
                    for (node in nodes) {
                        val text = node.text?.toString()?.trim()
                        if (!text.isNullOrBlank()) return text
                    }
                }
            } catch (_: Throwable) {}
        }
        return searchNodeForUrl(rootNode, 0, 6)
    }

    private fun searchNodeForUrl(node: AccessibilityNodeInfo?, depth: Int, maxDepth: Int): String? {
        if (node == null || depth > maxDepth) return null
        val text = node.text?.toString()?.trim() ?: ""
        if (text.isNotBlank()) {
            val lower = text.lowercase(java.util.Locale.US)
            if (lower.contains("aistudio") || lower.contains("ais-dev-") || lower.contains("ais-pre-") ||
                lower.contains(".google.com") || lower.contains(".ai") || lower.contains(".com/") ||
                lower.startsWith("http://") || lower.startsWith("https://")) {
                return text
            }
        }
        for (i in 0 until node.childCount) {
            val child = try { node.getChild(i) } catch (_: Throwable) { null } ?: continue
            val found = searchNodeForUrl(child, depth + 1, maxDepth)
            if (found != null) return found
        }
        return null
    }

    private fun collectSampleVisibleTexts(
        node: AccessibilityNodeInfo?,
        collected: MutableList<String>,
        maxCount: Int,
        depth: Int,
        maxDepth: Int
    ) {
        if (node == null || depth > maxDepth || collected.size >= maxCount) return
        val text = node.text?.toString()?.trim()
        val desc = node.contentDescription?.toString()?.trim()
        val candidate = when {
            !text.isNullOrBlank() && text.length > 2 -> text
            !desc.isNullOrBlank() && desc.length > 2 -> desc
            else -> null
        }
        if (candidate != null && !collected.contains(candidate)) {
            if (candidate.length <= 120) {
                collected.add(candidate)
            }
        }
        for (i in 0 until node.childCount) {
            if (collected.size >= maxCount) break
            val child = try { node.getChild(i) } catch (_: Throwable) { null } ?: continue
            collectSampleVisibleTexts(child, collected, maxCount, depth + 1, maxDepth)
        }
    }

    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        com.princeniithompson.rapcha.diagnostics.BubbleHealthLog.record(
            context = this,
            type = DiagnosticType.TRIM_MEMORY,
            message = "level=$level session_active=${isBubbleSessionActive()}"
        )
        com.princeniithompson.rapcha.service.floating.RenderBudget.onTrimMemory(this, level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
            level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE
        ) {
            AppDetector.clearCache()
            com.princeniithompson.rapcha.diagnostics.BubbleHealthLog.markUncleanShutdownPossible(this, true)
        } else if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            AppDetector.clearSessionCache()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "FloatingBubbleService onDestroy")

        watchdogJob?.cancel()
        watchdogJob = null
        if (screenReceiverRegistered) {
            try { unregisterReceiver(screenReceiver) } catch (_: Throwable) {}
            screenReceiverRegistered = false
        }

        com.princeniithompson.rapcha.diagnostics.BubbleHealthLog.markServiceRunning(this, false)
        val stillEnabled = FloatingBubbleManager.isBubbleEnabledSync(this) &&
            FloatingBubbleManager.canDrawOverlays(this)
        if (stillEnabled) {
            com.princeniithompson.rapcha.diagnostics.BubbleHealthLog.recordLastStopReason(
                this,
                com.princeniithompson.rapcha.diagnostics.BubbleHealthLog.STOP_UNEXPECTED
            )
            com.princeniithompson.rapcha.diagnostics.BubbleHealthLog.recordHideReason(
                this,
                com.princeniithompson.rapcha.diagnostics.BubbleHealthLog.HIDE_SERVICE_DESTROYED
            )
            BubbleRecoveryJobScheduler.schedule(this, RecoveryJobPolicy.MIN_LATENCY_MS)
        } else {
            com.princeniithompson.rapcha.diagnostics.BubbleHealthLog.recordLastStopReason(
                this,
                com.princeniithompson.rapcha.diagnostics.BubbleHealthLog.STOP_INTENTIONAL
            )
        }

        activeDetectionJob?.cancel()
        activeDetectionJob = null
        AppDetector.clearCache()

        networkWatcher?.stop()
        networkWatcher = null
        DictationAlerts.cancel(this, DictationAlerts.RETRY_READY_NOTIFICATION_ID)
        polishCoordinator.cancel()
        isPendingInjectionOnSmartCompletion = false
        pendingCompletionTimeoutJob?.cancel()
        pendingCompletionTimeoutJob = null
        fgsDowngradeJob?.cancel()
        fgsDowngradeJob = null

        if (::sessionManager.isInitialized) {
            stopVoiceTyping()
            sessionManager.release()
        }
        FloatingBubbleManager.setNetworkProblem(false)
        FloatingBubbleManager.onOfflineRetry = null
        FloatingBubbleManager.onOfflineDiscard = null
        FloatingBubbleManager.setOfflineRetry(null)
        if (::overlayWindowManager.isInitialized) {
            overlayWindowManager.onDestroy()
        }
        serviceScope.cancel()

        if (instance == this) {
            instance = null
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        val enabled = FloatingBubbleManager.isBubbleEnabledSync(this)
        val hasOverlay = FloatingBubbleManager.canDrawOverlays(this)
        val recording = ::sessionManager.isInitialized && sessionManager.isRecording
        Log.d(TAG, "FloatingBubbleService onTaskRemoved: enabled=$enabled recording=$recording")
        com.princeniithompson.rapcha.diagnostics.BubbleHealthLog.record(
            context = this,
            type = DiagnosticType.TASK_REMOVED,
            message = "was_recording=$recording keep_alive=$enabled"
        )
        if (TaskRemovalPolicy.shouldReleaseSessionOnTaskRemoved(enabled)) {
            if (::sessionManager.isInitialized) {
                stopVoiceTyping()
                sessionManager.release()
            }
            FloatingBubbleManager.setNetworkProblem(false)
            return
        }
        if (!recording) {
            enterSpecialUseForeground()
        }
        if (TaskRemovalPolicy.shouldRebuildOverlayOnTaskRemoved(enabled, hasOverlay)) {
            rebuildOverlayIfNeeded("task_removed")
            serviceScope.launch(Dispatchers.Main) {
                delay(300L)
                if (FloatingBubbleManager.isBubbleEnabledSync(this@FloatingBubbleService) &&
                    FloatingBubbleManager.canDrawOverlays(this@FloatingBubbleService)
                ) {
                    rebuildOverlayIfNeeded("task_removed_settled")
                }
            }
        }
        BubbleRecoveryJobScheduler.schedule(this, RecoveryJobPolicy.DEFAULT_DELAY_MS)
        if (!recording && hasOverlay) {
            FloatingBubbleManager.startBubbleService(this, source = "task_removed_keepalive")
        }
    }
}
