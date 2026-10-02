package com.example.ui.components.overlay

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Full-screen futuristic semi-transparent overlay displaying Expanding Aurora Rings
 * while capturing AI application context.
 */
@Composable
fun FloatingScreenScanOverlay(
    isScanning: Boolean,
    appName: String? = null,
    originX: Float = 0.5f,
    originY: Float = 0.5f,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = isScanning,
        enter = fadeIn(animationSpec = tween(250)),
        exit = fadeOut(animationSpec = tween(300))
    ) {
        val infiniteTransition = rememberInfiniteTransition(label = "auroraRings")

        // 3 staggered expanding rings for organic, vibrant aurora wave motion
        val ring1Progress by infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(1800, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "ring1"
        )
        val ring2Progress by infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(1800, delayMillis = 600, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "ring2"
        )
        val ring3Progress by infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(1800, delayMillis = 1200, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "ring3"
        )

        val pulseGlow by infiniteTransition.animateFloat(
            initialValue = 0.8f,
            targetValue = 1.2f,
            animationSpec = infiniteRepeatable(
                animation = tween(900, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "pulseGlow"
        )

        Box(
            modifier = modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            Color(0x990A192F),
                            Color(0xCC060D1A),
                            Color(0xE602050B)
                        ),
                        center = Offset.Unspecified,
                        radius = 1200f
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            // Expanding Aurora Rings Canvas
            Canvas(modifier = Modifier.fillMaxSize()) {
                val centerOffset = Offset(
                    x = size.width * originX.coerceIn(0.1f, 0.9f),
                    y = size.height * originY.coerceIn(0.1f, 0.9f)
                )
                val maxRadius = kotlin.math.hypot(size.width, size.height) * 0.75f

                listOf(ring1Progress, ring2Progress, ring3Progress).forEachIndexed { index, progress ->
                    val currentRadius = maxRadius * progress
                    val alpha = (1f - progress).coerceIn(0f, 1f) * 0.75f

                    val color = when (index) {
                        0 -> Color(0xFF00E5FF) // Electric Cyan
                        1 -> Color(0xFF2979FF) // Vibrant Blue
                        else -> Color(0xFF7C4DFF) // Purple Neon
                    }.copy(alpha = alpha)

                    drawCircle(
                        color = color,
                        radius = currentRadius,
                        center = centerOffset,
                        style = Stroke(width = (6.dp.toPx() * (1f - progress * 0.5f)))
                    )
                }
            }

            // Centered Modern Status HUD Glass Pill
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color(0x880E1E38))
                    .border(
                        width = 1.5.dp,
                        brush = Brush.horizontalGradient(
                            listOf(Color(0xFF00E5FF), Color(0xFF2979FF), Color(0xFF7C4DFF))
                        ),
                        shape = RoundedCornerShape(24.dp)
                    )
                    .padding(horizontal = 24.dp, vertical = 20.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    // Pulsing Glowing Lens Icon
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .background(
                                Brush.radialGradient(
                                    listOf(Color(0xFF00E5FF).copy(alpha = 0.4f * pulseGlow), Color.Transparent)
                                ),
                                CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.CenterFocusStrong,
                            contentDescription = "Scanning Lens",
                            tint = Color(0xFF00E5FF),
                            modifier = Modifier.size(32.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = "Scanning screen content…",
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    val targetLabel = if (!appName.isNullOrBlank()) "Capturing $appName context" else "AI Chat Context"
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Filled.AutoAwesome,
                            contentDescription = null,
                            tint = Color(0xFF64FFDA),
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = targetLabel,
                            color = Color(0xFF64FFDA),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }
    }
}
