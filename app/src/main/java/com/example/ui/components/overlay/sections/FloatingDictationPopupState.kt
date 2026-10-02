package com.example.ui.components.overlay.sections

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
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay

/**
 * State holder managing animations, audio amplitude smoothing, and completion transitions
 * for the floating dictation popup.
 */
@Stable
class FloatingDictationPopupState(
    val glowPulse: Float,
    val phase1: Float,
    val phase2: Float,
    val animatedAmplitude: Float,
    val idleBreathing: Float,
    val cursorAlpha: Float,
    val morphProgress: Float,
    val sunsetEntranceProgress: Float,
    val waitingLoopProgress: Float,
    val isFinalizing: Boolean,
    val isPolishing: Boolean,
    val scrollState: ScrollState,
    private val onLocalCompleteTriggered: () -> Unit
) {
    val effectiveAmp: Float
        get() = maxOf(animatedAmplitude, idleBreathing)

    fun markLocalComplete() {
        onLocalCompleteTriggered()
    }
}

@Composable
fun rememberFloatingDictationPopupState(
    transcriptText: String,
    isRecording: Boolean,
    isPendingFinalizing: Boolean,
    isPolishing: Boolean,
    audioAmplitude: Float
): FloatingDictationPopupState {
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

    val scrollState = rememberScrollState()
    LaunchedEffect(transcriptText) {
        if (transcriptText.isNotEmpty()) {
            scrollState.animateScrollTo(scrollState.maxValue)
        }
    }

    return remember(
        glowPulse, phase1, phase2, animatedAmplitude, idleBreathing,
        cursorAlpha, morphProgress, sunsetEntranceProgress, waitingLoopProgress,
        isFinalizing, isPolishing, scrollState
    ) {
        FloatingDictationPopupState(
            glowPulse = glowPulse,
            phase1 = phase1,
            phase2 = phase2,
            animatedAmplitude = animatedAmplitude,
            idleBreathing = idleBreathing,
            cursorAlpha = cursorAlpha,
            morphProgress = morphProgress,
            sunsetEntranceProgress = sunsetEntranceProgress,
            waitingLoopProgress = waitingLoopProgress,
            isFinalizing = isFinalizing,
            isPolishing = isPolishing,
            scrollState = scrollState,
            onLocalCompleteTriggered = {
                isCompletingLocally = true
            }
        )
    }
}
