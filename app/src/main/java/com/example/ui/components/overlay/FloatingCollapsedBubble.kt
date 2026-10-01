package com.example.ui.components.overlay

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.example.R
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Collapsed floating lifebuoy bubble with AI Context Scanning Lens mode:
 * - Normal: Starts at full 56dp size, smoothly shrinks to 45dp on inactivity.
 * - Long-press (>= 650ms): Transforms into a "Scanning Lens" with glowing ring & lens reticle.
 * - Circle gesture in Scan Mode: Detects circular gesture to trigger AI context capture.
 * - Context Loaded: Displays a small glowing neon cyan lens/sparkle badge.
 */
@Composable
fun FloatingCollapsedBubble(
    isRecording: Boolean,
    isShrunk: Boolean = false,
    isSnappedToRight: Boolean = true,
    isScanMode: Boolean = false,
    isContextLoaded: Boolean = false,
    onClick: () -> Unit,
    onLongPress: () -> Unit = {},
    onCircleGestureDetected: () -> Unit = {},
    onDragStart: () -> Unit = {},
    onDrag: (Float, Float) -> Unit = { _, _ -> },
    onDragEnd: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val palette = rememberDynamicAuroraPalette()
    val infiniteTransition = rememberInfiniteTransition(label = "collapsedGlow")

    val pulse by infiniteTransition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    val lensRotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(4000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "lensRotation"
    )

    val animatedBubbleSize by animateDpAsState(
        targetValue = when {
            isScanMode -> 62.dp
            isShrunk -> 45.dp
            else -> 56.dp
        },
        animationSpec = spring(
            dampingRatio = androidx.compose.animation.core.Spring.DampingRatioMediumBouncy,
            stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow
        ),
        label = "bubbleSize"
    )

    val animatedImageSize by animateDpAsState(
        targetValue = if (isShrunk) 40.dp else 48.dp,
        animationSpec = spring(
            dampingRatio = androidx.compose.animation.core.Spring.DampingRatioMediumBouncy,
            stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow
        ),
        label = "imageSize"
    )

    val animatedAlpha by animateFloatAsState(
        targetValue = if (isShrunk && !isScanMode) 0.85f else 1.0f,
        animationSpec = tween(300),
        label = "bubbleAlpha"
    )

    // Gesture tracking for circle detection in scan mode & long press
    val gesturePoints = remember { mutableStateListOf<Offset>() }

    Box(
        modifier = modifier
            .size(if (isScanMode) 64.dp else 56.dp)
            .pointerInput(isScanMode) {
                coroutineScope {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        var isLongPressTriggered = false
                        var isDragging = false
                        var totalDrag = 0f
                        gesturePoints.clear()
                        gesturePoints.add(down.position)

                        val longPressJob = launch {
                            delay(650L)
                            if (!isDragging && totalDrag < 18f) {
                                isLongPressTriggered = true
                                onLongPress()
                            }
                        }

                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull() ?: break
                            if (!change.pressed) {
                                // Pointer up
                                change.consume()
                                longPressJob.cancel()
                                if (isDragging) {
                                    if (isScanMode && isCirclePath(gesturePoints)) {
                                        onCircleGestureDetected()
                                    } else {
                                        onDragEnd()
                                    }
                                } else if (!isLongPressTriggered) {
                                    if (isScanMode) {
                                        onCircleGestureDetected()
                                    } else {
                                        onClick()
                                    }
                                }
                                break
                            } else {
                                val posChange = change.positionChange()
                                val dx = posChange.x
                                val dy = posChange.y
                                val dist = hypot(dx, dy)
                                totalDrag += dist

                                if (totalDrag > 12f && !isDragging) {
                                    isDragging = true
                                    longPressJob.cancel()
                                    onDragStart()
                                }

                                if (isDragging) {
                                    change.consume()
                                    gesturePoints.add(change.position)
                                    if (isScanMode) {
                                        // Check if continuous circular motion detected
                                        if (gesturePoints.size >= 8 && isCirclePath(gesturePoints)) {
                                            onCircleGestureDetected()
                                            break
                                        }
                                    } else {
                                        onDrag(dx, dy)
                                    }
                                }
                            }
                        }
                    }
                }
            },
        contentAlignment = if (isSnappedToRight) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        Box(
            modifier = Modifier
                .size(animatedBubbleSize)
                .clip(CircleShape),
            contentAlignment = Alignment.Center
        ) {
            if (isScanMode) {
                // 1. Scanning Lens Mode: Glowing pulsing cyan/aurora rings with rotating reticle
                Canvas(modifier = Modifier.size(animatedBubbleSize)) {
                    val center = Offset(size.width / 2f, size.height / 2f)
                    val radius = size.minDimension / 2f - 3.dp.toPx()

                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                Color(0x6600E5FF),
                                Color(0x332979FF),
                                Color(0x00000000)
                            ),
                            center = center,
                            radius = radius * 1.3f
                        ),
                        radius = radius * 1.2f,
                        center = center
                    )

                    drawCircle(
                        brush = Brush.sweepGradient(
                            colors = listOf(
                                Color(0xFF00E5FF),
                                Color(0xFF2979FF),
                                Color(0xFF7C4DFF),
                                Color(0xFF00E5FF)
                            )
                        ),
                        radius = radius,
                        center = center,
                        style = Stroke(width = 3.dp.toPx())
                    )
                }

                Icon(
                    imageVector = Icons.Filled.CenterFocusStrong,
                    contentDescription = "Context Scanning Lens",
                    tint = Color(0xFF00E5FF),
                    modifier = Modifier
                        .size(30.dp)
                        .scale(pulse)
                )
            } else {
                // 2. Standard Lifebuoy Bubble
                if (isRecording) {
                    Box(
                        modifier = Modifier
                            .size(animatedBubbleSize * 0.95f * pulse)
                            .background(palette.primaryVibrant.copy(alpha = 0.35f), CircleShape)
                    )
                }
                Image(
                    painter = painterResource(id = R.drawable.ic_lifebuoy_ring),
                    contentDescription = "Floating voice bubble",
                    alpha = animatedAlpha,
                    modifier = Modifier.size(animatedImageSize)
                )

                // 3. AI Context Loaded Badge (Neon Cyan Sparkle Ring on top-right)
                if (isContextLoaded) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .offset(x = (-2).dp, y = 2.dp)
                            .size(16.dp)
                            .background(Color(0xFF0A192F), CircleShape)
                            .border(1.5.dp, Color(0xFF00E5FF), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.AutoAwesome,
                            contentDescription = "AI Context Loaded",
                            tint = Color(0xFF00E5FF),
                            modifier = Modifier.size(10.dp)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Computes whether a set of pointer points approximates a circular gesture
 * by calculating cumulative angle sweep >= 240 degrees (4.18 radians).
 */
private fun isCirclePath(points: List<Offset>): Boolean {
    if (points.size < 6) return false
    val minX = points.minOf { it.x }
    val maxX = points.maxOf { it.x }
    val minY = points.minOf { it.y }
    val maxY = points.maxOf { it.y }

    val width = maxX - minX
    val height = maxY - minY
    if (width < 30f || height < 30f) return false

    val centerX = (minX + maxX) / 2f
    val centerY = (minY + maxY) / 2f

    var totalAngleSweep = 0.0
    var lastAngle = atan2((points.first().y - centerY).toDouble(), (points.first().x - centerX).toDouble())

    for (i in 1 until points.size) {
        val pt = points[i]
        val currentAngle = atan2((pt.y - centerY).toDouble(), (pt.x - centerX).toDouble())
        var diff = currentAngle - lastAngle
        while (diff < -PI) diff += 2 * PI
        while (diff > PI) diff -= 2 * PI
        totalAngleSweep += diff
        lastAngle = currentAngle
    }

    return abs(totalAngleSweep) >= 4.0 // ~230 degrees circular sweep
}
