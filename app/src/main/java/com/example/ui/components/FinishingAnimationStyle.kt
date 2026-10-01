package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.sin

data class FinishingAnimationStyle(
    val id: String,
    val name: String,
    val subtitle: String,
    val category: String
)

object FinishingAnimationCatalogue {
    const val DEFAULT_ID = "pixel_flourish"

    val styles: List<FinishingAnimationStyle> = listOf(
        FinishingAnimationStyle(
            id = "pixel_flourish",
            name = "Pixel Flourish",
            subtitle = "Thick, soft liquid flashlight beam that sweeps gracefully across the center and fades gently",
            category = "Pixel Signature"
        ),
        FinishingAnimationStyle(
            id = "soft_collapse",
            name = "Soft Collapse",
            subtitle = "Sleek horizontal line that contracts smoothly from edges toward center and dissolves",
            category = "Contracting"
        ),
        FinishingAnimationStyle(
            id = "rising_spark",
            name = "Rising Spark",
            subtitle = "A quick upward traveling shimmer bar that sweeps through the container interior and fades",
            category = "Sweeping"
        ),
        FinishingAnimationStyle(
            id = "ring_bloom",
            name = "Ring Bloom",
            subtitle = "A clean expanding halo ring centered inside the panel that expands smoothly and dissolves",
            category = "Bloom"
        ),
        FinishingAnimationStyle(
            id = "gentle_echo",
            name = "Gentle Echo",
            subtitle = "Two soft parallel traveling wave lines that trail across the container interior",
            category = "Harmonic"
        ),
        FinishingAnimationStyle(
            id = "settling_pulse",
            name = "Settling Pulse",
            subtitle = "A crisp interior bottom line that pulses softly with a still, premium glow",
            category = "Pulsing"
        )
    )

    fun getById(id: String): FinishingAnimationStyle {
        return styles.find { it.id == id } ?: styles.first()
    }
}

/**
 * Hardware-accelerated renderer for finishing / completion animation styles.
 * Renders sleek, precise progress/completion beams strictly INSIDE the container content area.
 */
