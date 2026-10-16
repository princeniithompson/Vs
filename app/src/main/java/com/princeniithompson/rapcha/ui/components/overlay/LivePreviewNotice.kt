package com.princeniithompson.rapcha.ui.components.overlay

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.princeniithompson.rapcha.websocket.InterimPreviewMonitor

@Composable
fun LivePreviewNotice(
    phase: InterimPreviewMonitor.Phase,
    polishingMessage: String?,
    modifier: Modifier = Modifier
) {
    val pulse = rememberInfiniteTransition(label = "livePreviewNotice")
    val alpha by pulse.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 700, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "livePreviewPulse"
    )
    val text = when {
        !polishingMessage.isNullOrBlank() -> polishingMessage
        phase == InterimPreviewMonitor.Phase.PREVIEW_DELAYED -> "Still listening, live preview delayed"
        phase == InterimPreviewMonitor.Phase.CONNECTION_LOST -> "Connection dropped. Audio is not being sent."
        else -> null
    } ?: return
    val color = if (phase == InterimPreviewMonitor.Phase.CONNECTION_LOST && polishingMessage.isNullOrBlank()) {
        Color(0xFFFDA4AF)
    } else {
        Color(0xFFE2E8F0)
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 6.dp, end = 36.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Spacer(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color.copy(alpha = alpha))
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = text,
            color = color,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            lineHeight = 17.sp
        )
    }
}
