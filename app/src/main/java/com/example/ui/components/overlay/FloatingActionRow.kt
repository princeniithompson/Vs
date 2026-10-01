package com.example.ui.components.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.service.AppContextResolver
import com.example.service.FloatingBubbleManager

@Composable
fun PillActionButton(
    onClick: () -> Unit,
    backgroundColor: Color,
    borderColor: Color,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit
) {
    val shape = RoundedCornerShape(20.dp)
    Box(
        modifier = modifier
            .height(36.dp)
            .clip(shape)
            .background(backgroundColor)
            .then(
                if (borderColor != Color.Transparent) {
                    Modifier.border(1.dp, borderColor, shape)
                } else Modifier
            )
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

@Composable
fun FloatingActionRow(
    isFinalizing: Boolean,
    isPolishing: Boolean,
    palette: AuroraColorPalette,
    onCancelClick: () -> Unit,
    onPolishClick: () -> Unit,
    onCompleteClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isAiApp by FloatingBubbleManager.isCurrentAppAi.collectAsState()
    val selectedAiMode by FloatingBubbleManager.selectedAiPolishMode.collectAsState()

    Column(modifier = modifier.fillMaxWidth()) {
        // AI-Aware Mode Toggle (Displayed strictly inside AI apps)
        if (isAiApp) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(30.dp)
                    .clip(RoundedCornerShape(15.dp))
                    .background(Color(0x381E1E28))
                    .border(1.dp, Color(0x26FFFFFF), RoundedCornerShape(15.dp))
                    .padding(2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Mode 1: Clean Message
                val isClean = selectedAiMode == com.example.service.floating.AiPolishMode.CLEAN_MESSAGE
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(13.dp))
                        .background(if (isClean) Color(0x38FFFFFF) else Color.Transparent)
                        .clickable(enabled = !isPolishing && !isFinalizing) {
                            FloatingBubbleManager.setAiPolishMode(com.example.service.floating.AiPolishMode.CLEAN_MESSAGE)
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "💬 Clean Message",
                        color = if (isClean) Color.White else Color(0xFF94A3B8),
                        fontSize = 11.5.sp,
                        fontWeight = if (isClean) FontWeight.SemiBold else FontWeight.Normal
                    )
                }

                // Mode 2: Optimize as Prompt
                val isPrompt = selectedAiMode == com.example.service.floating.AiPolishMode.OPTIMIZE_PROMPT
                Box(
                    modifier = Modifier
                        .weight(1.2f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(13.dp))
                        .background(if (isPrompt) palette.primaryVibrant.copy(alpha = 0.35f) else Color.Transparent)
                        .clickable(enabled = !isPolishing && !isFinalizing) {
                            FloatingBubbleManager.setAiPolishMode(com.example.service.floating.AiPolishMode.OPTIMIZE_PROMPT)
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "✨ Optimize Prompt",
                        color = if (isPrompt) Color.White else Color(0xFF94A3B8),
                        fontSize = 11.5.sp,
                        fontWeight = if (isPrompt) FontWeight.SemiBold else FontWeight.Normal
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 1. Cancel Button
            PillActionButton(
                onClick = onCancelClick,
                backgroundColor = Color(0x381E1E24),
                borderColor = Color(0x26FF5252),
                enabled = !isFinalizing && !isPolishing,
                modifier = Modifier.weight(1f)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "Cancel",
                        tint = Color(0xFFFF5252),
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                    Text(
                        text = "Cancel",
                        color = Color(0xFFFF7B7B),
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            // 2. Polish Button
            val polishLabel = when {
                isPolishing -> "Polishing..."
                !isAiApp -> "Polish"
                selectedAiMode == com.example.service.floating.AiPolishMode.OPTIMIZE_PROMPT -> "Optimize"
                else -> "Clean"
            }

            PillActionButton(
                onClick = onPolishClick,
                backgroundColor = Color(0x24FFFFFF),
                borderColor = Color(0x24FFFFFF),
                enabled = !isPolishing && !isFinalizing,
                modifier = Modifier.weight(1f)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    if (isPolishing) {
                        Text(
                            text = "Polishing...",
                            color = Color(0xFFE2E8F0),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                    } else {
                        Text(
                            text = if (isAiApp && selectedAiMode == com.example.service.floating.AiPolishMode.OPTIMIZE_PROMPT) "✨" else "✦",
                            color = palette.primaryVibrant,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            text = polishLabel,
                            color = Color(0xFFE2E8F0),
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            // 3. Complete Button
            val onAccentColor = if (isColorDark(palette.primaryVibrant)) Color.White else Color(0xFF042F2E)

            PillActionButton(
                onClick = onCompleteClick,
                backgroundColor = if (isFinalizing) palette.primaryVibrant.copy(alpha = 0.85f) else palette.primaryVibrant,
                borderColor = Color.Transparent,
                enabled = !isFinalizing && !isPolishing,
                modifier = Modifier.weight(1.2f)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    if (isFinalizing) {
                        Text(
                            text = "Completing...",
                            color = onAccentColor,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Filled.Check,
                            contentDescription = "Complete",
                            tint = onAccentColor,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            text = "Complete",
                            color = onAccentColor,
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }

        // Context Status (e.g. "AI · VoxStream")
        val sessionContext by FloatingBubbleManager.lockedSessionContext.collectAsState()
        val currentPkg by FloatingBubbleManager.currentForegroundPackage.collectAsState()
        val context = LocalContext.current
        val displayContext = sessionContext ?: remember(currentPkg) {
            AppContextResolver.resolve(context, currentPkg)?.formatted
        }

        if (!displayContext.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(5.dp))
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = displayContext,
                    style = TextStyle(
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                        fontSize = 10.5.sp,
                        fontFamily = FontFamily.SansSerif,
                        fontWeight = FontWeight.Normal,
                        letterSpacing = 0.2.sp
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