fun DrawScope.renderFinishingAnimation(
    styleId: String,
    palette: AuroraColorPalette,
    phase: Float, // continuous phase angle 0..2PI
    glowBrightness: Float = 1.0f,
    progress: Float? = null // optional unified morph transition progress 0..1
) {
    val w = size.width
    val h = size.height
    if (w <= 0f || h <= 0f) return

    val normPhase = progress?.coerceIn(0f, 1f)
        ?: ((phase % (2 * PI.toFloat())) / (2 * PI.toFloat())).coerceIn(0f, 1f)

    when (styleId) {
        // 1. Pixel Flourish (Default, Pixel 11 Pro inspired)
        // Thick, soft, liquid "flashlight" style beam traveling from "N" of Cancel to "P" of Complete
        "pixel_flourish" -> {
            val startX = w * 0.16f // Roughly around letter "N" in Cancel button area
            val endX = w * 0.80f   // Roughly around letter "P" in Complete button area
            val pathRange = endX - startX
            val centerY = h / 2f

            // Soft fade-in at the start (first 18%), soft fade-out at the end (last 22%)
            val fadeIn = (normPhase / 0.18f).coerceIn(0f, 1f)
            val fadeOut = ((1f - normPhase) / 0.22f).coerceIn(0f, 1f)
            val masterFade = (fadeIn * fadeOut * glowBrightness).coerceIn(0f, 1f)
            val smoothFade = sin(masterFade * (PI / 2).toFloat())

            if (smoothFade > 0.01f) {
                val headX = startX + pathRange * normPhase
                val beamLength = pathRange * 0.42f
                val tailX = (headX - beamLength).coerceAtLeast(startX)

                // 0. Soft convergence bloom (captures the melting voice wave energy condensing into the beam)
                if (normPhase < 0.35f) {
                    val convergePhase = 1f - (normPhase / 0.35f)
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                Color.White.copy(alpha = 0.65f * convergePhase * smoothFade),
                                palette.primaryLight.copy(alpha = 0.45f * convergePhase * smoothFade),
                                Color.Transparent
                            ),
                            center = Offset(startX, centerY),
                            radius = (16.dp.toPx() * (0.6f + 0.4f * convergePhase)).coerceAtMost(h * 1.5f)
                        ),
                        center = Offset(startX, centerY),
                        radius = (16.dp.toPx() * (0.6f + 0.4f * convergePhase)).coerceAtMost(h * 1.5f)
                    )
                }

                // 1. Ambient Diffuse Liquid Aurora Bloom (wide flashlight diffusion, feather-soft vertical falloff)
                val diffuseHeight = 16.dp.toPx().coerceAtMost(h)
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            palette.primaryVibrant.copy(alpha = 0.30f * smoothFade),
                            palette.primaryLight.copy(alpha = 0.50f * smoothFade),
                            palette.primaryVibrant.copy(alpha = 0.30f * smoothFade),
                            Color.Transparent
                        ),
                        startY = centerY - diffuseHeight / 2f,
                        endY = centerY + diffuseHeight / 2f
                    ),
                    topLeft = Offset(tailX, centerY - diffuseHeight / 2f),
                    size = Size(headX - tailX + 8.dp.toPx(), diffuseHeight)
                )

                // 2. Continuous Fluid Liquid Core (thick, soft horizontal gradient with no hard edges)
                val coreHeight = 7.dp.toPx().coerceAtMost(h)
                drawRect(
                    brush = Brush.horizontalGradient(
                        colors = listOf(
                            Color.Transparent,
                            palette.secondary.copy(alpha = 0.25f * smoothFade),
                            palette.primaryVibrant.copy(alpha = 0.65f * smoothFade),
                            palette.primaryLight.copy(alpha = 0.88f * smoothFade),
                            Color.White.copy(alpha = 0.95f * smoothFade),
                            Color.Transparent
                        ),
                        startX = tailX,
                        endX = headX + 6.dp.toPx()
                    ),
                    topLeft = Offset(tailX, centerY - coreHeight / 2f),
                    size = Size(headX - tailX + 6.dp.toPx(), coreHeight)
                )

                // 3. Fluid Flashlight Head Pool (soft radial glowing pool of light leading the beam)
                val headRadius = 12.dp.toPx().coerceAtMost(h)
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.92f * smoothFade),
                            palette.primaryLight.copy(alpha = 0.70f * smoothFade),
                            palette.primaryVibrant.copy(alpha = 0.35f * smoothFade),
                            Color.Transparent
                        ),
                        center = Offset(headX, centerY),
                        radius = headRadius
                    ),
                    center = Offset(headX, centerY),
                    radius = headRadius
                )
            }
        }

        // 2. Soft Collapse
        "soft_collapse" -> {
            val strokeHeight = 3.dp.toPx()
            val centerY = h / 2f
            val collapseWidth = w * (1f - normPhase)
            val startX = (w - collapseWidth) / 2f

            drawRect(
                brush = Brush.horizontalGradient(
                    colors = listOf(
                        Color.Transparent,
                        palette.secondary.copy(alpha = 0.4f * (1f - normPhase) * glowBrightness),
                        palette.primaryVibrant.copy(alpha = 0.9f * (1f - normPhase) * glowBrightness),
                        palette.secondary.copy(alpha = 0.4f * (1f - normPhase) * glowBrightness),
                        Color.Transparent
                    ),
                    startX = startX,
                    endX = startX + collapseWidth
                ),
                topLeft = Offset(0f, centerY - strokeHeight / 2f),
                size = Size(w, strokeHeight)
            )
        }

        // 3. Rising Spark
        "rising_spark" -> {
            val strokeHeight = 3.dp.toPx()
            val bandY = h * (1f - normPhase)

            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color.Transparent,
                        Color.White.copy(alpha = 0.90f * (1f - normPhase) * glowBrightness),
                        palette.primaryVibrant.copy(alpha = 0.60f * (1f - normPhase) * glowBrightness),
                        Color.Transparent
                    ),
                    startY = (bandY - strokeHeight * 2f).coerceAtLeast(0f),
                    endY = (bandY + strokeHeight * 2f).coerceAtMost(h)
                )
            )
        }

        // 4. Ring Bloom
        "ring_bloom" -> {
            val maxRadius = (w * 0.45f).coerceAtLeast(h * 2f)
            val currentRadius = maxRadius * normPhase
            val alpha = (1f - normPhase).coerceIn(0f, 1f) * glowBrightness

            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color.Transparent,
                        palette.primaryLight.copy(alpha = 0.85f * alpha),
                        palette.primaryVibrant.copy(alpha = 0.50f * alpha),
                        Color.Transparent
                    ),
                    center = Offset(w / 2f, h / 2f),
                    radius = currentRadius.coerceAtLeast(1f)
                ),
                style = Stroke(width = 2.5.dp.toPx())
            )
        }

        // 5. Gentle Echo
        "gentle_echo" -> {
            val strokeHeight = 2.5.dp.toPx()
            val centerY = h / 2f

            val norm1 = normPhase
            val norm2 = ((normPhase + 0.35f) % 1f)

            val headX1 = (w + w * 0.3f) * norm1 - w * 0.15f
            val headX2 = (w + w * 0.3f) * norm2 - w * 0.15f

            // Primary beam
            drawRect(
                brush = Brush.horizontalGradient(
                    colors = listOf(
                        Color.Transparent,
                        palette.primaryVibrant.copy(alpha = 0.85f * glowBrightness),
                        Color.White.copy(alpha = 0.95f * glowBrightness),
                        Color.Transparent
                    ),
                    startX = (headX1 - w * 0.3f).coerceAtLeast(0f),
                    endX = (headX1 + w * 0.1f).coerceAtMost(w)
                ),
                topLeft = Offset(0f, centerY - strokeHeight),
                size = Size(w, strokeHeight)
            )

            // Echo beam
            drawRect(
                brush = Brush.horizontalGradient(
                    colors = listOf(
                        Color.Transparent,
                        palette.secondary.copy(alpha = 0.50f * glowBrightness),
                        palette.primaryLight.copy(alpha = 0.65f * glowBrightness),
                        Color.Transparent
                    ),
                    startX = (headX2 - w * 0.3f).coerceAtLeast(0f),
                    endX = (headX2 + w * 0.1f).coerceAtMost(w)
                ),
                topLeft = Offset(0f, centerY + strokeHeight / 2f),
                size = Size(w, strokeHeight)
            )
        }

        // 6. Settling Pulse
        "settling_pulse" -> {
            val strokeHeight = 3.dp.toPx()
            val centerY = h / 2f
            val pulseAlpha = (0.5f + 0.5f * sin(normPhase * 2 * PI.toFloat())).coerceIn(0.2f, 1f) * glowBrightness

            drawRect(
                brush = Brush.horizontalGradient(
                    colors = listOf(
                        Color.Transparent,
                        palette.primaryVibrant.copy(alpha = pulseAlpha),
                        Color.White.copy(alpha = pulseAlpha),
                        palette.primaryVibrant.copy(alpha = pulseAlpha),
                        Color.Transparent
                    )
                ),
                topLeft = Offset(0f, centerY - strokeHeight / 2f),
                size = Size(w, strokeHeight)
            )
        }

        else -> {
            renderFinishingAnimation("pixel_flourish", palette, phase, glowBrightness)
        }
    }
}

/**
 * Replaces the boring CircularProgressIndicator on action buttons (Complete, Polish)
 * with a dynamic, premium finishing wave animation preview!
 */
@Composable
fun FinishingWaveProgressIndicator(
    styleId: String = "pixel_flourish",
    palette: AuroraColorPalette,
    phase: Float,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        renderFinishingAnimation(
            styleId = styleId,
            palette = palette,
            phase = phase,
            glowBrightness = 1.0f
        )
    }
}
