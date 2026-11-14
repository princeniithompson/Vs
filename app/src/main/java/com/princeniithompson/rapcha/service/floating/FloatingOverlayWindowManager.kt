package com.princeniithompson.rapcha.service.floating

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import com.princeniithompson.rapcha.data.AppLogRepository
import com.princeniithompson.rapcha.data.DiagnosticSource
import com.princeniithompson.rapcha.data.DiagnosticType
import com.princeniithompson.rapcha.data.ScreenContextRepository
import com.princeniithompson.rapcha.diagnostics.BubbleHealthLog
import com.princeniithompson.rapcha.diagnostics.BubbleHealthRateLimiter
import com.princeniithompson.rapcha.service.A11yRepairKind
import com.princeniithompson.rapcha.service.A11yWatchdog
import com.princeniithompson.rapcha.service.FloatingBubbleManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Manages the overlay WindowManager, layout parameters, lifecycle, and view composition
 * for the floating voice bubble.
 *
 * Always owns a service-hosted [WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY] window
 * so accessibility rebinds or unbinds never tear down or swap the Compose hierarchy.
 */
class FloatingOverlayWindowManager(private val context: Context) {

    companion object {
        private const val TAG = "FloatingOverlayWinMgr"
        private const val SHRINK_TIMEOUT_MS = 3000L
        const val BUBBLE_ANIM_MS = 140L

        private val ATTACH_BACKOFF_MS = longArrayOf(250L, 1_000L, 3_000L, 10_000L)

        /**
         * The bubble, expanded panel, and scan preview share one window.
         * NOT_FOCUSABLE keeps a tap on Complete from taking input focus, so the editor's
         * input connection stays bound. NOT_TOUCH_MODAL lets touches outside the overlay
         * through. LAYOUT_NO_LIMITS + LAYOUT_IN_SCREEN + HARDWARE_ACCELERATED match reliable
         * overlay hosts on One UI and Android 12-16.
         */
        const val OVERLAY_WINDOW_FLAGS: Int =
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    val lifecycleOwner = FloatingOverlayLifecycleOwner()
    val dragSnapHandler = FloatingDragSnapHandler(context)

    private var overlayScope: CoroutineScope? = null
    private var windowManager: WindowManager? = null
    var overlayView: View? = null
        private set
    var layoutParams: WindowManager.LayoutParams? = null
        private set

    @Volatile
    var lastHideReason: String? = null
        private set

    private val attachRateLimiter = BubbleHealthRateLimiter(capPerWindow = 6, windowMs = 60_000L)
    private var attachRetryAttempt = 0
    private var pendingRetryRunnable: Runnable? = null

    val overlayTranscript = MutableStateFlow("")
    val overlayPreviewPhase = MutableStateFlow(com.princeniithompson.rapcha.websocket.InterimPreviewMonitor.Phase.LISTENING)
    val overlayPolishingMessage = MutableStateFlow<String?>(null)
    val overlayRecording = MutableStateFlow(false)
    val overlayElapsedSeconds = MutableStateFlow(0)
    val overlayPendingFinalizing = MutableStateFlow(false)
    val overlayPolishing = MutableStateFlow(false)
    val overlayExpanded = MutableStateFlow(false)
    val overlayShrunk = MutableStateFlow(false)
    val overlayAudioAmplitude = MutableStateFlow(0f)
    val overlayScanMode = MutableStateFlow(false)
    val overlayScanningAnimation = MutableStateFlow(false)
    val overlayScanningAppName = MutableStateFlow<String?>(null)
    val overlayScanOriginX = MutableStateFlow(0.5f)
    val overlayScanOriginY = MutableStateFlow(0.5f)
    val overlayBubbleVisible = MutableStateFlow(false)

    private val shrinkRunnable = Runnable { applyShrink() }

    val isSnappedToRight: Boolean
        get() = dragSnapHandler.isSnappedToRight

    fun isAttached(): Boolean = overlayView?.isAttachedToWindow == true

    fun isWindowVisible(): Boolean = overlayView?.visibility == View.VISIBLE

    fun shouldBeVisible(): Boolean {
        if (FloatingBubbleManager.isCurrentAppSensitive.value) return false
        return overlayRecording.value ||
            overlayExpanded.value ||
            overlayPendingFinalizing.value ||
            overlayPolishing.value ||
            overlayScanningAnimation.value ||
            FloatingBubbleManager.isKeyboardVisible.value ||
            A11yWatchdog.repairKind.value != A11yRepairKind.NONE
    }

    private fun createFontWeightSafeContext(base: Context): Context {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return base
        return try {
            val config = Configuration(base.resources.configuration)
            config.fontWeightAdjustment = 0
            base.createConfigurationContext(config)
        } catch (_: Throwable) {
            base
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    fun initOverlay(
        onRingClick: () -> Unit,
        onCancelClick: () -> Unit,
        onPolishClick: () -> Unit,
        onCompleteClick: () -> Unit,
        onLongPressBubble: () -> Unit = {},
        onScanTriggered: () -> Unit = {},
        onRepairClick: () -> Unit = {}
    ) {
        lifecycleOwner.onCreate()
        lifecycleOwner.onStart()
        lifecycleOwner.onResume()

        windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

        dragSnapHandler.loadPreferences()

        val displayMetrics = context.resources.displayMetrics
        val density = displayMetrics.density
        val screenHeight = displayMetrics.heightPixels
        val screenWidth = displayMetrics.widthPixels
        val windowSize = (BubbleChromeGeometry.IDLE_WINDOW_SIZE_DP * density).toInt()
        val windowPaddingPx = (BubbleChromeGeometry.WINDOW_PADDING_DP * density).toInt()

        val topY = (36 * density).toInt()
        val maxY = (screenHeight - (80 * density).toInt()).coerceAtLeast(topY + (60 * density).toInt())
        val edgeMarginPx = (BubbleChromeGeometry.EDGE_MARGIN_DP * density).toInt()
        val initialY = dragSnapHandler.savedY.coerceIn(topY, maxY)
        val initialX = if (dragSnapHandler.isSnappedToRight) {
            screenWidth - windowSize - edgeMarginPx + windowPaddingPx
        } else {
            edgeMarginPx - windowPaddingPx
        }

        val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        layoutParams = WindowManager.LayoutParams(
            windowSize,
            windowSize,
            windowType,
            OVERLAY_WINDOW_FLAGS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = initialX
            y = initialY
            windowAnimations = 0
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        val composeContext = createFontWeightSafeContext(context)
        val composeView = ComposeView(composeContext).apply {
            lifecycleOwner.attachToView(this)
            isHapticFeedbackEnabled = !com.princeniithompson.rapcha.core.LiteMode.enabled // v24 Lite
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnLifecycleDestroyed(lifecycleOwner))
            setContent {
                val transcript by overlayTranscript.collectAsState()
                val recording by overlayRecording.collectAsState()
                val pendingFinalizing by overlayPendingFinalizing.collectAsState()
                val polishing by overlayPolishing.collectAsState()
                val isExpanded by overlayExpanded.collectAsState()
                val isShrunk by overlayShrunk.collectAsState()
                val audioAmplitude by overlayAudioAmplitude.collectAsState()
                val isScanMode by overlayScanMode.collectAsState()
                val isScanningAnimation by overlayScanningAnimation.collectAsState()
                val scanningAppName by overlayScanningAppName.collectAsState()
                val scanOriginX by overlayScanOriginX.collectAsState()
                val scanOriginY by overlayScanOriginY.collectAsState()
                val isContextLoaded by ScreenContextRepository.isContextLoaded.collectAsState()
                val elapsedSeconds by overlayElapsedSeconds.collectAsState()
                val selectedGlowStyleId by FloatingBubbleManager.selectedGlowStyleId.collectAsState()
                val selectedFinishingStyleId by FloatingBubbleManager.selectedFinishingStyleId.collectAsState()
                val isCurrentAppSensitive by FloatingBubbleManager.isCurrentAppSensitive.collectAsState()
                val bubbleVisible by overlayBubbleVisible.collectAsState()
                val repairKind by A11yWatchdog.repairKind.collectAsState()

                FloatingOverlayContent(
                    transcript = transcript,
                    recording = recording,
                    pendingFinalizing = pendingFinalizing,
                    polishing = polishing,
                    isExpanded = isExpanded,
                    isShrunk = isShrunk,
                    audioAmplitude = audioAmplitude,
                    isScanMode = isScanMode,
                    isScanningAnimation = isScanningAnimation,
                    scanningAppName = scanningAppName,
                    scanOriginX = scanOriginX,
                    scanOriginY = scanOriginY,
                    isSnappedToRight = dragSnapHandler.isSnappedToRight,
                    isContextLoaded = isContextLoaded,
                    selectedGlowStyleId = selectedGlowStyleId,
                    elapsedSeconds = elapsedSeconds,
                    selectedFinishingStyleId = selectedFinishingStyleId,
                    isCurrentAppSensitive = isCurrentAppSensitive,
                    bubbleVisible = bubbleVisible,
                    repairKind = repairKind,
                    onRepairClick = onRepairClick,
                    onRingClick = onRingClick,
                    onCancelClick = onCancelClick,
                    onPolishClick = onPolishClick,
                    onCompleteClick = onCompleteClick,
                    onLongPressBubble = onLongPressBubble,
                    onScanTriggered = onScanTriggered,
                    onDragStart = {
                        FloatingHapticManager.trigger(context, FloatingHapticType.BUBBLE_HOLD)
                        resetInactivityTimer(keepShrunk = true)
                    },
                    onDrag = { dx, dy ->
                        val lp = this@FloatingOverlayWindowManager.layoutParams ?: return@FloatingOverlayContent
                        dragSnapHandler.handleOverlayDrag(
                            dx = dx,
                            dy = dy,
                            isExpanded = overlayExpanded.value,
                            lp = lp,
                            windowManager = windowManager,
                            overlayView = overlayView
                        )
                    },
                    onDragEnd = {
                        val lp = this@FloatingOverlayWindowManager.layoutParams ?: return@FloatingOverlayContent
                        dragSnapHandler.handleOverlayDragEnd(
                            isExpanded = overlayExpanded.value,
                            lp = lp,
                            windowManager = windowManager,
                            overlayView = overlayView,
                            onComplete = {
                                resetInactivityTimer(keepShrunk = true)
                            }
                        )
                    }
                )
            }
        }
        composeView.isFocusable = false
        composeView.isFocusableInTouchMode = false
        composeView.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        overlayView = composeView

        val showNow = shouldBeVisible()
        setViewVisibilityImmediate(composeView, showNow, reason = if (showNow) null else BubbleHealthLog.HIDE_NO_IME_AFTER_SETTLE)

        attachWithRetry("init")

        overlayScope?.cancel()
        val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        overlayScope = scope
        scope.launch {
            FloatingBubbleManager.isCurrentAppSensitive.collect { sensitive ->
                if (sensitive) {
                    hideOverlayImmediately(BubbleHealthLog.HIDE_SENSITIVE_APP)
                } else {
                    restoreOverlayVisibility(
                        FloatingBubbleManager.isKeyboardVisible.value ||
                            A11yWatchdog.repairKind.value != A11yRepairKind.NONE
                    )
                }
            }
        }
        scope.launch {
            FloatingBubbleManager.bubbleChrome.collect {
                if (overlayExpanded.value) {
                    expandPanel()
                }
            }
        }
        scope.launch {
            A11yWatchdog.repairKind.collect { kind ->
                if (kind != A11yRepairKind.NONE && !FloatingBubbleManager.isCurrentAppSensitive.value) {
                    val root = overlayView ?: return@collect
                    if (!root.isAttachedToWindow) {
                        attachWithRetry("a11y_repair")
                    }
                    revealBubble(root)
                } else if (kind == A11yRepairKind.NONE &&
                    !FloatingBubbleManager.isKeyboardVisible.value &&
                    !overlayRecording.value &&
                    !overlayExpanded.value
                ) {
                    overlayView?.let { concealBubble(it, BubbleHealthLog.HIDE_NO_IME_AFTER_SETTLE) }
                }
            }
        }

        resetInactivityTimer()
    }

    fun attachWithRetry(reason: String): Boolean {
        val view = overlayView ?: return false
        val lp = layoutParams ?: return false
        if (view.isAttachedToWindow) return true

        if (!Settings.canDrawOverlays(context)) {
            BubbleHealthLog.updateOpportunityBlockingReason("overlay_permission_missing")
            BubbleHealthLog.record(
                context = context,
                type = DiagnosticType.BUBBLE_ATTACH_FAILED,
                message = "reason=$reason kind=overlay_permission_missing"
            )
            BubbleHealthLog.recordHideReason(context, BubbleHealthLog.HIDE_OVERLAY_PERMISSION_MISSING)
            return false
        }

        val wm = windowManager
            ?: (context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager).also { windowManager = it }
            ?: return false

        val resultKind = try {
            wm.addView(view, lp)
            null
        } catch (e: IllegalStateException) {
            // View already added in this WindowManager session; clear cleanly and retry once
            try {
                wm.removeViewImmediate(view)
                wm.addView(view, lp)
                null
            } catch (retryEx: Throwable) {
                "IllegalStateException:${retryEx.javaClass.simpleName}"
            }
        } catch (e: WindowManager.BadTokenException) {
            "BadTokenException"
        } catch (e: IllegalArgumentException) {
            "IllegalArgumentException"
        } catch (e: SecurityException) {
            "SecurityException"
        } catch (e: Throwable) {
            e.javaClass.simpleName
        }

        if (resultKind == null) {
            attachRetryAttempt = 0
            cancelPendingAttachRetry()
            Log.d(TAG, "Compose overlay view attached to WindowManager (reason=$reason)")
            view.post {
                if (view.isAttachedToWindow && view.visibility == View.VISIBLE) {
                    BubbleHealthLog.confirmBubbleVisible(context, "attached_visible:$reason")
                }
            }
            return true
        }

        Log.e(TAG, "addView failed (reason=$reason, kind=$resultKind)")
        BubbleHealthLog.updateOpportunityBlockingReason("add_failed:$resultKind")
        BubbleHealthLog.record(
            context = context,
            type = DiagnosticType.BUBBLE_ATTACH_FAILED,
            message = "reason=$reason kind=$resultKind attempt=$attachRetryAttempt"
        )
        scheduleNextAttachRetry(reason)
        return false
    }

    private fun scheduleNextAttachRetry(reason: String) {
        if (!attachRateLimiter.tryAcquire()) {
            Log.w(TAG, "Attach retry cap (6/min) reached; handing over to watchdog")
            FloatingNotificationManager.postRestoreNotification(context)
            return
        }
        if (attachRetryAttempt >= ATTACH_BACKOFF_MS.size) {
            FloatingNotificationManager.postRestoreNotification(context)
            return
        }
        val delayMs = ATTACH_BACKOFF_MS[attachRetryAttempt++]
        cancelPendingAttachRetry()
        val runnable = Runnable {
            pendingRetryRunnable = null
            attachWithRetry("retry_$reason")
        }
        pendingRetryRunnable = runnable
        mainHandler.postDelayed(runnable, delayMs)
    }

    private fun cancelPendingAttachRetry() {
        pendingRetryRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingRetryRunnable = null
    }

    private fun setViewVisibilityImmediate(view: View, visible: Boolean, reason: String? = null) {
        view.visibility = if (visible) View.VISIBLE else View.GONE
        overlayBubbleVisible.value = visible
        RenderBudget.setOverlayFrameMonitoring(visible)
        setOverlayRendering(visible)
        if (!visible && reason != null) {
            lastHideReason = reason
        } else if (visible) {
            isConcealing = false
            lastHideReason = null
        }
    }

    /** v23: pause Compose frames (and every infinite animation) while the window is hidden. */
    private fun setOverlayRendering(active: Boolean) {
        val apply = {
            try {
                lifecycleOwner.setRendering(active)
            } catch (t: Throwable) {
                Log.w(TAG, "Overlay lifecycle change failed: ${t.javaClass.simpleName}")
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) apply() else mainHandler.post { apply() }
    }

    fun resetInactivityTimer(keepShrunk: Boolean = false) {
        mainHandler.removeCallbacks(shrinkRunnable)
        if (!overlayRecording.value && !overlayExpanded.value) {
            if (!keepShrunk) {
                overlayShrunk.value = false
                mainHandler.postDelayed(shrinkRunnable, SHRINK_TIMEOUT_MS)
            } else {
                if (!overlayShrunk.value) {
                    mainHandler.postDelayed(shrinkRunnable, SHRINK_TIMEOUT_MS)
                }
            }
        }
    }

    fun expandToFullSize() {
        mainHandler.removeCallbacks(shrinkRunnable)
        overlayShrunk.value = false
    }

    private fun applyShrink() {
        if (!overlayRecording.value && !overlayExpanded.value) {
            overlayShrunk.value = true
        }
    }

    fun expandPanel() {
        if (FloatingBubbleManager.isCurrentAppSensitive.value) return
        dragSnapHandler.cancel()
        overlayExpanded.value = true
        val lp = layoutParams ?: return
        val displayMetrics = context.resources.displayMetrics
        val density = displayMetrics.density
        val screenHeight = displayMetrics.heightPixels

        val topY = (36 * density).toInt()
        val maxY = (screenHeight - (320 * density).toInt()).coerceAtLeast(topY + (60 * density).toInt())

        val isCompact = FloatingBubbleManager.bubbleChrome.value == BubbleChromeStyle.COMPACT
        val edgeMarginPx = (BubbleChromeGeometry.EDGE_MARGIN_DP * density).toInt()
        val windowPaddingPx = (BubbleChromeGeometry.WINDOW_PADDING_DP * density).toInt()
        if (isCompact) {
            val windowWidth = (BubbleChromeGeometry.RECORDING_WINDOW_WIDTH_DP * density).toInt()
            val windowHeight = (BubbleChromeGeometry.RECORDING_WINDOW_HEIGHT_DP * density).toInt()
            lp.width = windowWidth
            lp.height = windowHeight
            lp.x = if (dragSnapHandler.isSnappedToRight) {
                (displayMetrics.widthPixels - windowWidth - edgeMarginPx + windowPaddingPx).coerceAtLeast(0)
            } else {
                (edgeMarginPx - windowPaddingPx).coerceAtLeast(0)
            }
        } else {
            lp.width = WindowManager.LayoutParams.MATCH_PARENT
            lp.height = WindowManager.LayoutParams.WRAP_CONTENT
            lp.x = 0
        }
        lp.gravity = Gravity.TOP or Gravity.START
        lp.flags = OVERLAY_WINDOW_FLAGS
        lp.y = dragSnapHandler.savedY.coerceIn(topY, maxY)

        if (!isAttached()) {
            attachWithRetry("expand")
        }
        try {
            windowManager?.updateViewLayout(overlayView, lp)
        } catch (e: Exception) {
            Log.e(TAG, "Error updating window layout on expand", e)
        }
    }

    fun collapsePanel() {
        val wasExpanded = overlayExpanded.value
        overlayExpanded.value = false
        val lp = layoutParams ?: return
        val displayMetrics = context.resources.displayMetrics
        val density = displayMetrics.density
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels
        val windowSize = (BubbleChromeGeometry.IDLE_WINDOW_SIZE_DP * density).toInt()
        val edgeMarginPx = (BubbleChromeGeometry.EDGE_MARGIN_DP * density).toInt()
        val windowPaddingPx = (BubbleChromeGeometry.WINDOW_PADDING_DP * density).toInt()

        val topY = (36 * density).toInt()
        val maxY = (screenHeight - (80 * density).toInt()).coerceAtLeast(topY + (60 * density).toInt())
        val targetX = if (dragSnapHandler.isSnappedToRight) {
            screenWidth - windowSize - edgeMarginPx + windowPaddingPx
        } else {
            edgeMarginPx - windowPaddingPx
        }
        val targetY = dragSnapHandler.savedY.coerceIn(topY, maxY)
        val layoutChanged = wasExpanded ||
            lp.width != windowSize ||
            lp.height != windowSize ||
            lp.x != targetX ||
            lp.y != targetY

        lp.width = windowSize
        lp.height = windowSize
        lp.gravity = Gravity.TOP or Gravity.START
        lp.flags = OVERLAY_WINDOW_FLAGS
        lp.x = targetX
        lp.y = targetY

        if (layoutChanged) {
            try {
                if (isAttached()) {
                    windowManager?.updateViewLayout(overlayView, lp)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error updating window layout on collapse", e)
            }
        }
        resetInactivityTimer()
    }

    fun showScanAnimation(appName: String?, onComplete: () -> Unit) {
        val lp = layoutParams ?: return
        val displayMetrics = context.resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels.toFloat()
        val screenHeight = displayMetrics.heightPixels.toFloat()

        val originX = if (screenWidth > 0) (lp.x.toFloat() / screenWidth).coerceIn(0.1f, 0.9f) else 0.5f
        val originY = if (screenHeight > 0) (lp.y.toFloat() / screenHeight).coerceIn(0.1f, 0.9f) else 0.5f

        overlayScanOriginX.value = originX
        overlayScanOriginY.value = originY
        overlayScanningAppName.value = appName
        overlayScanningAnimation.value = true

        lp.width = WindowManager.LayoutParams.MATCH_PARENT
        lp.height = WindowManager.LayoutParams.MATCH_PARENT
        lp.flags = OVERLAY_WINDOW_FLAGS
        lp.x = 0
        lp.y = 0

        try {
            windowManager?.updateViewLayout(overlayView, lp)
        } catch (e: Exception) {
            Log.e(TAG, "Error updating window layout for scan animation", e)
        }

        mainHandler.postDelayed({
            overlayScanningAnimation.value = false
            collapsePanel()
            onComplete()
        }, 1300L)
    }

    fun showImmediately(trigger: String = "focus_or_restore") {
        fun apply() {
            val root = overlayView ?: return
            if (!FloatingBubbleManager.isKeyboardVisible.value &&
                A11yWatchdog.repairKind.value == A11yRepairKind.NONE &&
                !overlayRecording.value &&
                !overlayExpanded.value
            ) {
                return
            }
            if (FloatingBubbleManager.isCurrentAppSensitive.value) {
                setViewVisibilityImmediate(root, false, BubbleHealthLog.HIDE_SENSITIVE_APP)
                return
            }
            if (!root.isAttachedToWindow) {
                attachWithRetry(trigger)
            }
            revealBubble(root)
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            apply()
        } else {
            mainHandler.post { apply() }
        }
    }

    fun onKeyboardVisibilityChanged(isVisible: Boolean, isSessionActive: Boolean) {
        fun apply() {
            val root = overlayView ?: return
            if (FloatingBubbleManager.isCurrentAppSensitive.value) {
                setViewVisibilityImmediate(root, false, BubbleHealthLog.HIDE_SENSITIVE_APP)
                BubbleHealthLog.cancelShowOpportunity(context, BubbleHealthLog.HIDE_SENSITIVE_APP)
                return
            }
            val repairActive = A11yWatchdog.repairKind.value != A11yRepairKind.NONE
            if (isVisible || repairActive) {
                if (!root.isAttachedToWindow) {
                    attachWithRetry("keyboard_show")
                }
                if (!overlayRecording.value && !overlayExpanded.value) {
                    collapsePanel()
                }
                expandToFullSize()
                revealBubble(root)
                resetInactivityTimer(keepShrunk = false)
            } else if (!isSessionActive) {
                collapsePanel()
                concealBubble(root, BubbleHealthLog.HIDE_NO_IME_AFTER_SETTLE)
            } else {
                setViewVisibilityImmediate(root, true, null)
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            apply()
        } else {
            mainHandler.post { apply() }
        }
    }

    private fun revealBubble(root: View) {
        isConcealing = false
        if (!root.isAttachedToWindow) {
            attachWithRetry("reveal")
        }
        if (root.visibility == View.VISIBLE && root.alpha >= 0.99f && root.scaleX >= 0.99f) {
            overlayBubbleVisible.value = true
            RenderBudget.setOverlayFrameMonitoring(true)
            setOverlayRendering(true)
            return
        }
        root.animate().cancel()
        root.alpha = 0f
        root.scaleX = 0.92f
        root.scaleY = 0.92f
        setViewVisibilityImmediate(root, true, null)
        logBubble(shown = true)
        root.post {
            if (root.isAttachedToWindow && root.visibility == View.VISIBLE) {
                BubbleHealthLog.confirmBubbleVisible(context, "reveal_visible")
            }
        }
        root.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(BUBBLE_ANIM_MS)
            .setStartDelay(0L)
            .start()
    }

    private fun concealBubble(root: View, hideReason: String) {
        if (root.visibility != View.VISIBLE || isConcealing) {
            overlayBubbleVisible.value = false
            RenderBudget.setOverlayFrameMonitoring(false)
            return
        }
        isConcealing = true
        root.animate().cancel()
        logBubble(shown = false)
        BubbleHealthLog.cancelShowOpportunity(context, hideReason)
        lastHideReason = hideReason
        root.animate()
            .alpha(0f)
            .scaleX(0.92f)
            .scaleY(0.92f)
            .setDuration(BUBBLE_ANIM_MS)
            .setStartDelay(0L)
            .withEndAction {
                isConcealing = false
                setViewVisibilityImmediate(root, false, hideReason)
                root.alpha = 1f
                root.scaleX = 1f
                root.scaleY = 1f
            }
            .start()
    }

    @Volatile
    private var isConcealing = false

    private val bubbleSignalLog = BubbleSignalLog()

    private fun logBubble(shown: Boolean) {
        if (!bubbleSignalLog.shouldLog(shown)) return
        val signal = if (shown) {
            FloatingBubbleManager.bubbleSignalAtElapsed
        } else {
            FloatingBubbleManager.bubbleHideSignalAtElapsed
        }
        val latency = BubbleTiming.latencyMs(signal, SystemClock.elapsedRealtime())
        AppLogRepository.logEvent(
            DiagnosticSource.BUBBLE,
            if (shown) DiagnosticType.BUBBLE_SHOWN else DiagnosticType.BUBBLE_HIDDEN,
            "latency_ms=$latency"
        )
    }

    fun hideOverlayImmediately(reason: String = BubbleHealthLog.HIDE_SENSITIVE_APP) {
        bubbleSignalLog.forget()
        mainHandler.post {
            val root = overlayView
            val wasVisibleOrPending = (root?.visibility == View.VISIBLE) ||
                isConcealing ||
                BubbleHealthLog.hasPendingOpportunity()
            isConcealing = false
            root?.animate()?.cancel()
            root?.let { setViewVisibilityImmediate(it, false, reason) }
            if (wasVisibleOrPending) {
                BubbleHealthLog.cancelShowOpportunity(context, reason)
            }
        }
    }

    fun restoreOverlayVisibility(isKeyboardOpen: Boolean) {
        mainHandler.post {
            if (FloatingBubbleManager.isCurrentAppSensitive.value) {
                overlayView?.let { setViewVisibilityImmediate(it, false, BubbleHealthLog.HIDE_SENSITIVE_APP) }
                return@post
            }
            val root = overlayView ?: return@post
            val repairActive = A11yWatchdog.repairKind.value != A11yRepairKind.NONE
            if (isKeyboardOpen || repairActive || overlayRecording.value || overlayExpanded.value) {
                revealBubble(root)
            } else {
                concealBubble(root, BubbleHealthLog.HIDE_NO_IME_AFTER_SETTLE)
            }
        }
    }

    fun updateOverlayVisibility(isSessionActive: Boolean) {
        if (FloatingBubbleManager.isCurrentAppSensitive.value) {
            overlayView?.let { setViewVisibilityImmediate(it, false, BubbleHealthLog.HIDE_SENSITIVE_APP) }
            return
        }
        val isKeyboardOpen = FloatingBubbleManager.isKeyboardVisible.value
        val repairActive = A11yWatchdog.repairKind.value != A11yRepairKind.NONE
        val root = overlayView ?: return
        if (!isSessionActive && !isKeyboardOpen && !repairActive) {
            setViewVisibilityImmediate(root, false, BubbleHealthLog.HIDE_NO_IME_AFTER_SETTLE)
            BubbleHealthLog.cancelShowOpportunity(context, BubbleHealthLog.HIDE_NO_IME_AFTER_SETTLE)
        } else {
            if (!root.isAttachedToWindow) {
                attachWithRetry("update_visibility")
            }
            setViewVisibilityImmediate(root, true, null)
        }
    }

    fun onDestroy() {
        cancelPendingAttachRetry()
        RenderBudget.setOverlayFrameMonitoring(false)
        overlayBubbleVisible.value = false
        overlayScope?.cancel()
        overlayScope = null
        mainHandler.removeCallbacks(shrinkRunnable)
        dragSnapHandler.cancel()

        lifecycleOwner.onPause()
        lifecycleOwner.onStop()
        lifecycleOwner.onDestroy()

        if (overlayView != null) {
            try {
                windowManager?.removeViewImmediate(overlayView)
            } catch (_: Throwable) {
                try {
                    windowManager?.removeView(overlayView)
                } catch (e: Exception) {
                    Log.e(TAG, "Error removing overlayView from WindowManager", e)
                }
            }
            overlayView = null
        }
    }
}
