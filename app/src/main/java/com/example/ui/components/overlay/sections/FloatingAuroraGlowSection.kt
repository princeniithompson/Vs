package com.example.ui.components.overlay.sections

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.ui.components.overlay.AuroraColorPalette
import com.example.ui.components.overlay.NotchedContainerShape
import com.example.ui.components.overlay.createNotchedPath
import com.example.ui.components.overlay.drawNotchedOverlayBackground

/**
 * Renders the dark frosted-glass container with the dynamic multi-style Aurora Glow bloom
 * tailored to the custom notch path.
 */
@Composable
fun FloatingAuroraGlowSection(
    notchedShape: NotchedContainerShape,
    palette: AuroraColorPalette,
    glowStyleId: String,
    state: FloatingDictationPopupState,
    isRecording: Boolean,
    cornerRadius: Dp = 24.dp,
    notchRadius: Dp = 25.5.dp,
    notchCenterOffsetX: Dp = 28.dp,
    notchCenterOffsetY: Dp = 2.dp,
    filletRadius: Dp = 8.dp,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 22.dp)
            .clip(notchedShape)
            .drawBehind {
                val path = createNotchedPath(
                    size = size,
                    density = this,
                    cornerRadius = cornerRadius,
                    notchRadius = notchRadius,
                    notchCenterOffsetX = notchCenterOffsetX,
                    notchCenterOffsetY = notchCenterOffsetY,
                    filletRadius = filletRadius
                )
                val cx = size.width - notchCenterOffsetX.toPx()
                val cy = notchCenterOffsetY.toPx()

                val baseAmp = if (isRecording) state.effectiveAmp else 0.06f

                drawNotchedOverlayBackground(
                    path = path,
                    palette = palette,
                    glowPulse = state.glowPulse,
                    cx = cx,
                    cy = cy,
                    styleId = glowStyleId,
                    effectiveAmp = baseAmp,
                    phase1 = state.phase1,
                    phase2 = state.phase2,
                    morphProgress = state.morphProgress,
                    sunsetEntranceProgress = state.sunsetEntranceProgress,
                    waitingLoopProgress = state.waitingLoopProgress
                )
            }
            .padding(top = 16.dp, bottom = 12.dp, start = 16.dp, end = 16.dp),
        content = content
    )
}
