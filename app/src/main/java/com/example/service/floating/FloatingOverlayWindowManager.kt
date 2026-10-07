package com.example.service.floating

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import com.example.data.ScreenContextRepository
import com.example.service.FloatingBubbleManager
import com.example.service.VoxStreamAccessibilityService
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Manages the overlay WindowManager, layout parameters, lifecycle, and view composition
 * for the floating voice bubble.
 */
class FloatingOverlayWindowManager(private val context: Context) {

    companion object {
        private const val TAG = "FloatingOverlayWinMgr"
        private const val SHRINK_TIMEOUT_MS = 3000L
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    val lifecycleOwner = FloatingOverlayLifecycleOwner()
    val dragSnapHandler = FloatingDragSnapHandler(context)

    private var windowManager: WindowManager? = null
    var overlayView: View? = null
        private set
    var layoutParams: WindowManager.LayoutParams? = null
        private set

    val overlayTranscript = MutableStateFlow("")
    val overlayRecording = MutableStateFlow(false)
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

    private val shrinkRunnable = Runnable { applyShrink() }

    val isSnappedToRight: Boolean
        get() = dragSnapHandler.isSnappedToRight

    @SuppressLint("ClickableViewAccessibility")
    fun initOverlay(
        onRingClick: () -> Unit,
        onCancelClick: () -> Unit,
        onPolishClick: () -> Unit,
        onCompleteClick: () -> Unit,
        onLongPressBubble: () -> Unit = {},
        onScanTriggered: () -> Unit = {}
    ) {
        lifecycleOwner.onCreate()
        lifecycleOwner.onStart()
        lifecycleOwner.onResume()

        val accessService = VoxStreamAccessibilityService.instance
        windowManager = if (accessService != null) {
            accessService.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        } else {
            context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        }

        dragSnapHandler.loadPreferences()

        val displayMetrics = context.resources.displayMetrics
        val density = displayMetrics.density
        val screenHeight = displayMetrics.heightPixels
        val screenWidth = displayMetrics.widthPixels
        val bubbleSize = (56 * density).toInt()

        val topY = (36 * density).toInt()
        val maxY = (screenHeight - (80 * density).toInt()).coerceAtLeast(topY + (60 * density).toInt())
        val initialY = dragSnapHandler.savedY.coerceIn(topY, maxY)
        val initialX = if (dragSnapHandler.isSnappedToRight) (screenWidth - bubbleSize) else 0

        val windowType = if (accessService != null) {
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        layoutParams = WindowManager.LayoutParams(
            bubbleSize,
            bubbleSize,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = initialX
            y = initialY
        }

        val composeView = ComposeView(context).apply {
            lifecycleOwner.attachToView(this)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool)
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
                val selectedGlowStyleId by FloatingBubbleManager.selectedGlowStyleId.collectAsState()
                val selectedFinishingStyleId by FloatingBubbleManager.selectedFinishingStyleId.collectAsState()
                val isCurrentAppSensitive by FloatingBubbleManager.isCurrentAppSensitive.collectAsState()

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
                    selectedFinishingStyleId = selectedFinishingStyleId,
                    isCurrentAppSensitive = isCurrentAppSensitive,
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
        overlayView = composeView

        val isKeyboardVisible = FloatingBubbleManager.isKeyboardVisible.value
        composeView.visibility = if (isKeyboardVisible) View.VISIBLE else View.GONE

        try {
            windowManager?.addView(overlayView, layoutParams)
            Log.d(TAG, "Compose overlay view attached to WindowManager successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed adding overlay view to WindowManager", e)
        }

        resetInactivityTimer()
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
        overlayExpanded.value = true
        val lp = layoutParams ?: return
        val displayMetrics = context.resources.displayMetrics
        val density = displayMetrics.density
        val screenHeight = displayMetrics.heightPixels

        val topY = (36 * density).toInt()
        val maxY = (screenHeight - (210 * density).toInt()).coerceAtLeast(topY + (60 * density).toInt())

        lp.width = WindowManager.LayoutParams.MATCH_PARENT
        lp.height = WindowManager.LayoutParams.WRAP_CONTENT
        lp.gravity = Gravity.TOP or Gravity.START
        lp.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        lp.x = 0
        lp.y = dragSnapHandler.savedY.coerceIn(topY, maxY)

        try {
            windowManager?.updateViewLayout(overlayView, lp)
        } catch (e: Exception) {
            Log.e(TAG, "Error updating window layout on expand", e)
        }
    }

    fun collapsePanel() {
        overlayExpanded.value = false
        val lp = layoutParams ?: return
        val displayMetrics = context.resources.displayMetrics
        val density = displayMetrics.density
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels
        val bubbleSize = (56 * density).toInt()

        val topY = (36 * density).toInt()
        val maxY = (screenHeight - (80 * density).toInt()).coerceAtLeast(topY + (60 * density).toInt())

        lp.width = bubbleSize
        lp.height = bubbleSize
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = if (dragSnapHandler.isSnappedToRight) (screenWidth - bubbleSize) else 0
        lp.y = dragSnapHandler.savedY.coerceIn(topY, maxY)

        try {
            windowManager?.updateViewLayout(overlayView, lp)
        } catch (e: Exception) {
            Log.e(TAG, "Error updating window layout on collapse", e)
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
        lp.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
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

    fun onKeyboardVisibilityChanged(isVisible: Boolean, isSessionActive: Boolean) {
        mainHandler.post {
            val root = overlayView ?: return@post
            if (isVisible) {
                root.animate().cancel()
                root.alpha = 1f
                if (!overlayRecording.value && !overlayExpanded.value) {
                    collapsePanel()
                }
                expandToFullSize()
                root.visibility = View.VISIBLE
                resetInactivityTimer(keepShrunk = false)
            } else {
                if (!isSessionActive) {
                    collapsePanel()
                    root.animate()
                        .alpha(0f)
                        .setDuration(150L)
                        .withEndAction {
                            root.visibility = View.GONE
                            root.alpha = 1f
                        }
                        .start()
                } else {
                    root.visibility = View.VISIBLE
                }
            }
        }
    }

    fun attachToAccessibilityService(accessService: VoxStreamAccessibilityService) {
        mainHandler.post {
            try {
                val currentOverlay = overlayView ?: return@post
                val currentLp = layoutParams ?: return@post

                try {
                    windowManager?.removeView(currentOverlay)
                } catch (e: Exception) {
                    Log.w(TAG, "Notice removing view for accessibility re-attachment: ${e.message}")
                }

                val newWm = accessService.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                windowManager = newWm
                currentLp.type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY

                newWm.addView(currentOverlay, currentLp)
                Log.d(TAG, "Re-attached overlay to TYPE_ACCESSIBILITY_OVERLAY successfully!")
            } catch (e: Exception) {
                Log.e(TAG, "Error attaching to accessibility window manager", e)
            }
        }
    }

    fun updateOverlayVisibility(isSessionActive: Boolean) {
        val isKeyboardOpen = FloatingBubbleManager.isKeyboardVisible.value
        if (!isSessionActive && !isKeyboardOpen) {
            overlayView?.visibility = View.GONE
        } else {
            overlayView?.visibility = View.VISIBLE
        }
    }

    fun onDestroy() {
        mainHandler.removeCallbacks(shrinkRunnable)
        dragSnapHandler.cancel()

        lifecycleOwner.onPause()
        lifecycleOwner.onStop()
        lifecycleOwner.onDestroy()

        if (overlayView != null) {
            try {
                windowManager?.removeView(overlayView)
            } catch (e: Exception) {
                Log.e(TAG, "Error removing overlayView from WindowManager", e)
            }
            overlayView = null
        }
    }
}
