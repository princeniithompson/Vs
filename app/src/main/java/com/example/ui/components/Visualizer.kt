package com.example.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import kotlin.math.sin

/**
 * Dynamic audio waveform visualizer that renders live bars reacting to PCM amplitude.
 */
@Composable
fun AudioWaveformVisualizer(
    amplitude: Float,
    isRecording: Boolean,
    modifier: Modifier = Modifier
) {
    val barCount = 36
    val animatedAmp = remember { Animatable(0f) }

    LaunchedEffect(amplitude, isRecording) {
        if (isRecording) {
            animatedAmp.animateTo(
                targetValue = amplitude.coerceIn(0.05f, 1f),
                animationSpec = tween(durationMillis = 80, easing = LinearEasing)
            )
        } else {
            animatedAmp.animateTo(0f, animationSpec = tween(150))
        }
    }

    val infiniteTransition = rememberInfiniteTransition(label = "waveform_motion")
    val phase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "phase"
    )

    val primaryColor = MaterialTheme.colorScheme.primary
    val tertiaryColor = MaterialTheme.colorScheme.tertiary
    val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(64.dp)
            .testTag("waveform_canvas")
    ) {
        val width = size.width
        val height = size.height
        val centerY = height / 2f
        val currentAmp = animatedAmp.value

        val totalSpacing = width / barCount
        val barWidth = (totalSpacing * 0.55f).coerceAtLeast(2.dp.toPx())

        for (i in 0 until barCount) {
            val progress = i.toFloat() / barCount
            // Harmonic wave modulation
            val wave = sin((progress * 4 * Math.PI + phase).toDouble()).toFloat()
            val waveFactor = 0.35f + 0.65f * ((wave + 1f) / 2f)

            val minBarHeight = 4.dp.toPx()
            val maxBarHeight = height * 0.9f
            val dynamicHeight = if (isRecording) {
                (minBarHeight + (maxBarHeight - minBarHeight) * currentAmp * waveFactor)
            } else {
                minBarHeight
            }

            val x = i * totalSpacing + (totalSpacing - barWidth) / 2f
            val top = centerY - dynamicHeight / 2f

            val brush = if (isRecording) {
                Brush.verticalGradient(
                    colors = listOf(primaryColor, tertiaryColor),
                    startY = top,
                    endY = top + dynamicHeight
                )
            } else {
                Brush.verticalGradient(
                    colors = listOf(surfaceVariant.copy(alpha = 0.5f), surfaceVariant),
                    startY = top,
                    endY = top + dynamicHeight
                )
            }

            drawRoundRect(
                brush = brush,
                topLeft = Offset(x, top),
                size = Size(barWidth, dynamicHeight),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
            )
        }
    }
}

/**
 * Large central Record button with pulsating rings, active state transitions,
 * and minimum 72dp touch target.
 */
@Composable
fun PulsatingRecordButton(
    isRecording: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse_rings")

    val pulseScale1 by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.45f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulse_1"
    )

    val pulseAlpha1 by infiniteTransition.animateFloat(
        initialValue = 0.6f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulse_alpha_1"
    )

    val pulseScale2 by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.7f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, delayMillis = 400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulse_2"
    )

    val pulseAlpha2 by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, delayMillis = 400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulse_alpha_2"
    )

    val primaryColor = MaterialTheme.colorScheme.primary
    val errorColor = MaterialTheme.colorScheme.error

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(110.dp)
            .testTag("record_button_container")
    ) {
        // Outer pulsating glow rings when recording
        if (isRecording) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val center = Offset(size.width / 2f, size.height / 2f)
                val baseRadius = 38.dp.toPx()

                // Ring 2
                drawCircle(
                    color = errorColor.copy(alpha = pulseAlpha2),
                    radius = baseRadius * pulseScale2,
                    center = center,
                    style = Stroke(width = 2.dp.toPx())
                )

                // Ring 1
                drawCircle(
                    color = errorColor.copy(alpha = pulseAlpha1),
                    radius = baseRadius * pulseScale1,
                    center = center,
                    style = Stroke(width = 3.dp.toPx())
                )
            }
        }

        // Center Action Button
        val buttonColor = if (isRecording) errorColor else primaryColor
        val interactionSource = remember { MutableInteractionSource() }

        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(76.dp)
                .clip(CircleShape)
                .background(buttonColor)
                .clickable(
                    interactionSource = interactionSource,
                    indication = ripple(bounded = true, radius = 48.dp, color = Color.White),
                    role = Role.Button,
                    onClick = onClick
                )
                .testTag("record_toggle_button")
        ) {
            Icon(
                imageVector = if (isRecording) Icons.Filled.Stop else Icons.Filled.Mic,
                contentDescription = if (isRecording) "Stop voice typing" else "Start voice typing",
                tint = Color.White,
                modifier = Modifier
                    .size(36.dp)
                    .scale(if (isRecording) 1.1f else 1f)
            )
        }
    }
}
