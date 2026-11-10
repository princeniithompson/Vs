package com.princeniithompson.rapcha.ui.components.overlay

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.Image
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.princeniithompson.rapcha.R
import com.princeniithompson.rapcha.service.floating.BubbleChromeGeometry
import com.princeniithompson.rapcha.service.floating.CompactGlowLevel
import com.princeniithompson.rapcha.service.floating.SessionClockFormat
import com.princeniithompson.rapcha.ui.components.renderGlowAnimation
import kotlin.math.PI

/**
 * Compact recording chrome. The transcript stays off this surface.
 * The glow is the same renderer used by the original container.
 */
@Composable
fun CompactRecordingBar(
    elapsedSeconds: Int,
    appName: String,
    glowStyleId: String,
    palette: AuroraColorPalette,
    audioAmplitude: Float = 0.35f,
    processing: Boolean = false,
    statusLabel: String? = null,
    onCancel: () -> Unit,
    onComplete: () -> Unit,
    onDragStart: () -> Unit = {},
    onDrag: (Float, Float) -> Unit = { _, _ -> },
    onDragEnd: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val motion = rememberInfiniteTransition(label = "compactGlow")
    val phase1 by motion.animateFloat(
        initialValue = 0f,
        targetValue = (2f * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(2600, easing = LinearEasing), RepeatMode.Restart),
        label = "p1"
    )
    val phase2 by motion.animateFloat(
        initialValue = (2f * PI).toFloat(),
        targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(3600, easing = LinearEasing), RepeatMode.Restart),
        label = "p2"
    )
    val spin by motion.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(2500, easing = LinearEasing), RepeatMode.Restart),
        label = "spin"
    )
    val level = CompactGlowLevel.fromAmplitude(audioAmplitude)
    val pillShape = RoundedCornerShape((BubbleChromeGeometry.PILL_HEIGHT_DP / 2f).dp)
    Row(
        modifier = modifier.padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(BubbleChromeGeometry.GAP_DP.dp)
    ) {
        Box(
            modifier = Modifier
                .size(BubbleChromeGeometry.CANCEL_DP.dp)
                .clip(CircleShape)
                .background(Color.White)
                .clickable(onClick = onCancel),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = "Cancel",
                tint = Color(0xFF1A202C),
                modifier = Modifier.size(22.dp)
            )
        }
        Box(
            modifier = Modifier
                .width(BubbleChromeGeometry.PILL_WIDTH_DP.dp)
                .height(BubbleChromeGeometry.PILL_HEIGHT_DP.dp)
                .clip(pillShape)
                .border(1.dp, Color.White.copy(alpha = 0.35f), pillShape)
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { onDragStart() },
                        onDragEnd = { onDragEnd() },
                        onDragCancel = { onDragEnd() }
                    ) { change, drag ->
                        change.consume()
                        onDrag(drag.x, drag.y)
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.matchParentSize()) {
                renderGlowAnimation(
                    styleId = glowStyleId,
                    palette = palette,
                    effectiveAmp = level,
                    glowBrightness = 1f,
                    phase1 = phase1,
                    phase2 = phase2
                )
            }
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(Color.Black.copy(alpha = 0.28f))
            )
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = if (processing) "Polishing..." else SessionClockFormat.format(elapsedSeconds),
                    color = Color.White,
                    fontSize = if (processing) 11.sp else 16.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
                if (!processing) {
                    Text(
                        text = statusLabel?.takeIf { it.isNotBlank() } ?: appName.ifBlank { "App" },
                        color = Color.White.copy(alpha = 0.92f),
                        fontSize = 10.sp,
                        maxLines = 1
                    )
                }
            }
        }
        Box(
            modifier = Modifier
                .size(BubbleChromeGeometry.COMPLETE_DP.dp)
                .clip(CircleShape)
                .clickable(onClick = onComplete),
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = painterResource(id = R.drawable.ic_lifebuoy_ring),
                contentDescription = "Complete",
                modifier = Modifier
                    .size(BubbleChromeGeometry.COMPLETE_DP.dp)
                    .graphicsLayer { if (processing) rotationZ = spin }
            )
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = Color(0xFF111111),
                modifier = Modifier
                    .size(16.dp)
                    .graphicsLayer { if (processing) rotationZ = spin }
            )
        }
    }
}
