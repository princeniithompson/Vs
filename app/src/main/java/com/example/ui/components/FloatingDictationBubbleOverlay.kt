package com.example.ui.components

import android.content.Context
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.unit.dp
import com.example.ui.components.overlay.AuroraColorPalette
import com.example.ui.components.overlay.FloatingActionRow
import com.example.ui.components.overlay.FloatingDockedLifebuoy
import com.example.ui.components.overlay.FloatingTranscriptBox
import com.example.ui.components.overlay.NotchedContainerShape
import com.example.ui.components.overlay.createNotchedPath
import com.example.ui.components.overlay.drawNotchedOverlayBackground
import com.example.ui.components.overlay.getDynamicTonePalette as getDynamicTonePaletteFromOverlay
import com.example.ui.components.overlay.rememberDynamicAuroraPalette as rememberDynamicAuroraPaletteFromOverlay
import kotlinx.coroutines.delay

// Re-exports for backward compatibility across the app
typealias AuroraColorPalette = com.example.ui.components.overlay.AuroraColorPalette
typealias NotchedPopupShape = com.example.ui.components.overlay.NotchedContainerShape
typealias NotchedContainerShape = com.example.ui.components.overlay.NotchedContainerShape

@Composable
fun rememberDynamicAuroraPalette(toneIdOverride: String? = null): com.example.ui.components.overlay.AuroraColorPalette {
    return rememberDynamicAuroraPaletteFromOverlay(toneIdOverride)
}

fun getDynamicTonePalette(context: Context, toneId: String): com.example.ui.components.overlay.AuroraColorPalette {
    return getDynamicTonePaletteFromOverlay(context, toneId)
}

@Composable
fun FloatingCollapsedBubble(
    isRecording: Boolean,
    isShrunk: Boolean = false,
    isSnappedToRight: Boolean = true,
    onClick: () -> Unit,
    onDragStart: () -> Unit = {},
    onDrag: (Float, Float) -> Unit = { _, _ -> },
    onDragEnd: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    com.example.ui.components.overlay.FloatingCollapsedBubble(
        isRecording = isRecording,
        isShrunk = isShrunk,
        isSnappedToRight = isSnappedToRight,
        onClick = onClick,
        onDragStart = onDragStart,
        onDrag = onDrag,
        onDragEnd = onDragEnd,
        modifier = modifier
    )
}

/**
 * Floating Dictation Popup matching the exact reference UI design:
 * - Highly transparent dark frosted glass container
 * - Soft dynamic Aurora Glow bloom diffusing from within the container and around the notch
 * - Semicircular cutout notch in top-right corner with nested, completely unclipped lifebuoy ring
 * - Left-aligned text input area with active blinking cursor and auto-scroll
 * - Compact button row (Cancel | Polish | Complete)
 * - Drag handle on the Lifebuoy Bubble
 */
