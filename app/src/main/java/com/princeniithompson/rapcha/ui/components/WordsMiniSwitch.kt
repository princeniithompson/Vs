package com.princeniithompson.rapcha.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.princeniithompson.rapcha.service.floating.BubbleChromeStyle

/**
 * Clean Words/Mini Segmented Switch
 * 52dp tall, fully rounded (percent = 50), flat track, raised tonal thumb with shadow/highlight.
 */
@Composable
fun WordsMiniSwitch(
    active: BubbleChromeStyle,
    onChoose: (BubbleChromeStyle) -> Unit,
    modifier: Modifier = Modifier
) {
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val wordsInteraction = remember { MutableInteractionSource() }
    val miniInteraction = remember { MutableInteractionSource() }
    val wordsPressed by wordsInteraction.collectIsPressedAsState()
    val miniPressed by miniInteraction.collectIsPressedAsState()
    val isPressed = wordsPressed || miniPressed

    val pressScale by animateFloatAsState(
        targetValue = if (isPressed) 0.97f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "pressScale"
    )

    val fraction by animateFloatAsState(
        targetValue = if (active == BubbleChromeStyle.COMPACT) 1f else 0f,
        animationSpec = spring(
            dampingRatio = 0.75f,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "thumbFraction"
    )

    var trackSize by remember { mutableStateOf(IntSize.Zero) }

    val trackColor = if (isDark) {
        Color.White.copy(alpha = 0.08f)
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)
    }

    val thumbColor = MaterialTheme.colorScheme.primaryContainer
    val selectedTextColor = MaterialTheme.colorScheme.onPrimaryContainer
    val unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant

    val shape = RoundedCornerShape(percent = 50)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp)
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            }
            .clip(shape)
            .background(trackColor)
            .padding(4.dp)
            .onSizeChanged { trackSize = it }
    ) {
        // Draw Thumb
        if (trackSize.width > 0) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .drawBehind {
                        val thumbW = (size.width) / 2f
                        val thumbH = size.height
                        val left = fraction * thumbW
                        val radius = thumbH / 2f

                        if (isDark) {
                            // Dark mode: faint top highlight
                            drawRoundRect(
                                color = thumbColor,
                                topLeft = Offset(left, 0f),
                                size = Size(thumbW, thumbH),
                                cornerRadius = CornerRadius(radius, radius)
                            )
                            drawRoundRect(
                                color = Color.White.copy(alpha = 0.12f),
                                topLeft = Offset(left, 0f),
                                size = Size(thumbW, 1.dp.toPx()),
                                cornerRadius = CornerRadius(radius, radius)
                            )
                        } else {
                            // Light mode: soft 2-layer ambient and spot shadow
                            drawRoundRect(
                                color = Color.Black.copy(alpha = 0.06f),
                                topLeft = Offset(left, 1.5.dp.toPx()),
                                size = Size(thumbW, thumbH),
                                cornerRadius = CornerRadius(radius, radius)
                            )
                            drawRoundRect(
                                color = Color.Black.copy(alpha = 0.08f),
                                topLeft = Offset(left, 3.dp.toPx()),
                                size = Size(thumbW, thumbH),
                                cornerRadius = CornerRadius(radius, radius)
                            )
                            drawRoundRect(
                                color = thumbColor,
                                topLeft = Offset(left, 0f),
                                size = Size(thumbW, thumbH),
                                cornerRadius = CornerRadius(radius, radius)
                            )
                        }
                    }
            )
        }

        // Labels Row
        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val wordsColor = lerp(selectedTextColor, unselectedTextColor, fraction)
            val miniColor = lerp(unselectedTextColor, selectedTextColor, fraction)

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(shape)
                    .clickable(
                        interactionSource = wordsInteraction,
                        indication = null
                    ) { onChoose(BubbleChromeStyle.ORIGINAL) },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Words",
                    color = wordsColor,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(shape)
                    .clickable(
                        interactionSource = miniInteraction,
                        indication = null
                    ) { onChoose(BubbleChromeStyle.COMPACT) },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Mini",
                    color = miniColor,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}
