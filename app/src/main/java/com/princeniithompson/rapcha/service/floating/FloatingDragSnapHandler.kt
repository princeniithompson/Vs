package com.princeniithompson.rapcha.service.floating

import android.animation.ValueAnimator
import android.content.Context
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import androidx.core.animation.addListener
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Handles touch dragging, magnetic center attraction, and physics-based snap-to-edge
 * animations for the floating overlay bubble.
 */
class FloatingDragSnapHandler(private val context: Context) {

    companion object {
        private const val TAG = "FloatingDragSnap"
    }

    enum class SettlePosition { TOP, BOTTOM }

    var isSnappedToRight = true
        private set

    var savedY = 0
        private set

    var currentSettlePosition = SettlePosition.BOTTOM
        private set

    private var snapAnimator: ValueAnimator? = null
    private var lastMoveVibrateTime = 0L

    init {
        loadPreferences()
    }

    fun loadPreferences() {
        val prefs = context.getSharedPreferences("voxstream_settings", Context.MODE_PRIVATE)
        isSnappedToRight = prefs.getBoolean("bubble_snapped_right", true)
        val posStr = prefs.getString("settle_position", SettlePosition.BOTTOM.name) ?: SettlePosition.BOTTOM.name
        currentSettlePosition = try {
            SettlePosition.valueOf(posStr)
        } catch (_: Exception) {
            SettlePosition.BOTTOM
        }
        val displayMetrics = context.resources.displayMetrics
        val screenHeight = displayMetrics.heightPixels
        val defaultY = (screenHeight * 0.52f).toInt()
        savedY = prefs.getInt("bubble_pos_y", defaultY)
    }

    fun savePreferences(snappedRight: Boolean, y: Int) {
        isSnappedToRight = snappedRight
        savedY = y
        context.getSharedPreferences("voxstream_settings", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("bubble_snapped_right", snappedRight)
            .putInt("bubble_pos_y", y)
            .apply()
    }

    fun handleOverlayDrag(
        dx: Float,
        dy: Float,
        isExpanded: Boolean,
        lp: WindowManager.LayoutParams,
        windowManager: WindowManager?,
        overlayView: View?
    ) {
        val displayMetrics = context.resources.displayMetrics
        val screenHeight = displayMetrics.heightPixels
        val screenWidth = displayMetrics.widthPixels
        val density = displayMetrics.density

        val topY = (32 * density).toInt()
        val maxY = if (isExpanded) {
            (screenHeight - (320 * density).toInt()).coerceAtLeast(topY + (60 * density).toInt())
        } else {
            (screenHeight - (80 * density).toInt()).coerceAtLeast(topY + (60 * density).toInt())
        }

        snapAnimator?.cancel()
        lp.y = (lp.y + dy.toInt()).coerceIn(topY, maxY)

        val matchParent = lp.width == WindowManager.LayoutParams.MATCH_PARENT
        val lockX = OverlayDragMath.locksHorizontal(isExpanded, matchParent)
        val windowWidth = if (matchParent) screenWidth else lp.width.coerceAtLeast(1)
        lp.x = OverlayDragMath.nextX(lp.x, dx.toInt(), screenWidth, windowWidth, lockX)

        if (!isExpanded) {
            val windowSize = (BubbleChromeGeometry.IDLE_WINDOW_SIZE_DP * density).toInt()
            val windowPaddingPx = (BubbleChromeGeometry.WINDOW_PADDING_DP * density).toInt()
            val maxX = (screenWidth - windowSize).coerceAtLeast(0)
            val proposedX = lp.x.coerceIn(0, maxX)

            val magneticCenterX = (screenWidth - windowSize) / 2
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

    fun handleOverlayDragEnd(
        isExpanded: Boolean,
        lp: WindowManager.LayoutParams,
        windowManager: WindowManager?,
        overlayView: View?,
        onComplete: () -> Unit
    ) {
        val displayMetrics = context.resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val density = displayMetrics.density

        savedY = lp.y

        if (!isExpanded) {
            val windowSize = (BubbleChromeGeometry.IDLE_WINDOW_SIZE_DP * density).toInt()
            val windowPaddingPx = (BubbleChromeGeometry.WINDOW_PADDING_DP * density).toInt()
            val magneticCenterX = (screenWidth - windowSize) / 2
            val maxY = (displayMetrics.heightPixels - (80 * density).toInt())
            val magneticRadius = 135 * density

            val distToMagnetic = hypot(
                (lp.x - magneticCenterX).toDouble(),
                (lp.y - maxY).toDouble()
            ).toFloat()

            val targetX: Int
            val targetY: Int
            val snapRight: Boolean

            val edgeMarginPx = (BubbleChromeGeometry.EDGE_MARGIN_DP * density).toInt()
            if (distToMagnetic < magneticRadius * 0.85f) {
                targetX = magneticCenterX
                targetY = maxY
                snapRight = lp.x >= screenWidth / 2
            } else {
                snapRight = (lp.x + windowSize / 2) >= screenWidth / 2
                targetX = if (snapRight) (screenWidth - windowSize - edgeMarginPx + windowPaddingPx) else (edgeMarginPx - windowPaddingPx)
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
                    var mainProgress: Float
                    var reboundOffset: Float

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

                    if (lp.width == WindowManager.LayoutParams.MATCH_PARENT) {
                        lp.x = 0
                    } else {
                        val currentWindowWidth = if (lp.width > 0) lp.width else windowSize
                        lp.x = finalX.toInt().coerceIn(0, screenWidth - currentWindowWidth)
                    }
                    lp.y = currentMainY.toInt()

                    try {
                        windowManager?.updateViewLayout(overlayView, lp)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed updating layout during snap", e)
                    }
                }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        if (lp.width == WindowManager.LayoutParams.MATCH_PARENT) {
                            lp.x = 0
                        } else {
                            lp.x = targetX
                        }
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
        } else if (lp.width != WindowManager.LayoutParams.MATCH_PARENT) {
            val windowWidth = lp.width.coerceAtLeast(1)
            val windowPaddingPx = (BubbleChromeGeometry.WINDOW_PADDING_DP * density).toInt()
            val snapRight = (lp.x + windowWidth / 2) >= screenWidth / 2
            val edgeMarginPx = (BubbleChromeGeometry.EDGE_MARGIN_DP * density).toInt()
            lp.x = if (snapRight) {
                (screenWidth - windowWidth - edgeMarginPx + windowPaddingPx).coerceAtLeast(0)
            } else {
                (edgeMarginPx - windowPaddingPx).coerceAtLeast(0)
            }
            isSnappedToRight = snapRight
            savedY = lp.y
            savePreferences(snapRight, savedY)
            try {
                windowManager?.updateViewLayout(overlayView, lp)
            } catch (e: Exception) {
                Log.w(TAG, "Failed updating compact layout after drag end", e)
            }
            FloatingHapticManager.trigger(context, FloatingHapticType.BUBBLE_SETTLE)
        } else {
            lp.x = 0
            savedY = lp.y
            savePreferences(isSnappedToRight, savedY)
            try {
                windowManager?.updateViewLayout(overlayView, lp)
            } catch (e: Exception) {
                Log.w(TAG, "Failed updating expanded layout after drag end", e)
            }
            FloatingHapticManager.trigger(context, FloatingHapticType.BUBBLE_SETTLE)
        }

        onComplete()
    }

    fun cancel() {
        snapAnimator?.cancel()
        snapAnimator = null
    }
}
