package com.example.ui.components.overlay

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.ui.components.renderGlowAnimation

/**
 * Draws the layered Aurora bloom, translucent glass gradient, dynamic visualizer animations,
 * and illuminated outer perimeter border inside the notched overlay card.
 */
fun DrawScope.drawNotchedOverlayBackground(
    path: Path,
    palette: AuroraColorPalette,
    glowPulse: Float,
    cx: Float,
    cy: Float,
    styleId: String,
    effectiveAmp: Float,
    phase1: Float,
    phase2: Float,
    morphProgress: Float,
    sunsetEntranceProgress: Float,
    waitingLoopProgress: Float
) {
    clipPath(path) {
        // Layer 1: Ambient internal Aurora bloom
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(
                    palette.primaryVibrant.copy(alpha = 0.38f * glowPulse),
                    palette.secondary.copy(alpha = 0.18f * glowPulse),
                    Color.Transparent
                ),
                center = Offset(size.width * 0.38f, size.height * 0.45f),
                radius = size.width * 0.75f
            )
        )

        // Layer 2: Focused light bloom wrapping the cutout notch
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(
                    palette.primaryLight.copy(alpha = 0.35f * glowPulse),
                    palette.primary.copy(alpha = 0.12f * glowPulse),
                    Color.Transparent
                ),
                center = Offset(cx, cy + 12.dp.toPx()),
                radius = 48.dp.toPx()
            )
        )

        // Layer 3: Dark frosted glass background fill
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(
                    Color(0x800A1513),
                    Color(0x94060F0E)
                )
            )
        )

        // Layer 4: Dynamic glowing container visualizer animation
        val glowBrightness = (0.38f + 0.62f * effectiveAmp)
        if (glowBrightness > 0.005f) {
            renderGlowAnimation(
                styleId = styleId,
                palette = palette,
                effectiveAmp = effectiveAmp,
                glowBrightness = glowBrightness,
                phase1 = phase1,
                phase2 = phase2,
                completionMorphProgress = morphProgress,
                entranceProgress = sunsetEntranceProgress,
                waitingLoopProgress = waitingLoopProgress
            )
        }
    }

    // Outer Edge: Ambient luminous border tracing notched shape
    drawPath(
        path = path,
        brush = Brush.horizontalGradient(
            colors = listOf(
                palette.primary.copy(alpha = 0.55f * glowPulse),
                palette.primaryLight.copy(alpha = 0.80f * glowPulse),
                palette.secondary.copy(alpha = 0.50f * glowPulse)
            )
        ),
        style = Stroke(
            width = 1.35.dp.toPx(),
            cap = StrokeCap.Round,
            join = StrokeJoin.Round
        )
    )
}
