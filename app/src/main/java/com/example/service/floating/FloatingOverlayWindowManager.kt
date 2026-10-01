package com.example.service.floating

import android.animation.ValueAnimator
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
import android.view.animation.LinearInterpolator
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.animation.addListener
import com.example.service.FloatingBubbleManager
import com.example.service.VoxStreamAccessibilityService
import com.example.ui.components.FloatingDictationPopup
import com.example.ui.components.overlay.FloatingCollapsedBubble
import com.example.ui.components.overlay.FloatingSafeModeShieldBadge
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sin

class FloatingOverlayWindowManager(private val context: Context) {

    companion object {
        private const val TAG = "FloatingOverlayWinMgr"
        private const val SHRINK_TIMEOUT_MS = 3000L
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    val lifecycleOwner = FloatingOverlayLifecycleOwner()

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

    private var isSnappedToRight = true
    private var savedY = 0
    private var snapAnimator: ValueAnimator? = null
    private var lastMoveVibrateTime = 0L

    private val shrinkRunnable = Runnable { applyShrink() }

    enum class SettlePosition { TOP, BOTTOM }
    private var currentSettlePosition = SettlePosition.BOTTOM

    @SuppressLint("ClickableViewAccessibility")
    fun initOverlay(
        onRingClick: () -> Unit,
        onCancelClick: () -> Unit,
        onPolishClick: () -> Unit,
        onCompleteClick: () -> Unit
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

        loadPreferences()

        val displayMetrics = context.resources.displayMetrics
        val density = displayMetrics.density
        val screenHeight = displayMetrics.heightPixels
        val screenWidth = displayMetrics.widthPixels
        val bubbleSize = (56 * density).toInt()

        val topY = (36 * density).toInt()
        val maxY = (screenHeight - (80 * density).toInt()).coerceAtLeast(topY + (60 * density).toInt())
        val initialY = savedY.coerceIn(topY, maxY)
        val initialX = if (isSnappedToRight) (screenWidth - bubbleSize) else 0

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
                MyApplicationTheme(darkTheme = true, dynamicColor = true) {
                    val transcript by overlayTranscript.collectAsState()
                    val recording by overlayRecording.collectAsState()
                    val pendingFinalizing by overlayPendingFinalizing.collectAsState()
                    val polishing by overlayPolishing.collectAsState()
                    val isExpanded by overlayExpanded.collectAsState()
                    val isShrunk by overlayShrunk.collectAsState()
                    val audioAmplitude by overlayAudioAmplitude.collectAsState()
                    val selectedGlowStyleId by FloatingBubbleManager.selectedGlowStyleId.collectAsState()
                    val selectedFinishingStyleId by FloatingBubbleManager.selectedFinishingStyleId.collectAsState()
                    val isCurrentAppSensitive by FloatingBubbleManager.isCurrentAppSensitive.collectAsState()

                    if (isCurrentAppSensitive) {
                        FloatingSafeModeShieldBadge(
                            isSnappedToRight = isSnappedToRight,
                            onClick = onRingClick,
                            onDragStart = {
                                FloatingHapticManager.trigger(context, FloatingHapticType.BUBBLE_HOLD)
                                resetInactivityTimer(keepShrunk = true)
                            },
                            onDrag = { dx: Float, dy: Float -> handleOverlayDrag(dx, dy) },
                            onDragEnd = { handleOverlayDragEnd() }
                        )
                    } else if (isExpanded) {
                        FloatingDictationPopup(
                            transcriptText = transcript,
                            isRecording = recording,
                            isPendingFinalizing = pendingFinalizing,
                            isPolishing = polishing,
                            audioAmplitude = audioAmplitude,
                            glowStyleId = selectedGlowStyleId,
                            finishingStyleId = selectedFinishingStyleId,
                            onCancelClick = onCancelClick,
                            onPolishClick = onPolishClick,
                            onCompleteClick = onCompleteClick,
                            onLifebuoyClick = onRingClick,
                            onDragStart = {
                                FloatingHapticManager.trigger(context, FloatingHapticType.BUBBLE_HOLD)
                                resetInactivityTimer(keepShrunk = true)
                            },
                            onDrag = { dx: Float, dy: Float -> handleOverlayDrag(dx, dy) },
                            onDragEnd = { handleOverlayDragEnd() }
                        )
                    } else {
                        FloatingCollapsedBubble(
                            isRecording = recording,
                            isShrunk = isShrunk,
                            isSnappedToRight = isSnappedToRight,
                            onClick = onRingClick,
                            onDragStart = {
                                FloatingHapticManager.trigger(context, FloatingHapticType.BUBBLE_HOLD)
                                resetInactivityTimer(keepShrunk = true)
                            },
                            onDrag = { dx: Float, dy: Float -> handleOverlayDrag(dx, dy) },
                            onDragEnd = { handleOverlayDragEnd() }
                        )
                    }
                }
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

    private fun loadPreferences() {
        val prefs = context.getSharedPreferences("voxstream_settings", Context.MODE_PRIVATE)
        isSnappedToRight = prefs.getBoolean("bubble_snapped_right", true)
        val posStr = prefs.getString("settle_position", SettlePosition.BOTTOM.name) ?: SettlePosition.BOTTOM.name
        currentSettlePosition = try {
            SettlePosition.valueOf(posStr)
        } catch (e: Exception) {
            SettlePosition.BOTTOM
        }
        val displayMetrics = context.resources.displayMetrics
        val screenHeight = displayMetrics.heightPixels
        val defaultY = (screenHeight * 0.52f).toInt()
        savedY = prefs.getInt("bubble_pos_y", defaultY)
    }

    private fun savePreferences(snappedRight: Boolean, y: Int) {
        context.getSharedPreferences("voxstream_settings", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("bubble_snapped_right", snappedRight)
            .putInt("bubble_pos_y", y)
            .apply()
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
        lp.y = savedY.coerceIn(topY, maxY)

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
        lp.x = if (isSnappedToRight) (screenWidth - bubbleSize) else 0
        lp.y = savedY.coerceIn(topY, maxY)

        try {
            windowManager?.updateViewLayout(overlayView, lp)
        } catch (e: Exception) {
            Log.e(TAG, "Error updating window layout on collapse", e)
        }
        resetInactivityTimer()
    }

    private fun handleOverlayDrag(dx: Float, dy: Float) {
        val lp = layoutParams ?: return
        val displayMetrics = context.resources.displayMetrics
        val screenHeight = displayMetrics.heightPixels
        val screenWidth = displayMetrics.widthPixels
        val density = displayMetrics.density

        val isExpanded = overlayExpanded.value
        val topY = (32 * density).toInt()
        val maxY = if (isExpanded) {
            (screenHeight - (210 * density).toInt()).coerceAtLeast(topY + (60 * density).toInt())
        } else {
            (screenHeight - (80 * density).toInt()).coerceAtLeast(topY + (60 * density).toInt())
        }

        lp.y = (lp.y + dy.toInt()).coerceIn(topY, maxY)

        if (!isExpanded) {
            val bubbleSize = (56 * density).toInt()
            val maxX = (screenWidth - bubbleSize).coerceAtLeast(0)
            val proposedX = (lp.x + dx.toInt()).coerceIn(0, maxX)

            val magneticCenterX = (screenWidth - bubbleSize) / 2
            val magneticCenterY = maxY
            val magneticRadius = 135 * density

            val distToMagnetic = hypot(
                (proposedX - magneticCenterX).toDouble(),
                (lp.y - magneticCenterY).toDouble()
            ).toFloat()

            if (distToMagnetic < magneticRadius) {
                val factor = (1f - (distToMagnetic / magneticRadius)).coerceIn(0f, 1f)
                val pullStrength = factor * factor * 0.70f
                lp.x = (proposedX + (magneticCenterX - proposedX) * pullStrength).toInt().coerceIn(0, maxX)
                lp.y = (lp.y + (magneticCenterY - lp.y) * pullStrength).toInt().coerceIn(topY, maxY)
            } else {
                lp.x = proposedX
            }
        } else {
            lp.x = 0
        }

        try {
            windowManager?.updateViewLayout(overlayView, lp)
        } catch (e: Exception) {
            Log.e(TAG, "Error updating overlay layout during drag", e)
        }

        val now = System.currentTimeMillis()
        if (now - lastMoveVibrateTime > 75) {
            lastMoveVibrateTime = now
            FloatingHapticManager.trigger(context, FloatingHapticType.BUBBLE_MOVE)
        }
    }

    private fun handleOverlayDragEnd() {
        val lp = layoutParams ?: return
        val displayMetrics = context.resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val density = displayMetrics.density
        val isExpanded = overlayExpanded.value

        savedY = lp.y

        if (!isExpanded) {
            val bubbleSize = (56 * density).toInt()
            val magneticCenterX = (screenWidth - bubbleSize) / 2
            val maxY = (displayMetrics.heightPixels - (80 * density).toInt())
            val magneticRadius = 135 * density

            val distToMagnetic = hypot(
                (lp.x - magneticCenterX).toDouble(),
                (lp.y - maxY).toDouble()
            ).toFloat()

            val targetX: Int
            val targetY: Int
            val snapRight: Boolean

            if (distToMagnetic < magneticRadius * 0.85f) {
                targetX = magneticCenterX
                targetY = maxY
                snapRight = lp.x >= screenWidth / 2
            } else {
                snapRight = (lp.x + bubbleSize / 2) >= screenWidth / 2
                targetX = if (snapRight) (screenWidth - bubbleSize) else 0
                targetY = lp.y
            }

            val startX = lp.x
            val startY = lp.y

            isSnappedToRight = snapRight
            savedY = targetY
            savePreferences(snapRight, savedY)

            val totalDistX = (targetX - startX).toFloat()
            val totalDistY = (targetY - startY).toFloat()

            val bounceDirX = if (targetX >= screenWidth / 2) -1f else 1f
            val maxAmplitude = (38 * density).coerceAtLeast(abs(totalDistX) * 0.32f).coerceAtMost(65 * density)
            val amp1 = maxAmplitude
            val amp2 = maxAmplitude * 0.40f
            val amp3 = maxAmplitude * 0.15f

            val t0 = 0.28f
            val t1 = 0.56f
            val t2 = 0.80f
            var lastBounceImpactIndex = -1

            snapAnimator?.cancel()
            snapAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 680L
                interpolator = LinearInterpolator()
                addUpdateListener { anim ->
                    val fraction = anim.animatedFraction
                    var mainProgress = 0f
                    var reboundOffset = 0f

                    if (fraction <= t0) {
                        val tNorm = fraction / t0
                        mainProgress = tNorm * tNorm
                        reboundOffset = 0f

                        if (lastBounceImpactIndex < 0 && fraction >= t0 * 0.92f) {
                            lastBounceImpactIndex = 0
                            FloatingHapticManager.trigger(context, FloatingHapticType.BUBBLE_SETTLE)
                        }
                    } else {
                        mainProgress = 1.0f
                        if (fraction <= t1) {
                            val u = (fraction - t0) / (t1 - t0)
                            reboundOffset = amp1 * sin(u * Math.PI).toFloat()
                            if (lastBounceImpactIndex < 1 && fraction >= (t0 + (t1 - t0) * 0.90f)) {
                                lastBounceImpactIndex = 1
                                FloatingHapticManager.trigger(context, FloatingHapticType.BUBBLE_SETTLE)
                            }
                        } else if (fraction <= t2) {
                            val u = (fraction - t1) / (t2 - t1)
                            reboundOffset = amp2 * sin(u * Math.PI).toFloat()
                            if (lastBounceImpactIndex < 2 && fraction >= (t1 + (t2 - t1) * 0.90f)) {
                                lastBounceImpactIndex = 2
                                FloatingHapticManager.trigger(context, FloatingHapticType.BUBBLE_SETTLE)
                            }
                        } else {
                            val u = (fraction - t2) / (1.0f - t2)
                            reboundOffset = amp3 * sin(u * Math.PI).toFloat()
                            if (lastBounceImpactIndex < 3 && fraction >= (t2 + (1.0f - t2) * 0.90f)) {
                                lastBounceImpactIndex = 3
                                FloatingHapticManager.trigger(context, FloatingHapticType.BUBBLE_SETTLE)
                            }
                        }
                    }

                    val currentMainX = startX + totalDistX * mainProgress
                    val currentMainY = startY + totalDistY * mainProgress
                    val finalX = currentMainX + bounceDirX * reboundOffset

                    lp.x = finalX.toInt().coerceIn(0, screenWidth - bubbleSize)
                    lp.y = currentMainY.toInt()

                    try {
                        windowManager?.updateViewLayout(overlayView, lp)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed updating layout during snap", e)
                    }
                }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        lp.x = targetX
                        lp.y = targetY
                        try {
                            windowManager?.updateViewLayout(overlayView, lp)
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed final layout update", e)
                        }
                        FloatingHapticManager.trigger(context, FloatingHapticType.BUBBLE_SETTLE)
                    }
                })
                start()
            }
        } else {
            savePreferences(isSnappedToRight, savedY)
            FloatingHapticManager.trigger(context, FloatingHapticType.BUBBLE_SETTLE)
        }

        resetInactivityTimer(keepShrunk = true)
    }

    fun onKeyboardVisibilityChanged(isVisible: Boolean, isSessionActive: Boolean) {
        mainHandler.post {
            val root = overlayView ?: return@post
            if (isVisible) {
                if (!overlayRecording.value && !overlayExpanded.value) {
                    collapsePanel()
                }
                expandToFullSize()
                root.visibility = View.VISIBLE
                resetInactivityTimer(keepShrunk = false)
            } else {
                if (!isSessionActive) {
                    collapsePanel()
                    root.visibility = View.GONE
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
        snapAnimator?.cancel()
        snapAnimator = null

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