@Composable
fun FloatingDictationPopup(
    transcriptText: String,
    isRecording: Boolean,
    isPendingFinalizing: Boolean,
    isPolishing: Boolean,
    audioAmplitude: Float = 0f,
    glowStyleId: String = GlowAnimationCatalogue.DEFAULT_ID,
    finishingStyleId: String = FinishingAnimationCatalogue.DEFAULT_ID,
    onCancelClick: () -> Unit,
    onPolishClick: () -> Unit,
    onCompleteClick: () -> Unit,
    onLifebuoyClick: () -> Unit,
    onDragStart: () -> Unit = {},
    onDrag: (Float, Float) -> Unit = { _, _ -> },
    onDragEnd: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val palette = rememberDynamicAuroraPalette()

    val infiniteTransition = rememberInfiniteTransition(label = "auroraGlow")
    val glowPulse by infiniteTransition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glowPulse"
    )

    val phase1 by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2f * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(2600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "phase1"
    )

    val phase2 by infiniteTransition.animateFloat(
        initialValue = (2f * Math.PI).toFloat(),
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(3600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "phase2"
    )

    // Asymmetric audio amplitude tracking
    val rawTargetAmp = if (isRecording) audioAmplitude.coerceIn(0f, 1f) else 0f
    var prevTargetAmp by remember { mutableFloatStateOf(0f) }
    val isAttacking = rawTargetAmp > prevTargetAmp

    val animatedAmplitude by animateFloatAsState(
        targetValue = rawTargetAmp,
        animationSpec = if (isAttacking) {
            spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessHigh)
        } else {
            spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessLow)
        },
        label = "animatedAmplitude"
    )

    LaunchedEffect(rawTargetAmp) {
        prevTargetAmp = rawTargetAmp
    }

    val idleBreathing by infiniteTransition.animateFloat(
        initialValue = 0.12f,
        targetValue = 0.24f,
        animationSpec = infiniteRepeatable(
            animation = tween(2200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "idleBreathing"
    )

    var isCompletingLocally by remember { mutableStateOf(false) }
    val isFinalizing = isPendingFinalizing || isCompletingLocally
    val isFinishingActive = isFinalizing || isPolishing

    val exitAnim = remember { Animatable(0f) }
    LaunchedEffect(isFinishingActive) {
        if (isFinishingActive) {
            exitAnim.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 1400, easing = LinearEasing)
            )
        } else {
            exitAnim.snapTo(0f)
        }
    }
    val morphProgress = exitAnim.value

    val waitingLoopAnim by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "waitingLoopAnim"
    )
    val waitingLoopProgress = if (isFinishingActive && morphProgress >= 0.999f) waitingLoopAnim else 0f

    val sunsetEntranceAnim = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(350L)
        sunsetEntranceAnim.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing)
        )
    }
    val sunsetEntranceProgress = sunsetEntranceAnim.value

    LaunchedEffect(isRecording) {
        if (isRecording) {
            isCompletingLocally = false
        }
    }

    val cursorAlpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 550, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "cursorBlink"
    )

    val cornerRadius = 24.dp
    val notchRadius = 25.5.dp
    val notchCenterOffsetX = 28.dp
    val notchCenterOffsetY = 2.dp
    val filletRadius = 8.dp

    val scrollState = rememberScrollState()

    LaunchedEffect(transcriptText) {
        if (transcriptText.isNotEmpty()) {
            scrollState.animateScrollTo(scrollState.maxValue)
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 4.dp)
    ) {
        val notchedShape = remember(cornerRadius, notchRadius, notchCenterOffsetX, notchCenterOffsetY, filletRadius) {
            NotchedContainerShape(
                cornerRadius = cornerRadius,
                notchRadius = notchRadius,
                notchCenterOffsetX = notchCenterOffsetX,
                notchCenterOffsetY = notchCenterOffsetY,
                filletRadius = filletRadius
            )
        }

        // 1. Notched Glass Container Box
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 22.dp)
                .clip(notchedShape)
                .drawBehind {
                    val path = createNotchedPath(
                        size = size,
                        density = this,
                        cornerRadius = cornerRadius,
                        notchRadius = notchRadius,
                        notchCenterOffsetX = notchCenterOffsetX,
                        notchCenterOffsetY = notchCenterOffsetY,
                        filletRadius = filletRadius
                    )
                    val cx = size.width - notchCenterOffsetX.toPx()
                    val cy = notchCenterOffsetY.toPx()

                    val baseAmp = if (isRecording) maxOf(animatedAmplitude, idleBreathing) else 0.06f

                    drawNotchedOverlayBackground(
                        path = path,
                        palette = palette,
                        glowPulse = glowPulse,
                        cx = cx,
                        cy = cy,
                        styleId = glowStyleId,
                        effectiveAmp = baseAmp,
                        phase1 = phase1,
                        phase2 = phase2,
                        morphProgress = morphProgress,
                        sunsetEntranceProgress = sunsetEntranceProgress,
                        waitingLoopProgress = waitingLoopProgress
                    )
                }
                .padding(top = 16.dp, bottom = 12.dp, start = 16.dp, end = 16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .wrapContentHeight()
            ) {
                // Transcript text input box with scrolling & cursor
                FloatingTranscriptBox(
                    transcriptText = transcriptText,
                    cursorAlpha = cursorAlpha,
                    palette = palette,
                    scrollState = scrollState
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Action button row (Cancel | Polish | Complete)
                FloatingActionRow(
                    isFinalizing = isFinalizing,
                    isPolishing = isPolishing,
                    palette = palette,
                    onCancelClick = onCancelClick,
                    onPolishClick = onPolishClick,
                    onCompleteClick = {
                        if (!isFinalizing && !isPolishing) {
                            isCompletingLocally = true
                            onCompleteClick()
                        }
                    }
                )
            }
        }

        // 2. Docked Lifebuoy Ring (Unclipped, nested in notch)
        FloatingDockedLifebuoy(
            onLifebuoyClick = onLifebuoyClick,
            onDragStart = onDragStart,
            onDrag = onDrag,
            onDragEnd = onDragEnd,
            modifier = Modifier.align(Alignment.TopEnd)
        )
    }
}
