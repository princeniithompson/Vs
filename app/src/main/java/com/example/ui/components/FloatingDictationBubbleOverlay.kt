package com.example.ui.components

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.ui.components.overlay.AuroraColorPalette
import com.example.ui.components.overlay.NotchedContainerShape
import com.example.ui.components.overlay.toDangerPalette
import com.example.ui.components.overlay.getDynamicTonePalette as getDynamicTonePaletteFromOverlay
import com.example.ui.components.overlay.rememberDynamicAuroraPalette as rememberDynamicAuroraPaletteFromOverlay
import com.example.ui.components.overlay.sections.FloatingAuroraGlowSection
import com.example.ui.components.overlay.sections.FloatingButtonRowSection
import com.example.ui.components.overlay.sections.FloatingNotchedCutoutSection
import com.example.ui.components.overlay.sections.FloatingTranscriptSection
import com.example.ui.components.overlay.sections.rememberFloatingDictationPopupState
import com.example.ui.components.overlay.sections.rememberNotchedContainerShape

// Re-exports for backward compatibility across the app
typealias AuroraColorPalette = com.example.ui.components.overlay.AuroraColorPalette
typealias NotchedPopupShape = com.example.ui.components.overlay.NotchedContainerShape
typealias NotchedContainerShape = com.example.ui.components.overlay.NotchedContainerShape

@Composable
fun rememberDynamicAuroraPalette(toneIdOverride: String? = null): com.example.ui.components.overlay.AuroraColorPalette {
    return rememberDynamicAuroraPaletteFromOverlay(toneIdOverride)
}

fun getDynamicTonePalette(context: Context, toneId: String): com.example.ui.components.overlay.AuroraColorPalette {
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
    com.example.ui.components.overlay.FloatingCollapsedBubble(
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
    modifier: Modifier = Modifier
) {
    val isNetworkProblem by com.example.service.FloatingBubbleManager.isNetworkProblem.collectAsState()
    val basePalette = rememberDynamicAuroraPalette()
    val palette = if (isNetworkProblem) basePalette.toDangerPalette() else basePalette
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

        // 2. Docked Lifebuoy Ring (Unclipped, nested in notch)
        FloatingNotchedCutoutSection(
            onLifebuoyClick = onLifebuoyClick,
            onDragStart = onDragStart,
            onDrag = onDrag,
            onDragEnd = onDragEnd,
            modifier = Modifier.align(Alignment.TopEnd)
        )
    }
}
