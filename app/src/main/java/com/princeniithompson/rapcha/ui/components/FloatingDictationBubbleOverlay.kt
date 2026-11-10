package com.princeniithompson.rapcha.ui.components

import android.content.Context
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
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.princeniithompson.rapcha.ui.components.overlay.PillActionButton
import com.princeniithompson.rapcha.ui.components.overlay.isColorDark
import com.princeniithompson.rapcha.ui.components.overlay.AuroraColorPalette
import com.princeniithompson.rapcha.ui.components.overlay.NotchedContainerShape
import com.princeniithompson.rapcha.ui.components.overlay.toDangerPalette
import com.princeniithompson.rapcha.ui.components.overlay.getDynamicTonePalette as getDynamicTonePaletteFromOverlay
import com.princeniithompson.rapcha.ui.components.overlay.rememberDynamicAuroraPalette as rememberDynamicAuroraPaletteFromOverlay
import com.princeniithompson.rapcha.ui.components.overlay.sections.FloatingAuroraGlowSection
import com.princeniithompson.rapcha.ui.components.overlay.sections.FloatingButtonRowSection
import com.princeniithompson.rapcha.ui.components.overlay.sections.FloatingNotchedCutoutSection
import com.princeniithompson.rapcha.ui.components.overlay.sections.FloatingTranscriptSection
import com.princeniithompson.rapcha.ui.components.overlay.sections.rememberFloatingDictationPopupState
import com.princeniithompson.rapcha.ui.components.overlay.sections.rememberNotchedContainerShape

// Re-exports for backward compatibility across the app
typealias AuroraColorPalette = com.princeniithompson.rapcha.ui.components.overlay.AuroraColorPalette
typealias NotchedPopupShape = com.princeniithompson.rapcha.ui.components.overlay.NotchedContainerShape
typealias NotchedContainerShape = com.princeniithompson.rapcha.ui.components.overlay.NotchedContainerShape

@Composable
fun rememberDynamicAuroraPalette(toneIdOverride: String? = null): com.princeniithompson.rapcha.ui.components.overlay.AuroraColorPalette {
    return rememberDynamicAuroraPaletteFromOverlay(toneIdOverride)
}

fun getDynamicTonePalette(context: Context, toneId: String): com.princeniithompson.rapcha.ui.components.overlay.AuroraColorPalette {
    return getDynamicTonePaletteFromOverlay(context, toneId)
}

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
    com.princeniithompson.rapcha.ui.components.overlay.FloatingCollapsedBubble(
        isRecording = isRecording,
        isShrunk = isShrunk,
        isSnappedToRight = isSnappedToRight,
        onClick = onClick,
        onDragStart = onDragStart,
        onDrag = onDrag,
        onDragEnd = onDragEnd,
        modifier = modifier
    )
}

/**
 * Floating Dictation Popup coordinating modular UI sections:
 * - Dynamic Aurora Glow bloom diffused within the notched frosted-glass container
 * - Left-aligned text input area with auto-scrolling & blinking cursor
 * - Action button row (Cancel | Polish | Complete)
 * - Docked lifebuoy ring nested unclipped in the top-right cutout notch
 */
@Composable
fun FloatingDictationPopup(
    transcriptText: String,
    isRecording: Boolean,
    isPendingFinalizing: Boolean,
    isPolishing: Boolean,
    audioAmplitude: Float = 0f,
    glowStyleId: String = GlowAnimationCatalogue.DEFAULT_ID,
    finishingStyleId: String = FinishingAnimationCatalogue.DEFAULT_ID,
    onCancelClick: () -> Unit,
    onPolishClick: () -> Unit,
    onCompleteClick: () -> Unit,
    onLifebuoyClick: () -> Unit,
    onDragStart: () -> Unit = {},
    onDrag: (Float, Float) -> Unit = { _, _ -> },
    onDragEnd: () -> Unit = {},
    modifier: Modifier = Modifier,
    /**
     * Settings preview only. The live overlay leaves this false, so it still
     * shows the transcript and the live Cancel/Complete row.
     */
    blankPreview: Boolean = false
) {
    val isNetworkProblem by com.princeniithompson.rapcha.service.FloatingBubbleManager.isNetworkProblem.collectAsState()
    val offlineRetry by com.princeniithompson.rapcha.service.FloatingBubbleManager.offlineRetry.collectAsState()
    val basePalette = rememberDynamicAuroraPalette()
    val palette = if (isNetworkProblem || offlineRetry != null) basePalette.toDangerPalette() else basePalette
    val state = rememberFloatingDictationPopupState(
        transcriptText = transcriptText,
        isRecording = isRecording,
        isPendingFinalizing = isPendingFinalizing,
        isPolishing = isPolishing,
        audioAmplitude = audioAmplitude
    )
    val notchedShape = rememberNotchedContainerShape()

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 4.dp)
    ) {
        // 1. Notched Glass Container Box with Aurora Glow background
        FloatingAuroraGlowSection(
            notchedShape = notchedShape,
            palette = palette,
            glowStyleId = glowStyleId,
            state = state,
            isRecording = isRecording
        ) {
            if (blankPreview) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(96.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        PillActionButton(
                            onClick = onCancelClick,
                            backgroundColor = Color(0x381E1E24),
                            borderColor = Color(0x26FF5252),
                            modifier = Modifier.weight(1f)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Filled.Close,
                                    contentDescription = null,
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
                        val polishInk = if (isColorDark(palette.primaryVibrant)) Color.White else Color(0xFF042F2E)
                        PillActionButton(
                            onClick = onPolishClick,
                            backgroundColor = palette.primaryVibrant,
                            borderColor = Color.Transparent,
                            modifier = Modifier.weight(1.4f)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(text = "✦", color = polishInk, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                Spacer(modifier = Modifier.width(5.dp))
                                Text(
                                    text = "Polish",
                                    color = polishInk,
                                    fontSize = 13.5.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }
            } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .wrapContentHeight()
            ) {
                // Transcript text input box with scrolling & cursor
                FloatingTranscriptSection(
                    transcriptText = transcriptText,
                    cursorAlpha = state.cursorAlpha,
                    palette = palette,
                    scrollState = state.scrollState
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Action button row (Cancel | Polish | Complete)
                FloatingButtonRowSection(
                    state = state,
                    palette = palette,
                    onCancelClick = onCancelClick,
                    onPolishClick = onPolishClick,
                    onCompleteClick = onCompleteClick
                )
            }
            }
        }

        // 2. Docked Lifebuoy Ring (Unclipped, nested in notch)
        FloatingNotchedCutoutSection(
            onLifebuoyClick = onLifebuoyClick,
            onDragStart = onDragStart,
            onDrag = onDrag,
            onDragEnd = onDragEnd,
            spinning = state.isFinalizing || state.isPolishing,
            modifier = Modifier.align(Alignment.TopEnd)
        )
    }
}
