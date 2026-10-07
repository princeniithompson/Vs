package com.example.ui.components.overlay

import androidx.compose.animation.core.FastOutSlowInEasing
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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.example.R
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.hypot

/**
 * Collapsed floating lifebuoy bubble with simplified 2-step AI Context Scan flow:
 * 1. Normal mode: Tap starts/stops voice typing; Long-press (>= 650ms) enters Scan Mode (Lens appearance).
 * 2. Scan mode: Single tap immediately triggers the Aurora Screen Scan.
 * 3. Context Loaded: Displays a glowing neon cyan lens/sparkle badge.
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
    onScanTriggered: () -> Unit = {},
    onDragStart: () -> Unit = {},
    onDrag: (Float, Float) -> Unit = { _, _ -> },
    onDragEnd: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val palette = rememberDynamicAuroraPalette()
    val isNetworkProblem by com.example.service.FloatingBubbleManager.isNetworkProblem.collectAsState()
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
                                    onDragEnd()
                                } else if (!isLongPressTriggered) {
                                    if (isScanMode) {
                                        // Tap in Scan Mode -> immediately trigger screen scan
                                        onScanTriggered()
                                    } else {
                                        // Tap in Normal Mode -> open popup/start voice typing
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
                                    onDrag(dx, dy)
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
                // 1. Scanning Lens Mode: Glowing pulsing cyan/aurora halo with focus reticle
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
                        style = Stroke(width = 3.5.dp.toPx())
                    )
                }

                Icon(
                    imageVector = Icons.Filled.CenterFocusStrong,
                    contentDescription = "Context Scanning Lens (Tap to Scan)",
                    tint = Color(0xFF00E5FF),
                    modifier = Modifier
                        .size(30.dp)
                        .scale(pulse)
                )
            } else {
                // 2. Standard Lifebuoy Bubble
                if (isRecording) {
                    val recordingGlow = if (isNetworkProblem) {
                        Color(0xFFFF3B30).copy(alpha = 0.55f)
                    } else {
                        palette.primaryVibrant.copy(alpha = 0.35f)
                    }
                    Box(
                        modifier = Modifier
                            .size(animatedBubbleSize * 0.95f * pulse)
                            .background(recordingGlow, CircleShape)
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
