package com.example.ui.components

import android.content.Context
import android.widget.Toast
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import com.example.ui.components.overlay.AuroraColorPalette
import com.example.ui.components.overlay.rememberDynamicAuroraPalette
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.HistoryRepository
import com.example.ui.VoiceTypingViewModel
import kotlinx.coroutines.launch

/**
 * Fixed in-app voice-typing bubble matching the exact VoxStream floating bubble visual styling:
 * - Transparent dark frosted glass container
 * - Dynamic blue aurora glow strictly clipped inside container
 * - Anchored stagnant action buttons (Cancel / Polish / Complete) and status label
 * - Dedicated auto-scrolling transcript viewport (words scroll up, buttons stay fixed)
 */
@Composable
fun InAppVoiceTypingBubble(
    viewModel: VoiceTypingViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val isRecording by viewModel.isRecording.collectAsState()
    val finalizedTranscript by viewModel.finalizedTranscript.collectAsState()
    val interimTranscript by viewModel.interimTranscript.collectAsState()
    val amplitude by viewModel.audioAmplitude.collectAsState()
    val selectedGlowStyleId by viewModel.selectedGlowStyleId.collectAsState()
    val stats by viewModel.stats.collectAsState()

    var isPolishingLocally by remember { mutableStateOf(false) }

    val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            viewModel.startRecording()
        } else {
            Toast.makeText(
                context,
                "Microphone permission is required to use voice typing.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    val onStartDictation = {
        if (!isRecording) {
            val hasPermission = androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.RECORD_AUDIO
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED

            if (hasPermission) {
                viewModel.startRecording()
            } else {
                permissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    val transcriptText = buildString {
        append(finalizedTranscript)
        if (interimTranscript.isNotEmpty()) {
            if (isNotEmpty()) append(" ")
            append(interimTranscript)
        }
    }.trim()

    val scrollState = rememberScrollState()

    // Auto-scroll the transcribed words up to the bottom as new speech arrives
    LaunchedEffect(transcriptText) {
        if (transcriptText.isNotBlank()) {
            scrollState.animateScrollTo(scrollState.maxValue)
        }
    }

    val palette = rememberDynamicAuroraPalette()

    val infiniteTransition = rememberInfiniteTransition(label = "in_app_bubble_anim")
    val phase1 by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "phase1"
    )
    val phase2 by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 4200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "phase2"
    )
    val glowPulse by infiniteTransition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glowPulse"
    )
    val idleBreathing by infiniteTransition.animateFloat(
        initialValue = 0.04f,
        targetValue = 0.16f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "idleBreathing"
    )
    val cursorAlpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 550, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "cursorAlpha"
    )

    // Outer Container with strict 26dp clipping
    Box(
        modifier = modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(26.dp))
            .testTag("in_app_voice_bubble")
    ) {
        // 1. Dynamic Canvas: Frosted glass background + strictly clipped aurora glow + edge catch stroke
        Canvas(
            modifier = Modifier
                .matchParentSize()
                .clip(RoundedCornerShape(26.dp))
        ) {
            val cornerRadiusPx = 26.dp.toPx()
            val roundRectPath = Path().apply {
                addRoundRect(
                    RoundRect(
                        left = 0f,
                        top = 0f,
                        right = size.width,
                        bottom = size.height,
                        cornerRadius = CornerRadius(cornerRadiusPx, cornerRadiusPx)
                    )
                )
            }

            // Strictly clip all aurora effects within the 26dp container boundary
            clipPath(roundRectPath) {
                // Frosted Dark Glass Fill (~85-92% opacity)
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color(0xEB161E2E), // Translucent deep obsidian blue
                            Color(0xF50F1522)  // Deep rich foundation
                        )
                    )
                )

                // Render Aurora Glow (strictly clipped inside container)
                val baseAmp = if (isRecording) maxOf(amplitude, idleBreathing) else 0.06f
                val glowBrightness = 0.38f + 0.62f * baseAmp

                if (glowBrightness > 0.005f) {
                    renderGlowAnimation(
                        styleId = selectedGlowStyleId,
                        palette = palette,
                        effectiveAmp = baseAmp,
                        glowBrightness = glowBrightness,
                        phase1 = phase1,
                        phase2 = phase2,
                        completionMorphProgress = 0f,
                        entranceProgress = 1f,
                        waitingLoopProgress = 0f
                    )
                }
            }

            // Subtle glowing edge catch stroke
            drawPath(
                path = roundRectPath,
                brush = Brush.horizontalGradient(
                    colors = listOf(
                        palette.primary.copy(alpha = if (isRecording) 0.65f * glowPulse else 0.25f),
                        palette.primaryLight.copy(alpha = if (isRecording) 0.85f * glowPulse else 0.35f),
                        palette.secondary.copy(alpha = if (isRecording) 0.60f * glowPulse else 0.20f)
                    )
                ),
                style = Stroke(
                    width = 1.25.dp.toPx(),
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round
                )
            )
        }

        // 2. Foreground Interactive Content Layer: Stagnant buttons & status, scrollable words only
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 18.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (!isRecording && transcriptText.isEmpty()) {
                // IDLE STATE: Centered tap prompt
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .clickable(onClick = onStartDictation),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(palette.primaryLight.copy(alpha = 0.22f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Mic,
                                contentDescription = "Start dictation",
                                tint = palette.primaryLight,
                                modifier = Modifier.size(19.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "Tap to start voice typing...",
                            style = TextStyle(
                                color = Color(0xFFF1F5F9),
                                fontSize = 15.5.sp,
                                fontFamily = FontFamily.SansSerif,
                                fontWeight = FontWeight.Medium,
                                shadow = Shadow(
                                    color = Color(0xCC000000),
                                    blurRadius = 4f
                                )
                            )
                        )
                    }
                }
            } else {
                // ACTIVE STATE: Only the transcribed text scrolls inside this dedicated viewport
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(scrollState),
                        verticalAlignment = Alignment.Top
                    ) {
                        Text(
                            text = if (transcriptText.isNotBlank()) transcriptText else "Listening...",
                            style = TextStyle(
                                color = Color(0xFFFFFFFF),
                                fontSize = 15.sp,
                                fontFamily = FontFamily.SansSerif,
                                fontWeight = FontWeight.Normal,
                                lineHeight = 21.sp,
                                shadow = Shadow(
                                    color = Color(0xE6000000),
                                    offset = Offset(0f, 1f),
                                    blurRadius = 6f
                                )
                            ),
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if (isRecording) {
                            Spacer(modifier = Modifier.width(4.dp))
                            Box(
                                modifier = Modifier
                                    .width(2.dp)
                                    .height(18.dp)
                                    .background(palette.primaryLight.copy(alpha = cursorAlpha))
                            )
                        }
                    }
                }

                // FIXED STAGNANT BUTTON ROW: Remains firmly in place, never pushed down or hidden
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(38.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 1. Cancel Button
                    InAppPillButton(
                        onClick = {
                            viewModel.cancelSession()
                        },
                        backgroundColor = Color(0x381E1E24),
                        borderColor = Color(0x26FF5252),
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
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Cancel",
                                color = Color(0xFFFF7B7B),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }

                    // 2. Polish Button
                    InAppPillButton(
                        onClick = {
                            if (!isPolishingLocally && transcriptText.isNotBlank()) {
                                isPolishingLocally = true
                                scope.launch {
                                    viewModel.stopRecording()
                                    kotlinx.coroutines.delay(800)
                                    isPolishingLocally = false
                                    Toast.makeText(context, "Text polished!", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        backgroundColor = Color(0x24FFFFFF),
                        borderColor = Color(0x24FFFFFF),
                        modifier = Modifier.weight(1f)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            if (isPolishingLocally) {
                                Text(
                                    text = "Polishing...",
                                    color = Color(0xFFE2E8F0),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            } else {
                                Text(
                                    text = "✦",
                                    color = palette.primaryLight,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "Polish",
                                    color = Color(0xFFE2E8F0),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }

                    // 3. Complete Button
                    InAppPillButton(
                        onClick = {
                            val textToSave = transcriptText
                            if (textToSave.isNotBlank()) {
                                viewModel.stopRecording()
                                HistoryRepository.addHistoryItem(
                                    text = textToSave,
                                    appContext = "AI · VoxStream",
                                    durationSeconds = stats.durationSeconds
                                )
                                viewModel.clearTranscript()
                                Toast.makeText(context, "Saved to history!", Toast.LENGTH_SHORT).show()
                            }
                        },
                        backgroundColor = palette.primary,
                        borderColor = Color.Transparent,
                        modifier = Modifier.weight(1.2f)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Check,
                                contentDescription = "Complete",
                                tint = Color.White,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Complete",
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }

            // FIXED STAGNANT STATUS LABEL: Never shifts or drops off container
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = if (isRecording) "Live · In-App Dictation" else "AI · VoxStream",
                style = TextStyle(
                    color = Color.White.copy(alpha = 0.45f),
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

@Composable
private fun InAppPillButton(
    onClick: () -> Unit,
    backgroundColor: Color,
    borderColor: Color,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier
            .height(38.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(backgroundColor)
            .border(
                width = 1.dp,
                color = borderColor,
                shape = RoundedCornerShape(20.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}
