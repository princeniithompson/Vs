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
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.example.R

/**
 * Collapsed floating lifebuoy bubble when the full popup is minimized.
 * - Starts at full 56dp size.
 * - Smoothly shrinks to a compact 45dp bubble after inactivity.
 * - Aligns flush against screen bezels.
 */
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

    val animatedBubbleSize by animateDpAsState(
        targetValue = if (isShrunk) 45.dp else 56.dp,
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
        targetValue = if (isShrunk) 0.85f else 1.0f,
        animationSpec = tween(300),
        label = "bubbleAlpha"
    )

    Box(
        modifier = modifier
            .size(56.dp)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { onDragStart() },
                    onDragEnd = { onDragEnd() },
                    onDragCancel = { onDragEnd() },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        onDrag(dragAmount.x, dragAmount.y)
                    }
                )
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            ),
        contentAlignment = if (isSnappedToRight) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        Box(
            modifier = Modifier
                .size(animatedBubbleSize)
                .clip(CircleShape),
            contentAlignment = Alignment.Center
        ) {
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
        }
    }
}
