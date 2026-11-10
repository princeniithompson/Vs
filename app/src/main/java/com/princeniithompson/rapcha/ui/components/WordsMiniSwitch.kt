package com.princeniithompson.rapcha.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.princeniithompson.rapcha.service.floating.BubbleChromeStyle

/**
 * Raised Words/Mini control. Choosing a side saves that bubble.
 * The carousel scroll is the caller's job.
 */
@Composable
fun WordsMiniSwitch(
    active: BubbleChromeStyle,
    onChoose: (BubbleChromeStyle) -> Unit,
    modifier: Modifier = Modifier,
    rimColor: Color = Color(0xFFEEF6DC),
    trackColor: Color = Color(0xFFB7D07A)
) {
    val wordsInteraction = remember { MutableInteractionSource() }
    val miniInteraction = remember { MutableInteractionSource() }
    val wordsPressed by wordsInteraction.collectIsPressedAsState()
    val miniPressed by miniInteraction.collectIsPressedAsState()
    val pressed = wordsPressed || miniPressed
    val fraction by animateFloatAsState(
        targetValue = if (active == BubbleChromeStyle.COMPACT) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow),
        label = "thumb"
    )
    var trackSize by remember { mutableStateOf(IntSize.Zero) }
    val rim = RoundedCornerShape(percent = 50)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .shadow(8.dp, rim, ambientColor = Color(0x33000000), spotColor = Color(0x33000000))
            .clip(rim)
            .drawBehind {
                drawRect(rimColor)
                drawLine(
                    color = Color.White,
                    start = Offset(0f, 1.dp.toPx()),
                    end = Offset(size.width, 1.dp.toPx()),
                    strokeWidth = 1.dp.toPx()
                )
            }
            .padding(6.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp)
                .onSizeChanged { trackSize = it }
                .clip(RoundedCornerShape(34.dp))
                .drawBehind { drawRect(trackColor) }
        ) {
            if (trackSize.width > 0) {
                val inset = 5.dp
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val insetPx = inset.toPx()
                    val thumbW = (size.width - insetPx * 2f) / 2f
                    val left = insetPx + fraction * thumbW
                    val top = insetPx
                    val height = size.height - insetPx * 2f
                    val radius = height / 2f
                    val darken = if (pressed) 0.05f else 0f
                    val drop = if (pressed) 1.dp.toPx() else 4.dp.toPx()
                    drawRoundRect(
                        color = Color(0x33000000),
                        topLeft = Offset(left, top + drop),
                        size = androidx.compose.ui.geometry.Size(thumbW, height),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius, radius)
                    )
                    drawRoundRect(
                        brush = Brush.verticalGradient(
                            listOf(darken(0xFF9BC21F.toInt(), darken), darken(0xFF5F8A0F.toInt(), darken)),
                            startY = top,
                            endY = top + height
                        ),
                        topLeft = Offset(left, top),
                        size = androidx.compose.ui.geometry.Size(thumbW, height),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius, radius)
                    )
                    clipRect(left, top, left + thumbW, top + height) {
                        drawWoven(left, top, thumbW, height)
                    }
                    drawRoundRect(
                        color = Color(0xFFEAF4CF),
                        topLeft = Offset(left, top),
                        size = androidx.compose.ui.geometry.Size(thumbW, height),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius, radius),
                        style = Stroke(width = 3.dp.toPx())
                    )
                }
            }
        }
        Row(modifier = Modifier.fillMaxWidth().height(60.dp)) {
            SwitchLabel("Words", Modifier.weight(1f).fillMaxHeight().clickable(
                interactionSource = wordsInteraction,
                indication = null
            ) { onChoose(BubbleChromeStyle.ORIGINAL) })
            SwitchLabel("Mini", Modifier.weight(1f).fillMaxHeight().clickable(
                interactionSource = miniInteraction,
                indication = null
            ) { onChoose(BubbleChromeStyle.COMPACT) })
        }
    }
}

@Composable
private fun SwitchLabel(text: String, modifier: Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Text(
            text = text,
            color = Color.White,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            style = TextStyle(
                shadow = Shadow(color = Color(0xFF1A2A0A), offset = Offset(0f, 1.5f), blurRadius = 2f)
            )
        )
    }
}

private fun darken(argb: Int, amount: Float): Color {
    val color = Color(argb)
    val keep = 1f - amount
    return Color(color.red * keep, color.green * keep, color.blue * keep, 1f)
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawWoven(
    left: Float,
    top: Float,
    width: Float,
    height: Float
) {
    val stripe = 6.dp.toPx()
    val span = width + height
    var offset = -span
    while (offset < span) {
        drawLine(
            color = Color.White.copy(alpha = 0.18f),
            start = Offset(left + offset, top + height),
            end = Offset(left + offset + height, top),
            strokeWidth = stripe
        )
        drawLine(
            color = Color.Black.copy(alpha = 0.12f),
            start = Offset(left + offset, top),
            end = Offset(left + offset + height, top + height),
            strokeWidth = stripe
        )
        offset += stripe * 2f
    }
}
