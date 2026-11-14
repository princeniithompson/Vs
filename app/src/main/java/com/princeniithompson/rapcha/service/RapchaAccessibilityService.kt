package com.princeniithompson.rapcha.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.princeniithompson.rapcha.data.AppLogRepository
import com.princeniithompson.rapcha.data.DiagnosticSource
import com.princeniithompson.rapcha.data.DiagnosticType
import com.princeniithompson.rapcha.diagnostics.BubbleHealthLog
import com.princeniithompson.rapcha.service.floating.FloatingTextInjector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

open class RapchaAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "RapchaAccessService"
        const val VERIFICATION_ATTEMPTS = 2
        const val VERIFY_WAIT_MS = 150L
        /** Verification may poll, but a slow read must not be treated as a failed insert. */
        const val VERIFY_POLL_BUDGET_MS = 600L
        const val KEYBOARD_WATCH_MS = KeyboardSettleStateMachine.KEYBOARD_WATCH_MS
        const val EVAL_THROTTLE_MS = 85L
        var instance: RapchaAccessibilityService? = null
            internal set

        @Volatile
        var lastConnectedElapsedMs: Long = 0L
            internal set

        @Volatile
        var lastEventElapsedMs: Long = 0L
            internal set

        @Volatile
        private var bubbleBound = false

        /** v23: true while this accessibility service holds a binding to FloatingBubbleService. */
        fun isBubbleBound(): Boolean = bubbleBound
    }

    private var bubbleConnection: android.content.ServiceConnection? = null

    /**
     * v23: bind FloatingBubbleService from this (running, system-bound) accessibility service.
     * Unlike startForegroundService from the background, Android 12+ never refuses this, and the
     * bubble service then lives as long as this binding. Idempotent; main thread.
     */
    fun bindBubbleService(source: String): Boolean {
        if (bubbleConnection != null) return true
        if (!FloatingBubbleManager.isBubbleEnabledSync(this) || !FloatingBubbleManager.canDrawOverlays(this)) {
            return false
        }
        val connection = object : android.content.ServiceConnection {
            override fun onServiceConnected(name: android.content.ComponentName?, service: android.os.IBinder?) {
                Log.d(TAG, "Bubble service bound ($source)")
            }

            override fun onServiceDisconnected(name: android.content.ComponentName?) {
                Log.w(TAG, "Bubble service disconnected")
            }

            override fun onBindingDied(name: android.content.ComponentName?) {
                // The binding cannot recover by itself; drop it and bind again shortly.
                BubbleHealthLog.record(this@RapchaAccessibilityService, DiagnosticType.WARNING, "bubble_binding_died")
                mainHandler.post {
                    unbindBubbleService("binding_died")
                    if (instance === this@RapchaAccessibilityService) {
                        bindBubbleService("rebind_after_death")
                    }
                }
            }
        }
        val ok = try {
            bindService(
                Intent(this, FloatingBubbleService::class.java),
                connection,
                Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT
            )
        } catch (t: Throwable) {
            BubbleHealthLog.record(
                this,
                DiagnosticType.FGS_REFUSED,
                "site=a11y_bind source=$source kind=${t.javaClass.simpleName} detail=${t.message?.take(120)}"
            )
            false
        }
        if (ok) {
            bubbleConnection = connection
            bubbleBound = true
        } else {
            try { unbindService(connection) } catch (_: Throwable) {}
        }
        return ok
    }

    fun unbindBubbleService(reason: String) {
        val connection = bubbleConnection ?: return
        bubbleConnection = null
        bubbleBound = false
        try {
            unbindService(connection)
        } catch (t: Throwable) {
            Log.w(TAG, "unbindBubbleService($reason) failed: ${t.javaClass.simpleName}")
        }
    }

    /** v23: keyboard state already computed on the eval thread; no binder call on the caller. */
    fun isImeVisibleCached(): Boolean = lastImeWindowPresent || lastReportedKeyboardVisible == true

    /** v23: ask the eval thread for a fresh keyboard/focus evaluation (non-blocking). */
    fun requestEvaluation() {
        scheduleCoalescedEvaluation(delayMs = 0L)
    }

    @Volatile
    private var lastFocusedEditableNode: AccessibilityNodeInfo? = null

    @Volatile
    private var lastEditableFocusElapsedMs: Long = 0L

    @Volatile
    var lastSeenClassName: String? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val contextExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "rapcha-foreground").apply { isDaemon = true }
    }
    private val contextScheduler = ForegroundContextScheduler(
        postDelayed = { runnable, delayMs -> mainHandler.postDelayed(runnable, delayMs) },
        cancel = { runnable -> mainHandler.removeCallbacks(runnable) },
        execute = { runnable -> contextExecutor.execute(runnable) },
        publishOnMain = { runnable -> mainHandler.post(runnable) },
        evaluate = { packageName, className, windowTitle ->
            evaluateForeground(packageName, className, windowTitle)
        }
    ).also { scheduler ->
        scheduler.onResult = { packageName, evaluation -> publishForeground(packageName, evaluation) }
    }

    private val keyboardSettle = KeyboardSettleStateMachine()
    private val evalLock = Any()
    private val evalQueued = AtomicBoolean(false)
    private var evalThread: HandlerThread? = null
    private var evalHandler: Handler? = null
    private var lastEvalCompletedElapsedMs = 0L

    private var pendingHasWindowEvent = false
    private var pendingWindowEventType = 0
    private var pendingWindowPkg: String? = null
    private var pendingWindowClass: String? = null
    private var pendingWindowTitle: String? = null
    private var pendingFocusedNode: AccessibilityNodeInfo? = null

    private val evalRunnable = Runnable { runCoalescedEvaluation() }
    private val keyboardWatchRunnable = Runnable { scheduleCoalescedEvaluation(delayMs = 0L) }
    private val secondKeyboardWatchRunnable = Runnable { scheduleCoalescedEvaluation(delayMs = 0L) }

    private var receiversRegistered = false

    private var stallProbePostedAt = 0L
    private var screenInteractive = true
    private val packageLabelCache = ConcurrentHashMap<String, String>()
    @Volatile
    private var lastImeWindowPresent = false
    private val stallProbe = Runnable {
        val lateBy = MainThreadStall.latenessMs(stallProbePostedAt, SystemClock.uptimeMillis())
        if (MainThreadStall.shouldReport(lateBy)) {
            AppLogRepository.logEvent(
                DiagnosticSource.BUBBLE,
                DiagnosticType.MAIN_THREAD_STALL,
                "late_ms=$lateBy"
            )
        }
        armStallProbe()
    }

    private val screenReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    screenInteractive = false
                    mainHandler.removeCallbacks(stallProbe)
                    lastFocusedEditableNode = null
                    lastEditableFocusElapsedMs = 0L
                    lastImeWindowPresent = false
                    keyboardSettle.reset(false)
                    if (lastReportedKeyboardVisible != false) {
                        lastReportedKeyboardVisible = false
                        FloatingBubbleManager.notifyKeyboardVisibility(false)
                        FloatingBubbleManager.notifyFieldFocus(false)
                    }
                }
                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> {
                    screenInteractive = isScreenInteractive()
                    if (screenInteractive) {
                        armStallProbe()
                    }
                    scheduleCoalescedEvaluation(delayMs = 0L)
                }
            }
        }
    }

    private val packageReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: android.content.Intent?) {
            val appCtx = context?.applicationContext ?: context ?: return
            contextExecutor.execute {
                AppDetector.refreshWebApkInventory(appCtx)
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        setupServiceConnection(reason = "onServiceConnected")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Log.w(TAG, "RapchaAccessibilityService onUnbind")
        teardownConnectionState(clearEvalWorker = true)
        A11yWatchdog.onServiceUnbound(this)
        return true
    }

    override fun onRebind(intent: Intent?) {
        super.onRebind(intent)
        Log.d(TAG, "RapchaAccessibilityService onRebind")
        setupServiceConnection(reason = "onRebind")
    }

    private fun setupServiceConnection(reason: String) {
        instance = this
        lastConnectedElapsedMs = SystemClock.elapsedRealtime()
        FloatingBubbleManager.setAccessibilityConnected(true)
        A11yWatchdog.onServiceBound(this)
        Log.d(TAG, "RapchaAccessibilityService active ($reason)")

        val appCtx = applicationContext ?: this
        // v24 Lite: no app detection, so AppDetector is never loaded.
        if (!com.princeniithompson.rapcha.core.LiteMode.enabled) contextExecutor.execute {
            AppDetector.init(appCtx)
        }
        ensureEvalWorker()
        registerServiceReceivers()

        if (serviceInfo == null) {
            val info = AccessibilityServiceInfo()
            val targetEventTypes = AccessibilityEvent.TYPE_VIEW_FOCUSED or
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOWS_CHANGED

            var targetFlags = AccessibilityServiceInfo.DEFAULT or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                targetFlags = targetFlags or AccessibilityServiceInfo.FLAG_INPUT_METHOD_EDITOR
            }

            info.eventTypes = targetEventTypes
            info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            info.flags = targetFlags
            info.notificationTimeout = 50
            serviceInfo = info
        }

        screenInteractive = isScreenInteractive()
        armStallProbe()
        scheduleCoalescedEvaluation(delayMs = 0L)

        // Start or recover bubble service without swapping its WindowManager
        if (FloatingBubbleManager.isBubbleEnabledSync(this)) {
            if (FloatingBubbleService.instance == null &&
                BubbleHealthLog.lastStopReason(this) == BubbleHealthLog.STOP_UNEXPECTED
            ) {
                FloatingBubbleManager.startBubbleService(this, source = "a11y_recover_$reason")
            } else {
                FloatingBubbleManager.startEnabledBubble(this, source = reason)
            }
        }
    }

    private fun ensureEvalWorker() {
        synchronized(evalLock) {
            if (evalThread == null || evalThread?.isAlive != true) {
                val thread = HandlerThread("rapcha-a11y-eval", Process.THREAD_PRIORITY_DEFAULT).apply {
                    isDaemon = true
                    start()
                }
                evalThread = thread
                evalHandler = Handler(thread.looper)
            }
        }
    }

    private fun registerServiceReceivers() {
        if (receiversRegistered) return
        // v24 Lite: the package receiver only refreshes app detection's WebAPK list.
        if (!com.princeniithompson.rapcha.core.LiteMode.enabled) try {
            val packageFilter = android.content.IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addAction(Intent.ACTION_PACKAGE_REPLACED)
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
        try {
            val screenFilter = android.content.IntentFilter(Intent.ACTION_SCREEN_OFF).apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(screenReceiver, screenFilter, RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(screenReceiver, screenFilter)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error registering screen receiver: ${e.message}")
            BubbleHealthLog.record(this, DiagnosticType.WARNING, "a11y_screen_receiver_failed kind=${e.javaClass.simpleName}")
        }
        receiversRegistered = true
    }

    private var lastReportedKeyboardVisible: Boolean? = null
    private var enabledImePackages: Set<String> = emptySet()
    private var enabledImePackagesAt = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        lastEventElapsedMs = SystemClock.elapsedRealtime()
        val type = event.eventType
        val pkg = event.packageName?.toString()
        val cls = event.className?.toString()
        val title = if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            type == AccessibilityEvent.TYPE_WINDOWS_CHANGED
        ) {
            event.text?.firstOrNull()?.toString()
        } else {
            null
        }
        val focusSource = if (type == AccessibilityEvent.TYPE_VIEW_FOCUSED) {
            try {
                event.source
            } catch (_: Throwable) {
                null
            }
        } else {
            null
        }

        synchronized(evalLock) {
            if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                type == AccessibilityEvent.TYPE_WINDOWS_CHANGED
            ) {
                pendingHasWindowEvent = true
                pendingWindowEventType = type
                pendingWindowPkg = pkg
                if (cls != null) pendingWindowClass = cls
                if (title != null) pendingWindowTitle = title
            }
            if (focusSource != null) {
                pendingFocusedNode = focusSource
            }
        }

        val elapsedSinceLast = lastEventElapsedMs - lastEvalCompletedElapsedMs
        val delayMs = if (elapsedSinceLast in 0L until EVAL_THROTTLE_MS) {
            EVAL_THROTTLE_MS - elapsedSinceLast
        } else {
            0L
        }
        scheduleCoalescedEvaluation(delayMs)
    }

    private fun scheduleCoalescedEvaluation(delayMs: Long) {
        if (!evalQueued.compareAndSet(false, true)) return
        ensureEvalWorker()
        val handler = evalHandler
        if (handler != null) {
            handler.postDelayed(evalRunnable, delayMs)
        } else {
            evalQueued.set(false)
        }
    }

    private fun runCoalescedEvaluation() {
        evalQueued.set(false)
        val started = SystemClock.elapsedRealtime()
        val hasWindowEvent: Boolean
        val windowEventType: Int
        val windowPkg: String?
        val windowClass: String?
        val windowTitle: String?
        val focusCandidate: AccessibilityNodeInfo?

        synchronized(evalLock) {
            hasWindowEvent = pendingHasWindowEvent
            windowEventType = pendingWindowEventType
            windowPkg = pendingWindowPkg
            windowClass = pendingWindowClass
            windowTitle = pendingWindowTitle
            focusCandidate = pendingFocusedNode

            pendingHasWindowEvent = false
            pendingWindowEventType = 0
            pendingWindowPkg = null
            pendingWindowClass = null
            pendingWindowTitle = null
            pendingFocusedNode = null
        }

        try {
            val sessionProtected = FloatingBubbleService.instance?.isBubbleSessionActive() == true ||
                FloatingBubbleManager.isRecording.value
            screenInteractive = isScreenInteractive()
            val deviceLocked = isDeviceLocked()
            if (KeyboardVisibilityGuard.shouldSuppressForDeviceState(screenInteractive, deviceLocked) &&
                !sessionProtected
            ) {
                lastFocusedEditableNode = null
                lastEditableFocusElapsedMs = 0L
                lastImeWindowPresent = false
                keyboardSettle.reset(false)
                if (lastReportedKeyboardVisible != false) {
                    lastReportedKeyboardVisible = false
                    AppLogRepository.logEvent(
                        DiagnosticSource.BUBBLE,
                        DiagnosticType.KEYBOARD_HIDDEN,
                        "ime=false fallback=false suppressed=device_locked"
                    )
                    mainHandler.post {
                        FloatingBubbleManager.notifyKeyboardVisibility(false)
                        FloatingBubbleManager.notifyFieldFocus(false)
                    }
                }
                return
            }

            if (hasWindowEvent && windowEventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && windowClass != null) {
                lastSeenClassName = windowClass
            }

            // 1. Single getWindows() binder call for IME presence, app windows, and foreground resolution
            val currentWindows = try {
                windows ?: emptyList()
            } catch (_: Throwable) {
                emptyList()
            }

            var isImeWindowPresent = false
            val appWindows = ArrayList<ForegroundAppPicker.AppWindow>(currentWindows.size)
            for (window in currentWindows) {
                val wType = try {
                    window.type
                } catch (_: Throwable) {
                    continue
                }
                if (wType == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                    val imeVisible = try {
                        val bounds = Rect()
                        window.getBoundsInScreen(bounds)
                        (bounds.width() > 0 && bounds.height() > 0) || (bounds.isEmpty && window.isActive)
                    } catch (_: Throwable) {
                        true
                    }
                    if (imeVisible) {
                        isImeWindowPresent = true
                    }
                    continue
                }
                if (wType != AccessibilityWindowInfo.TYPE_APPLICATION) continue
                val isActiveOrFocused = try {
                    window.isActive || window.isFocused
                } catch (_: Throwable) {
                    false
                }
                if (!isActiveOrFocused) continue
                val rootPkg = try {
                    window.root?.packageName?.toString()
                } catch (_: Throwable) {
                    null
                } ?: continue
                appWindows.add(
                    ForegroundAppPicker.AppWindow(
                        packageName = rootPkg,
                        active = window.isActive,
                        focused = window.isFocused,
                        typeApplication = true
                    )
                )
            }

            val activeWindowIsLauncherOrIgnored = appWindows.none {
                !isForegroundNoise(it.packageName) && !ForegroundAppPicker.isLauncherPackage(it.packageName)
            }
            val wasImePresent = lastImeWindowPresent
            lastImeWindowPresent = isImeWindowPresent

            // 2. Focus candidate evaluation off the main thread (ignore SystemUI / lockscreen / hidden / launcher-without-IME)
            var focusedEditableConfirmed = false
            if (focusCandidate != null) {
                val focusPkg = try {
                    focusCandidate.packageName?.toString()
                } catch (_: Throwable) {
                    null
                }
                val visibleToUser = try {
                    focusCandidate.isVisibleToUser
                } catch (_: Throwable) {
                    true
                }
                val ignoredFocus = !focusPkg.isNullOrBlank() && (
                    isForegroundNoise(focusPkg) ||
                        (!isImeWindowPresent && ForegroundAppPicker.isLauncherPackage(focusPkg))
                    )
                val editable = visibleToUser && !ignoredFocus && try {
                    isEditableNode(focusCandidate)
                } catch (_: Throwable) {
                    false
                }
                if (editable) {
                    lastFocusedEditableNode = focusCandidate
                    lastEditableFocusElapsedMs = started
                    focusedEditableConfirmed = true
                    mainHandler.post {
                        FloatingBubbleManager.notifyFieldFocus(true)
                    }
                }
            }

            // 3. Foreground package resolution & immediate package Safe Mode hold
            var resolvedPkgForGuard: String? = null
            if (hasWindowEvent || windowPkg != null) {
                if (windowPkg != null && windowPkg != packageName && !isForegroundNoise(windowPkg)) {
                    try {
                        if (lastFocusedEditableNode?.refresh() == false) {
                            lastFocusedEditableNode = null
                        }
                    } catch (_: Throwable) {
                        lastFocusedEditableNode = null
                    }
                }
                val resolvedPkg = ForegroundAppPicker.resolve(
                    windows = appWindows,
                    eventPackage = windowPkg,
                    heldPackage = FloatingBubbleManager.currentForegroundPackage.value,
                    ignored = ::isForegroundNoise,
                    packageSensitive = ::isPackageSensitive
                )
                resolvedPkgForGuard = resolvedPkg
                if (resolvedPkg != null) {
                    holdPackageSafeModeIfNeeded(resolvedPkg)
                    if (com.princeniithompson.rapcha.core.LiteMode.enabled) {
                        publishLiteForeground(resolvedPkg)
                    } else {
                        mainHandler.post {
                            contextScheduler.submit(resolvedPkg, lastSeenClassName, windowTitle)
                        }
                    }
                }
            } else {
                resolvedPkgForGuard = ForegroundAppPicker.pick(
                    appWindows,
                    ::isForegroundNoise,
                    ::isPackageSensitive
                ) ?: FloatingBubbleManager.currentForegroundPackage.value
            }

            val focusNodePkg = try {
                lastFocusedEditableNode?.packageName?.toString()
            } catch (_: Throwable) {
                null
            }
            if (KeyboardVisibilityGuard.shouldClearFallbackFocusOnTransition(
                    wasImeVisible = wasImePresent,
                    isImeVisible = isImeWindowPresent,
                    focusEventJustConfirmed = focusedEditableConfirmed,
                    activeWindowIsLauncherOrIgnored = activeWindowIsLauncherOrIgnored,
                    focusNodePackage = focusNodePkg,
                    activePackage = resolvedPkgForGuard
                )
            ) {
                lastFocusedEditableNode = null
                lastEditableFocusElapsedMs = 0L
                focusedEditableConfirmed = false
            }

            // Fallback editable refresh on worker thread only when IME window is absent and not transitioning out
            if (!isImeWindowPresent && !focusedEditableConfirmed && !wasImePresent && !activeWindowIsLauncherOrIgnored) {
                focusedEditableConfirmed = hasFocusedEditableField()
                if (focusedEditableConfirmed) {
                    if (lastEditableFocusElapsedMs == 0L) {
                        lastEditableFocusElapsedMs = started
                    }
                }
            } else if (isImeWindowPresent) {
                lastEditableFocusElapsedMs = started
            }

            // 4. Settling state machine for keyboard show/hide
            val settledVisible = keyboardSettle.evaluate(
                imeWindowPresent = isImeWindowPresent,
                focusedEditableActive = focusedEditableConfirmed,
                lastTypingOrFocusElapsedMs = lastEditableFocusElapsedMs,
                sessionProtected = sessionProtected,
                nowMs = started
            )

            val previousVisible = lastReportedKeyboardVisible
            if (previousVisible != settledVisible) {
                lastReportedKeyboardVisible = settledVisible
                val fallbackUsed = !isImeWindowPresent && settledVisible
                Log.d(
                    TAG,
                    "Keyboard visibility settled -> $settledVisible (ime=$isImeWindowPresent fallback=$fallbackUsed)"
                )
                if (settledVisible) {
                    BubbleHealthLog.recordShowOpportunity(
                        context = this,
                        trigger = if (isImeWindowPresent) "ime_window" else "fallback_editable"
                    )
                }
                AppLogRepository.logEvent(
                    DiagnosticSource.BUBBLE,
                    if (settledVisible) DiagnosticType.KEYBOARD_SHOWN else DiagnosticType.KEYBOARD_HIDDEN,
                    "ime=$isImeWindowPresent fallback=$fallbackUsed"
                )
                mainHandler.post {
                    FloatingBubbleManager.notifyKeyboardVisibility(settledVisible)
                    if (settledVisible &&
                        FloatingBubbleService.instance == null &&
                        FloatingBubbleManager.isBubbleEnabledSync(this) &&
                        FloatingBubbleManager.canDrawOverlays(this)
                    ) {
                        FloatingBubbleManager.startBubbleService(this, source = "a11y_ime_recover")
                    }
                    if (!settledVisible && !focusedEditableConfirmed) {
                        FloatingBubbleManager.notifyFieldFocus(false)
                    }
                }
            }

            mainHandler.post {
                scheduleKeyboardWatch()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Worker accessibility evaluation failed cleanly", t)
        } finally {
            lastEvalCompletedElapsedMs = SystemClock.elapsedRealtime()
            val processingMs = lastEvalCompletedElapsedMs - started
            if (processingMs >= 80L) {
                AppLogRepository.logEvent(
                    DiagnosticSource.BUBBLE,
                    DiagnosticType.A11Y_EVENT,
                    "worker_eval_ms=$processingMs"
                )
            }
        }
    }

    private fun holdPackageSafeModeIfNeeded(pkg: String) {
        if (!FloatingBubbleManager.isSmartSafeModeEnabled.value) return
        val verdict = SafeModeClassifier.classify(context = this, packageName = pkg)
        if (!SessionPlacement.holdsImmediately(verdict)) return
        mainHandler.post {
            FloatingBubbleManager.engagePackageImmediately(verdict, pkg)
        }
    }

    /**
     * v24 Lite: Safe Mode from the package name and the focused field only. No AppDetector,
     * no label lookup, no window-tree walk, no second getWindows(), no detection log.
     */
    private fun publishLiteForeground(pkg: String) {
        val verdict = if (!FloatingBubbleManager.isSmartSafeModeEnabled.value) {
            null
        } else {
            try {
                SafeModeClassifier.classifyFromNodes(
                    context = this,
                    packageName = pkg,
                    focusedNode = lastFocusedEditableNode,
                    rootNode = null
                )
            } catch (_: Throwable) {
                SafeModeClassifier.classify(context = this, packageName = pkg)
            }
        }
        mainHandler.post {
            FloatingBubbleManager.noteForegroundPackage(pkg)
            if (verdict != null) FloatingBubbleManager.submitSafeMode(verdict)
        }
    }

    private fun isPackageSensitive(pkg: String): Boolean {
        return SafeModeClassifier.isSensitiveApp(this, pkg)
    }

    /** System UI, Rapcha, name-heuristic IMEs, and the device's enabled IME list. */
    private fun isForegroundNoise(pkg: String): Boolean {
        if (AppContextResolver.isIgnoredPackage(this, pkg)) return true
        return enabledImePackages().contains(pkg.lowercase())
    }

    private fun enabledImePackages(): Set<String> {
        val now = SystemClock.uptimeMillis()
        if (enabledImePackagesAt != 0L && now - enabledImePackagesAt < 2_000L) return enabledImePackages
        enabledImePackages = try {
            EnabledImePackages.load(this)
        } catch (_: Throwable) {
            emptySet()
        }
        enabledImePackagesAt = now
        return enabledImePackages
    }

    private fun readAppWindows(): List<ForegroundAppPicker.AppWindow> {
        val current = try {
            getWindows()
        } catch (_: Throwable) {
            null
        } ?: return emptyList()
        val windows = ArrayList<ForegroundAppPicker.AppWindow>(current.size)
        for (window in current) {
            val isApp = try { window.type == AccessibilityWindowInfo.TYPE_APPLICATION } catch (_: Throwable) { false }
            if (!isApp) continue
            val activeOrFocused = try { window.isActive || window.isFocused } catch (_: Throwable) { false }
            if (!activeOrFocused) continue
            val pkg = try {
                window.root?.packageName?.toString()
            } catch (_: Throwable) {
                null
            } ?: continue
            windows.add(
                ForegroundAppPicker.AppWindow(
                    packageName = pkg,
                    active = window.isActive,
                    focused = window.isFocused,
                    typeApplication = true
                )
            )
        }
        return windows
    }

    /**
     * App detection and the Safe Mode tree walk run off the main thread.
     * A thrown node query falls back to the package list so a banking app
     * still hides the bubble. Screen titles are not a trigger.
     */
    private fun evaluateForeground(
        packageName: String,
        className: String?,
        windowTitle: String?
    ): ForegroundContextScheduler.Evaluation {
        val resolved = try {
            AppDetector.resolve(
                context = this,
                packageName = packageName,
                className = className,
                windowTitle = windowTitle
            )
        } catch (_: Throwable) {
            null
        }
        if (!packageLabelCache.containsKey(packageName)) {
            val label = try {
                packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString()
            } catch (_: Throwable) {
                ""
            }
            packageLabelCache[packageName] = label
        }
        val verdict = if (!FloatingBubbleManager.isSmartSafeModeEnabled.value) {
            SafeModeClassifier.Verdict.notSensitive()
        } else {
            try {
                val focused = try {
                    findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                } catch (_: Throwable) {
                    null
                } ?: lastFocusedEditableNode
                val root = try {
                    rootInActiveWindow
                } catch (_: Throwable) {
                    null
                }
                SafeModeClassifier.classifyFromNodes(
                    context = this,
                    packageName = packageName,
                    focusedNode = focused,
                    rootNode = root,
                    windowTitle = windowTitle,
                    resolvedAppName = resolved?.appName
                )
            } catch (_: Throwable) {
                SafeModeClassifier.classify(
                    context = this,
                    packageName = packageName,
                    resolvedAppName = resolved?.appName
                )
            }
        }
        return ForegroundContextScheduler.Evaluation(
            sensitive = verdict.sensitive,
            appName = resolved?.appName ?: packageName,
            category = resolved?.category ?: "Other",
            verdict = verdict
        )
    }

    private fun publishForeground(packageName: String, evaluation: ForegroundContextScheduler.Evaluation) {
        if (!evaluation.sensitive) {
            val live = try {
                ForegroundAppPicker.pick(readAppWindows(), ::isForegroundNoise, ::isPackageSensitive)
            } catch (_: Throwable) {
                null
            }
            if (live != null && !live.equals(packageName, ignoreCase = true) && isPackageSensitive(live)) {
                holdPackageSafeModeIfNeeded(live)
                return
            }
        }
        FloatingBubbleManager.noteForegroundPackage(packageName)
        FloatingBubbleManager.updateLearnedAppContext(evaluation.appName, evaluation.category)
        val cachedLabel = packageLabelCache[packageName]?.takeIf { it.isNotBlank() }
        com.princeniithompson.rapcha.data.AppDetectionLogRepository.logEvent(
            com.princeniithompson.rapcha.data.AppDetectionEvent(
                rawPackageName = packageName,
                rawWindowTitle = null,
                packageManagerLabel = cachedLabel,
                resolvedAppName = evaluation.appName,
                classificationSource = "CACHED_FOREGROUND",
                finalCategory = evaluation.category
            )
        )
        FloatingBubbleManager.submitSafeMode(evaluation.verdict)
    }

    private fun isScreenInteractive(): Boolean {
        val power = getSystemService(POWER_SERVICE) as? PowerManager ?: return true
        return power.isInteractive
    }

    private fun isDeviceLocked(): Boolean {
        val km = getSystemService(KEYGUARD_SERVICE) as? KeyguardManager ?: return false
        return try {
            km.isKeyguardLocked || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1 && km.isDeviceLocked)
        } catch (_: Throwable) {
            false
        }
    }

    private fun armStallProbe() {
        mainHandler.removeCallbacks(stallProbe)
        if (com.princeniithompson.rapcha.core.LiteMode.enabled) return // v24 Lite: no main-thread stall diagnostics
        if (!MainThreadStall.shouldSchedule(screenInteractive)) return
        stallProbePostedAt = SystemClock.uptimeMillis()
        mainHandler.postDelayed(stallProbe, MainThreadStall.INTERVAL_MS)
    }

    private fun scheduleKeyboardWatch() {
        mainHandler.removeCallbacks(keyboardWatchRunnable)
        mainHandler.removeCallbacks(secondKeyboardWatchRunnable)
        val bubbleMayBeShowing = FloatingBubbleManager.isKeyboardVisible.value ||
            FloatingBubbleManager.isFieldActive.value
        if (bubbleMayBeShowing) {
            mainHandler.postDelayed(keyboardWatchRunnable, KEYBOARD_WATCH_MS)
            mainHandler.postDelayed(
                secondKeyboardWatchRunnable,
                KeyboardSettleStateMachine.KEYBOARD_SECOND_CHECK_MS
            )
        }
    }

    fun isImeVisibleNow(): Boolean {
        if (KeyboardVisibilityGuard.shouldSuppressForDeviceState(isScreenInteractive(), isDeviceLocked())) {
            return false
        }
        val currentWindows = try {
            getWindows() ?: emptyList()
        } catch (_: Throwable) {
            emptyList()
        }
        val imePresent = currentWindows.any { window ->
            if (window.type != AccessibilityWindowInfo.TYPE_INPUT_METHOD) return@any false
            try {
                val bounds = Rect()
                window.getBoundsInScreen(bounds)
                (bounds.width() > 0 && bounds.height() > 0) || (bounds.isEmpty && window.isActive)
            } catch (_: Throwable) {
                true
            }
        }
        return imePresent || hasFocusedEditableField()
    }

    fun hasFocusedEditableField(): Boolean {
        val node = lastFocusedEditableNode ?: return false
        return try {
            node.refresh() && node.isFocused && node.isVisibleToUser && isEditableNode(node)
        } catch (_: Throwable) {
            false
        }
    }

    private fun isEditableNode(node: AccessibilityNodeInfo): Boolean {
        val className = node.className?.toString()
        val viewId = try { node.viewIdResourceName } catch (_: Throwable) { null }
        return EditableNodePolicy.isEditableCandidate(
            isEditable = node.isEditable,
            className = className,
            viewIdResourceName = viewId,
            supportsActionSetText = FloatingTextInjector.supportsActionSetText(node)
        )
    }

    override fun onInterrupt() {
        Log.w(TAG, "RapchaAccessibilityService interrupted")
        AppLogRepository.logEvent(
            DiagnosticSource.BUBBLE,
            DiagnosticType.WARNING,
            "RapchaAccessibilityService onInterrupt"
        )
    }

    private fun teardownConnectionState(clearEvalWorker: Boolean) {
        mainHandler.removeCallbacksAndMessages(null)
        if (clearEvalWorker) {
            synchronized(evalLock) {
                evalHandler?.removeCallbacksAndMessages(null)
                evalThread?.quitSafely()
                evalThread = null
                evalHandler = null
                evalQueued.set(false)
            }
        }
        if (instance == this) {
            instance = null
            FloatingBubbleManager.setAccessibilityConnected(false)
        }
        unbindBubbleService("a11y_teardown")
        if (receiversRegistered) {
            try { unregisterReceiver(packageReceiver) } catch (_: Exception) {}
            try { unregisterReceiver(screenReceiver) } catch (_: Exception) {}
            receiversRegistered = false
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        teardownConnectionState(clearEvalWorker = true)
        contextExecutor.shutdownNow()
        lastFocusedEditableNode = null
        Log.d(TAG, "RapchaAccessibilityService destroyed")
    }

    /**
     * Determines whether the given node contains real, genuine user text versus an empty
     * field or a placeholder/hint string.
     * Safety check: Ignores isShowingHintText when content length exceeds 15 characters,
     * ensuring that long text in apps like Chrome or WhatsApp is never identified as a hint and overwritten.
     */
    fun extractGenuineText(node: AccessibilityNodeInfo): String {
        return com.princeniithompson.rapcha.service.floating.FloatingTextInjector.extractGenuineText(node)
    }

    fun injectTextSafely(node: AccessibilityNodeInfo, dictatedText: String): Boolean {
        return com.princeniithompson.rapcha.service.floating.FloatingTextInjector.injectTextSafely(node, dictatedText)
    }

    /**
     * Injects transcribed text. The clipboard is never written here.
     *
     * P1, API 33+: the accessibility service input connection `commitText`. No clipboard.
     * P2: the headless companion IME [RapchaInputMethodService.commitText], when it is active.
     * P3: caret-aware [injectTextSafely], only when P1 and P2 are both unavailable.
     * [injectionMethod] is `NO_TARGET_NODE` only when none of those targets exist. The caller
     * may copy to the clipboard in that case alone.
     */
    open suspend fun injectTextDetailed(newText: String): com.princeniithompson.rapcha.data.InjectionEvent {
        val startTime = android.os.SystemClock.elapsedRealtime()
        val wordCount = newText.trim().split("\\s+".toRegex()).count { it.isNotBlank() }
        val rawPreview = newText.take(40)

        if (newText.isEmpty()) {
            return com.princeniithompson.rapcha.data.InjectionEvent(
                textLength = 0,
                wordCount = 0,
                rawTextPreview = "",
                finalOutcome = "FAILED",
                injectionMethod = "NONE",
                resultDetails = "SKIPPED: Text is empty"
            )
        }

        if (FloatingBubbleManager.isCurrentAppSensitive.value && !FloatingBubbleManager.allowSafeModeInjection) {
            Log.w(TAG, "injectText blocked: Smart Safe Mode is active")
            val targetPkg = try { getActivePackageName() } catch (_: Throwable) { null } ?: "Unknown"
            val appName = try {
                AppContextResolver.resolve(this, targetPkg)?.appName ?: "App"
            } catch (_: Throwable) {
                "App"
            }
            return com.princeniithompson.rapcha.data.InjectionEvent(
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

        return com.princeniithompson.rapcha.data.InjectionEvent(
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
     * Replaces a previous commitText of [previousLength] characters with [newText].
     * commitText appends, so a streamed polish update must delete that earlier
     * paste before writing the revised sentence. Returns null when neither the
     * accessibility input connection nor the companion IME is available.
     */
    open suspend fun rewriteLastCommit(previousLength: Int, newText: String): Boolean? {
        if (newText.isEmpty()) return false
        val p1 = rewriteWithAccessibilityInputConnection(previousLength, newText)
        if (p1 != null) return p1
        return rewriteWithCompanionIme(previousLength, newText)
    }

    private suspend fun rewriteWithAccessibilityInputConnection(previousLength: Int, newText: String): Boolean? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
        return withContext(Dispatchers.Main.immediate) {
            var connectionBound = false
            try {
                val connection = inputMethod?.currentInputConnection ?: return@withContext null
                connectionBound = true
                if (previousLength > 0) connection.deleteSurroundingText(previousLength, 0)
                connection.commitText(newText, 1, null)
                true
            } catch (e: Throwable) {
                if (connectionBound) {
                    Log.w(TAG, "P1 rewrite failed: ${e.message}", e)
                    false
                } else {
                    null
                }
            }
        }
    }

    private suspend fun rewriteWithCompanionIme(previousLength: Int, newText: String): Boolean? {
        if (!RapchaInputMethodService.isServiceActive) return null
        return withContext(Dispatchers.Main.immediate) {
            RapchaInputMethodService.rewriteText(previousLength, newText)
        }
    }

    /**
     * P2. Commits through the headless companion IME when that keyboard is the active one.
     * Returns null when it is not active. Runs on the main looper.
     */
    protected open suspend fun commitWithCompanionIme(text: String): Boolean? {
        if (!RapchaInputMethodService.isServiceActive) {
            Log.d(TAG, "P2 unavailable: companion IME is not active")
            return null
        }
        return withContext(Dispatchers.Main.immediate) {
            val committed = RapchaInputMethodService.commitText(text)
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
        return RapchaInputMethodService.commitText(text)
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
        // The window list beats a focused IME or a mislabeled event package.
        try {
            val picked = ForegroundAppPicker.pick(
                readAppWindows(),
                ::isForegroundNoise,
                ::isPackageSensitive
            )
            if (!picked.isNullOrBlank()) return picked
        } catch (e: Exception) {
            Log.w(TAG, "Error picking foreground window: ${e.message}")
        }

        // 1. Check direct input focus first
        try {
            val focusedNode = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            val focusedPkg = focusedNode?.packageName?.toString()
            if (!focusedPkg.isNullOrEmpty() && !isForegroundNoise(focusedPkg) &&
                !ForegroundAppPicker.isLauncherPackage(focusedPkg)
            ) {
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
    fun extractAiScreenContext(): com.princeniithompson.rapcha.data.CapturedScreenContext? {
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

        val result = com.princeniithompson.rapcha.data.CapturedScreenContext(
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
