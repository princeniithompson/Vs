package com.example.ui.components.overlay

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.service.floating.FloatingHapticManager
import com.example.service.floating.FloatingHapticType
import kotlinx.coroutines.delay

/**
 * Smart Safe Mode Locked Shield Badge:
 * Rendered when the user is currently in a sensitive application (banking, crypto, password manager).
 * Shrinks into a sleek locked shield icon that gently indicates dictation is paused for privacy.
 */
@Composable
fun FloatingSafeModeShieldBadge(
    isSnappedToRight: Boolean = true,
    onClick: () -> Unit = {},
    onDragStart: () -> Unit = {},
    onDrag: (Float, Float) -> Unit = { _, _ -> },
    onDragEnd: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var showTooltip by remember { mutableStateOf(false) }

    // Auto-dismiss tooltip after 3 seconds
    LaunchedEffect(showTooltip) {
        if (showTooltip) {
            delay(3000)
            showTooltip = false
        }
    }

    val infiniteTransition = rememberInfiniteTransition(label = "shieldAura")
    val pulse by infiniteTransition.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    val amberBorder = Color(0xFFF59E0B)
    val amberGlow = Color(0x33F59E0B)
    val badgeBackground = Color(0xF0181E2A)

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
                onClick = {
                    FloatingHapticManager.trigger(context, FloatingHapticType.BUBBLE_HOLD)
                    showTooltip = !showTooltip
                    onClick()
                }
            ),
        contentAlignment = if (isSnappedToRight) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        // Outer Shield Container (44dp compact size)
        Box(
            modifier = Modifier
                .size(46.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(badgeBackground)
                .border(
                    width = 1.5.dp,
                    brush = Brush.linearGradient(
                        listOf(amberBorder, Color(0xFFD97706), amberBorder)
                    ),
                    shape = RoundedCornerShape(16.dp)
                )
                .shadow(elevation = 6.dp, shape = RoundedCornerShape(16.dp)),
            contentAlignment = Alignment.Center
        ) {
            // Reassuring soft pulsing glow inside
            Box(
                modifier = Modifier
                    .size(36.dp * pulse)
                    .clip(CircleShape)
                    .background(amberGlow)
            )

            // Shield Icon with Padlock
            Image(
                painter = painterResource(id = R.drawable.ic_shield_locked),
                contentDescription = "Smart Safe Mode Active",
                modifier = Modifier.size(28.dp)
            )
        }

        // Animated Floating Safety Info Pill (appears when tapped)
        AnimatedVisibility(
            visible = showTooltip,
            enter = fadeIn(tween(200)) + scaleIn(tween(200)),
            exit = fadeOut(tween(200)) + scaleOut(tween(200)),
            modifier = Modifier
                .align(if (isSnappedToRight) Alignment.CenterEnd else Alignment.CenterStart)
                .padding(end = if (isSnappedToRight) 56.dp else 0.dp, start = if (!isSnappedToRight) 56.dp else 0.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = Color(0xF8111827),
                shadowElevation = 8.dp,
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF374151)),
                modifier = Modifier
                    .widthIn(max = 240.dp)
                    .clickable { showTooltip = false }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Filled.Lock,
                        contentDescription = null,
                        tint = amberBorder,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "Smart Safe Mode",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFF9FAFB)
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Dictation paused in banking & password apps for privacy.",
                            style = MaterialTheme.typography.bodySmall,
                            fontSize = 11.sp,
                            lineHeight = 14.sp,
                            color = Color(0xFFD1D5DB)
                        )
                    }
                }
            }
        }
    }
}
