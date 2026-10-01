package com.example.ui.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Metadata and catalogue for ambient glowing container animations.
 */
data class GlowAnimationStyle(
    val id: String,
    val name: String,
    val subtitle: String,
    val category: String
)

object GlowAnimationCatalogue {
    const val DEFAULT_ID = "breathing_horizon"

    val styles: List<GlowAnimationStyle> = listOf(
        GlowAnimationStyle(
            id = "breathing_horizon",
            name = "Breathing Horizon",
            subtitle = "Calm sweeping horizontal band of ambient diffused light rising calmly",
            category = "Default"
        ),
        GlowAnimationStyle(
            id = "sunset_mirage",
            name = "Sunset Mirage",
            subtitle = "Warm molten gold, coral orange, and deep rose gradient twilight swell",
            category = "Warm Sunset"
        ),
        GlowAnimationStyle(
            id = "gentle_slice",
            name = "Gentle Wave Slice",
            subtitle = "Luminous flowing slice ribbon rippling softly along the bottom base",
            category = "Wave Slice"
        ),
        GlowAnimationStyle(
            id = "pulsing_spotlight",
            name = "Flashlight Core Beacon",
            subtitle = "Focused central flashlight beam swelling dynamically with voice intensity",
            category = "Ambient Beam"
        ),
        GlowAnimationStyle(
            id = "electric_sunburst",
            name = "Electric Sunburst",
            subtitle = "Diffused sunburst energy rays fanning upward from a bottom anchor point",
            category = "Radiant"
        ),
        GlowAnimationStyle(
            id = "bioluminescent_deep",
            name = "Bioluminescent Shimmer",
            subtitle = "Subtle underwater bioluminescent luminance rippling organically",
            category = "Organic"
        ),
        GlowAnimationStyle(
            id = "liquid_glass",
            name = "Liquid Specular Glass",
            subtitle = "Crystalline refraction wash reflecting along the base like liquid glass",
            category = "Crystalline"
        ),
        GlowAnimationStyle(
            id = "velvet_aura",
            name = "Velvet Ambient Calm",
            subtitle = "Ultra-minimalist, deep, smooth ambient illumination for zero distraction",
            category = "Minimalist"
        )
    )

    fun getById(id: String): GlowAnimationStyle {
        return styles.find { it.id == id } ?: styles.first()
    }
}

/**
 * Master renderer for all glowing container animations.
 * Completely hardware accelerated within Compose DrawScope.
 */
fun DrawScope.renderGlowAnimation(
    styleId: String,
    palette: AuroraColorPalette,
    effectiveAmp: Float,
    glowBrightness: Float,
    phase1: Float,
    phase2: Float,
    completionMorphProgress: Float = 0f,
    entranceProgress: Float = 1f,
    waitingLoopProgress: Float = 0f
) {
    val w = size.width
    val h = size.height
    val nX = w * 0.17f
    val pX = w * 0.81f
    val p = completionMorphProgress.coerceIn(0f, 1f)
    val ep = entranceProgress.coerceIn(0f, 1f)
    val loopP = waitingLoopProgress.coerceIn(0f, 1f)

    // Helper for Batch 1: Soft liquid ribbon layer with Grok-style feathered tips (BlendMode.DstIn)
    fun drawLiquidRibbonLayer(
        leftX: Float,
        rightX: Float,
        topY: Float,
        bottomY: Float,
        drawContent: DrawScope.() -> Unit
    ) {
        val blockW = (rightX - leftX).coerceAtLeast(1f)
        val blockH = (bottomY - topY).coerceAtLeast(1f)
        if (leftX <= 0.01f && rightX >= w - 0.01f) {
            drawContent()
        } else {
            val layerRect = Rect(leftX, topY, rightX, bottomY)
            drawContext.canvas.saveLayer(layerRect, Paint())
            drawContent()
            val featherRatio = (36.dp.toPx() / blockW).coerceIn(0.12f, 0.30f)
            drawRect(
                brush = Brush.horizontalGradient(
                    colorStops = arrayOf(
                        0.0f to Color.Transparent,
                        featherRatio to Color.Black,
                        (1f - featherRatio) to Color.Black,
                        1.0f to Color.Transparent
                    ),
                    startX = leftX,
                    endX = rightX
                ),
                topLeft = Offset(leftX, topY),
                size = Size(blockW, blockH),
                blendMode = BlendMode.DstIn
            )
            drawContext.canvas.restore()
        }
    }

    // Universal Lifecycle solver for all 21 styles (Sunset Mirage master blueprint):
    // Phase A: Entrance (Wait 350ms -> expands from [nX, pX] to full width in 280ms)
    // Phase B: Speaking (Full width, organic voice amplitude reactivity)
    // Phase C: Reverse-logic Exit (Contracts inward to Cancel 'N' and Complete 'P' -> glides smoothly to right & dissolves)
    // Phase D: Slow-network Waiting loop (Gentle, repeating sweep from left to right)
    fun solveUniversalBounds(): Triple<Float, Float, Float> { // (left, right, alpha)
        return if (p <= 0.001f) {
            if (ep < 0.999f) {
                // Entrance: softly unfolds from [nX, pX] outward to full width in 280ms
                val curL = nX * (1f - ep)
                val curR = pX + (w - pX) * ep
                val a = (ep / 0.15f).coerceIn(0f, 1f)
                Triple(curL, curR, a)
            } else {
                Triple(0f, w, 1f)
            }
        } else if (p < 0.999f) {
            if (p < 0.42f) {
                // Exit Step 1: contracts inward from edges to Cancel 'N' and Complete 'P'
                val cr = (p / 0.42f).coerceIn(0f, 1f)
                val curL = nX * cr
                val curR = w - (w - pX) * cr
                Triple(curL, curR, 1f)
            } else {
                // Exit Step 2: that exact calm wave block glides smoothly across to the right and fades out
                val sr = ((p - 0.42f) / 0.58f).coerceIn(0f, 1f)
                val blockWidth = pX - nX
                val startLeft = nX
                val endLeft = w + 16.dp.toPx()
                val curL = startLeft + (endLeft - startLeft) * sr
                val curR = curL + blockWidth
                val fadeOut = (1f - sr).coerceIn(0f, 1f)
                val a = sin(fadeOut * (PI / 2).toFloat())
                Triple(curL, curR, a)
            }
        } else {
            // Waiting loop: calm wave sweeps repeatedly from left to right
            val loopW = (pX - nX) * 0.85f
            val curL = -loopW + (w + loopW * 2f) * loopP
            val curR = curL + loopW
            val normX = ((curL + curR) / 2f / w).coerceIn(0f, 1f)
            val a = sin(normX * PI.toFloat()).coerceIn(0f, 1f) * 0.90f
            Triple(curL, curR, a)
        }
    }

    val (curL, curR, alphaMult) = solveUniversalBounds()
    val effAlpha = alphaMult * glowBrightness
    if (effAlpha <= 0.005f) return

    val blockCenterX = (curL + curR) / 2f
    val blockW = (curR - curL).coerceAtLeast(1f)

    drawLiquidRibbonLayer(curL, curR, 0f, h) {
        when (styleId) {
            // 1. Gemini Live Aurora (Dynamic)
            "gemini_live" -> {
                val swellHeight = (42.dp.toPx() + (h * 0.72f) * effectiveAmp).coerceAtMost(h)
                val topY = (h - swellHeight).coerceAtLeast(0f)
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            palette.secondary.copy(alpha = 0.28f * effAlpha),
                            palette.primaryVibrant.copy(alpha = 0.70f * effAlpha),
                            palette.primaryLight.copy(alpha = 0.92f * effAlpha)
                        ),
                        startY = topY,
                        endY = h
                    ),
                    topLeft = Offset(curL, topY),
                    size = Size(blockW, swellHeight)
                )
                val bloomRadius = blockW * 0.65f + (w * 0.35f) * effectiveAmp
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            palette.primaryLight.copy(alpha = 0.80f * effAlpha),
                            palette.primaryVibrant.copy(alpha = 0.50f * effAlpha),
                            palette.secondary.copy(alpha = 0.22f * effAlpha),
                            Color.Transparent
                        ),
                        center = Offset(blockCenterX, h + 12.dp.toPx()),
                        radius = bloomRadius
                    ),
                    topLeft = Offset(curL, topY),
                    size = Size(blockW, swellHeight)
                )
            }

            // 2. Gentle Wave Slice (Dynamic)
            "gentle_slice" -> {
                val baseHeight = (28.dp.toPx() + 45.dp.toPx() * effectiveAmp).coerceAtMost(h)
                val topY = (h - baseHeight).coerceAtLeast(0f)
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            palette.secondary.copy(alpha = 0.20f * effAlpha),
                            palette.primaryVibrant.copy(alpha = 0.55f * effAlpha)
                        ),
                        startY = topY,
                        endY = h
                    ),
                    topLeft = Offset(curL, topY),
                    size = Size(blockW, baseHeight)
                )

                val steps = 36
                val stepW = blockW / steps
                val slicePath = Path()
                slicePath.moveTo(curL, h)
                for (i in 0..steps) {
                    val x = curL + i * stepW
                    val normX = (i.toFloat() / steps.toFloat()) * 2f - 1f
                    val envelope = (1f - normX * normX * 0.70f).coerceAtLeast(0.2f)
                    val waveOffset = sin(phase1.toDouble() + (x / w) * 2.5 * PI).toFloat()
                    val waveH = 14.dp.toPx() + (36.dp.toPx() * effectiveAmp * envelope * (0.6f + 0.4f * waveOffset))
                    val y = (h - waveH).coerceAtLeast(0f)
                    slicePath.lineTo(x, y)
                }
                slicePath.lineTo(curR, h)
                slicePath.close()

                drawPath(
                    path = slicePath,
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            palette.primaryLight.copy(alpha = 0.65f * effAlpha),
                            palette.primaryVibrant.copy(alpha = 0.75f * effAlpha),
                            palette.deep.copy(alpha = 0.35f * effAlpha)
                        ),
                        startY = topY,
                        endY = h
                    )
                )
            }

            // 3. Northern Lights Curtain (Dynamic)
            "northern_lights" -> {
                val curtainCount = 5
                for (c in 0 until curtainCount) {
                    val curX = curL + (blockW / (curtainCount + 1)) * (c + 1) + (18.dp.toPx() * sin((phase1 + c * 1.2).toDouble())).toFloat()
                    val curW = blockW * 0.40f
                    val curAlpha = (0.25f + 0.55f * effectiveAmp) * (0.7f + 0.3f * cos((phase2 + c).toDouble()).toFloat()) * effAlpha
                    drawRect(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                if (c % 2 == 0) palette.primaryLight.copy(alpha = curAlpha)
                                else palette.secondary.copy(alpha = curAlpha),
                                palette.primaryVibrant.copy(alpha = curAlpha * 0.5f),
                                Color.Transparent
                            ),
                            center = Offset(curX, h * 0.55f),
                            radius = curW
                        ),
                        topLeft = Offset(curL, 0f),
                        size = Size(blockW, h)
                    )
                }
            }

            // 4. Flashlight Core Beacon (Dynamic)
            "pulsing_spotlight" -> {
                val spotRadius = (blockW * 0.85f) * (0.75f + 0.45f * effectiveAmp)
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            palette.primaryLight.copy(alpha = 0.88f * effAlpha),
                            palette.primaryVibrant.copy(alpha = 0.55f * effAlpha),
                            palette.secondary.copy(alpha = 0.20f * effAlpha),
                            Color.Transparent
                        ),
                        center = Offset(blockCenterX, h + 15.dp.toPx()),
                        radius = spotRadius
                    ),
                    topLeft = Offset(curL, 0f),
                    size = Size(blockW, h)
                )
            }

            // 5. Dual Orbital Fusion (Dynamic)
            "dual_orbit" -> {
                val orbitRadius = 45.dp.toPx() + 25.dp.toPx() * effectiveAmp
                val angle = phase1.toDouble()
                val o1X = blockCenterX + (cos(angle) * blockW * 0.26f).toFloat()
                val o1Y = h * 0.78f + (sin(angle) * 16.dp.toPx()).toFloat()
                val o2X = blockCenterX - (cos(angle) * blockW * 0.26f).toFloat()
                val o2Y = h * 0.78f - (sin(angle) * 16.dp.toPx()).toFloat()

                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            palette.primaryLight.copy(alpha = 0.82f * effAlpha),
                            palette.primaryVibrant.copy(alpha = 0.40f * effAlpha),
                            Color.Transparent
                        ),
                        center = Offset(o1X, o1Y),
                        radius = orbitRadius
                    ),
                    center = Offset(o1X, o1Y),
                    radius = orbitRadius
                )
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            palette.secondary.copy(alpha = 0.78f * effAlpha),
                            palette.primary.copy(alpha = 0.35f * effAlpha),
                            Color.Transparent
                        ),
                        center = Offset(o2X, o2Y),
                        radius = orbitRadius
                    ),
                    center = Offset(o2X, o2Y),
                    radius = orbitRadius
                )
            }

            // 6. Breathing Horizon (Dynamic)
            "breathing_horizon" -> {
                val barH = (18.dp.toPx() + (h * 0.55f) * effectiveAmp).coerceAtMost(h)
                val topY = (h - barH).coerceAtLeast(0f)
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            palette.secondary.copy(alpha = 0.35f * effAlpha),
                            palette.primaryLight.copy(alpha = 0.85f * effAlpha),
                            palette.primaryVibrant.copy(alpha = 0.90f * effAlpha)
                        ),
                        startY = topY,
                        endY = h
                    ),
                    topLeft = Offset(curL, topY),
                    size = Size(blockW, barH)
                )
            }

            // 7. Liquid Neon Pool (Dynamic)
            "liquid_neon" -> {
                val poolCenterY = h + 10.dp.toPx()
                val morphX = blockCenterX + (14.dp.toPx() * sin(phase1.toDouble())).toFloat()
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            palette.primaryVibrant.copy(alpha = 0.90f * effAlpha),
                            palette.primaryLight.copy(alpha = 0.65f * effAlpha),
                            palette.secondary.copy(alpha = 0.30f * effAlpha),
                            Color.Transparent
                        ),
                        center = Offset(morphX, poolCenterY),
                        radius = blockW * 0.75f + (w * 0.35f) * effectiveAmp
                    ),
                    topLeft = Offset(curL, 0f),
                    size = Size(blockW, h)
                )
            }

            // 8. Ethereal Nebula Vapor (Dynamic)
            "ethereal_vapor" -> {
                val cloudSpread = blockW * 0.65f + 40.dp.toPx() * effectiveAmp
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            palette.secondary.copy(alpha = 0.60f * effAlpha),
                            palette.primaryLight.copy(alpha = 0.35f * effAlpha),
                            Color.Transparent
                        ),
                        center = Offset(curL + blockW * 0.32f, h * 0.55f),
                        radius = cloudSpread
                    ),
                    topLeft = Offset(curL, 0f),
                    size = Size(blockW, h)
                )
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            palette.primaryVibrant.copy(alpha = 0.55f * effAlpha),
                            palette.primary.copy(alpha = 0.25f * effAlpha),
                            Color.Transparent
                        ),
                        center = Offset(curL + blockW * 0.68f, h * 0.65f),
                        radius = cloudSpread
                    ),
                    topLeft = Offset(curL, 0f),
                    size = Size(blockW, h)
                )
            }

            // 9. Prism Ripple Slice (Dynamic)
            "prism_ripple" -> {
                val rippleCount = 4
                for (r in 1..rippleCount) {
                    val rRadius = (22.dp.toPx() * r) + (35.dp.toPx() * effectiveAmp)
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                Color.Transparent,
                                palette.primaryLight.copy(alpha = (0.45f / r) * effAlpha),
                                palette.secondary.copy(alpha = (0.25f / r) * effAlpha),
                                Color.Transparent
                            ),
                            center = Offset(blockCenterX, h),
                            radius = rRadius
                        ),
                        center = Offset(blockCenterX, h),
                        radius = rRadius
                    )
                }
            }

            // 10. Electric Sunburst (Dynamic)
            "electric_sunburst" -> {
                val rayH = (32.dp.toPx() + (h * 0.65f) * effectiveAmp).coerceAtMost(h)
                val topY = (h - rayH).coerceAtLeast(0f)
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            palette.primaryLight.copy(alpha = 0.40f * effAlpha),
                            palette.primaryVibrant.copy(alpha = 0.85f * effAlpha)
                        ),
                        startY = topY,
                        endY = h
                    ),
                    topLeft = Offset(curL, topY),
                    size = Size(blockW, rayH)
                )
                val rayCenter = Offset(blockCenterX, h)
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            palette.primaryLight.copy(alpha = 0.95f * effAlpha),
                            palette.secondary.copy(alpha = 0.45f * effAlpha),
                            Color.Transparent
                        ),
                        center = rayCenter,
                        radius = 48.dp.toPx() + 65.dp.toPx() * effectiveAmp
                    ),
                    center = rayCenter,
                    radius = 48.dp.toPx() + 65.dp.toPx() * effectiveAmp
                )
            }

            // 11. Cyberpunk Synthwave (Fixed Signature Colors: Neon Cyan #00E5FF & Hot Pink #FF007F)
            "cyberpunk_synth" -> {
                val cyberH = (30.dp.toPx() + (h * 0.68f) * effectiveAmp).coerceAtMost(h)
                val topY = (h - cyberH).coerceAtLeast(0f)
                drawRect(
                    brush = Brush.horizontalGradient(
                        colors = listOf(
                            Color(0xFF00E5FF).copy(alpha = 0.70f * effAlpha),
                            Color(0xFFFFFFFF).copy(alpha = 0.85f * effAlpha),
                            Color(0xFFFF007F).copy(alpha = 0.70f * effAlpha)
                        ),
                        startX = curL,
                        endX = curR
                    ),
                    topLeft = Offset(curL, topY),
                    size = Size(blockW, cyberH)
                )
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color(0xFFFF007F).copy(alpha = 0.80f * effAlpha)
                        ),
                        startY = topY,
                        endY = h
                    ),
                    topLeft = Offset(curL, topY),
                    size = Size(blockW, cyberH)
                )
            }

            // 12. Supernova Flash (Dynamic)
            "supernova_bloom" -> {
                val coreRadius = 40.dp.toPx() + 85.dp.toPx() * effectiveAmp
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.90f * effAlpha),
                            palette.primaryLight.copy(alpha = 0.75f * effAlpha),
                            palette.primaryVibrant.copy(alpha = 0.45f * effAlpha),
                            Color.Transparent
                        ),
                        center = Offset(blockCenterX, h * 0.75f),
                        radius = coreRadius
                    ),
                    topLeft = Offset(curL, 0f),
                    size = Size(blockW, h)
                )
            }

            // 13. Zenith Light Pillar (Dynamic)
            "zenith_pillar" -> {
                val pillarW = (60.dp.toPx() + 50.dp.toPx() * effectiveAmp).coerceAtMost(blockW)
                drawRect(
                    brush = Brush.horizontalGradient(
                        colors = listOf(
                            Color.Transparent,
                            palette.primaryLight.copy(alpha = 0.80f * effAlpha),
                            palette.primaryVibrant.copy(alpha = 0.65f * effAlpha),
                            Color.Transparent
                        ),
                        startX = blockCenterX - pillarW / 2f,
                        endX = blockCenterX + pillarW / 2f
                    ),
                    topLeft = Offset(curL, 0f),
                    size = Size(blockW, h)
                )
            }

            // 14. Bioluminescent Shimmer (Dynamic)
            "bioluminescent_deep" -> {
                val bioH = (26.dp.toPx() + 55.dp.toPx() * effectiveAmp).coerceAtMost(h)
                val topY = (h - bioH).coerceAtLeast(0f)
                val shimmerAlpha = (0.35f + 0.45f * effectiveAmp) * (0.85f + 0.15f * sin(phase2.toDouble() * 2).toFloat())
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            palette.secondary.copy(alpha = shimmerAlpha * 0.5f * effAlpha),
                            palette.primaryVibrant.copy(alpha = shimmerAlpha * effAlpha)
                        ),
                        startY = topY,
                        endY = h
                    ),
                    topLeft = Offset(curL, topY),
                    size = Size(blockW, bioH)
                )
            }

            // 15. Sunset Mirage (Fixed Signature Colors: Gold #FFD740, Coral #FF5252, Violet #7C4DFF)
            "sunset_mirage" -> {
                val sunsetH = if (p > 0.001f && p < 0.999f) {
                    val cr = if (p < 0.42f) (p / 0.42f).coerceIn(0f, 1f) else 1f
                    val activeH = (35.dp.toPx() + (h * 0.65f) * effectiveAmp).coerceAtMost(h)
                    val calmH = 35.dp.toPx().coerceAtMost(h)
                    activeH * (1f - cr) + calmH * cr
                } else {
                    (35.dp.toPx() + (h * 0.65f) * effectiveAmp).coerceAtMost(h)
                }
                val topY = (h - sunsetH).coerceAtLeast(0f)
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color(0xFF7C4DFF).copy(alpha = 0.35f * effAlpha),
                            Color(0xFFFF5252).copy(alpha = 0.65f * effAlpha),
                            Color(0xFFFFD740).copy(alpha = 0.85f * effAlpha)
                        ),
                        startY = topY,
                        endY = h
                    ),
                    topLeft = Offset(curL, topY),
                    size = Size(blockW, sunsetH)
                )
            }

            // 16. Cosmic Galaxy Vortex (Dynamic)
            "cosmic_vortex" -> {
                val vortexRadius = blockW * 0.65f + 35.dp.toPx() * effectiveAmp
                val vx = blockCenterX + (10.dp.toPx() * cos(phase1.toDouble())).toFloat()
                val vy = h + (8.dp.toPx() * sin(phase1.toDouble())).toFloat()
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            palette.primaryLight.copy(alpha = 0.75f * effAlpha),
                            palette.secondary.copy(alpha = 0.50f * effAlpha),
                            palette.deep.copy(alpha = 0.25f * effAlpha),
                            Color.Transparent
                        ),
                        center = Offset(vx, vy),
                        radius = vortexRadius
                    ),
                    topLeft = Offset(curL, 0f),
                    size = Size(blockW, h)
                )
            }

            // 17. Harmonic Crest Ribbon (Dynamic)
            "harmonic_ribbon" -> {
                val steps = 36
                val stepW = blockW / steps
                val ribbonPath = Path()
                ribbonPath.moveTo(curL, h)
                for (i in 0..steps) {
                    val x = curL + i * stepW
                    val wave = sin(phase1.toDouble() + (x / w) * 3.0 * PI).toFloat()
                    val waveH = 12.dp.toPx() + (32.dp.toPx() * effectiveAmp * (0.5f + 0.5f * wave))
                    val y = (h - waveH).coerceAtLeast(0f)
                    ribbonPath.lineTo(x, y)
                }
                ribbonPath.lineTo(curR, h)
                ribbonPath.close()

                drawPath(
                    path = ribbonPath,
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            palette.secondary.copy(alpha = 0.60f * effAlpha),
                            palette.primaryVibrant.copy(alpha = 0.75f * effAlpha),
                            Color.Transparent
                        ),
                        startY = (h - 45.dp.toPx()).coerceAtLeast(0f),
                        endY = h
                    )
                )
            }

            // 18. Plasma Fusion Wave (Dynamic)
            "plasma_fusion" -> {
                val plasmaH = (30.dp.toPx() + 65.dp.toPx() * effectiveAmp).coerceAtMost(h)
                val topY = (h - plasmaH).coerceAtLeast(0f)
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            palette.secondary.copy(alpha = 0.40f * effAlpha),
                            palette.primaryLight.copy(alpha = 0.85f * effAlpha)
                        ),
                        startY = topY,
                        endY = h
                    ),
                    topLeft = Offset(curL, topY),
                    size = Size(blockW, plasmaH)
                )
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            palette.primaryVibrant.copy(alpha = 0.70f * effAlpha),
                            Color.Transparent
                        ),
                        center = Offset(blockCenterX, h * 0.75f),
                        radius = blockW * 0.5f * (0.6f + 0.4f * effectiveAmp)
                    ),
                    topLeft = Offset(curL, 0f),
                    size = Size(blockW, h)
                )
            }

            // 19. Quantum Halos (Dynamic)
            "quantum_halos" -> {
                for (k in 1..3) {
                    val kRadius = (35.dp.toPx() * k) + (45.dp.toPx() * effectiveAmp)
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                Color.Transparent,
                                palette.primaryLight.copy(alpha = (0.50f / k) * effAlpha),
                                Color.Transparent
                            ),
                            center = Offset(blockCenterX, h + 5.dp.toPx()),
                            radius = kRadius
                        ),
                        center = Offset(blockCenterX, h + 5.dp.toPx()),
                        radius = kRadius
                    )
                }
            }

            // 20. Liquid Specular Glass (Dynamic)
            "liquid_glass" -> {
                val glassH = (22.dp.toPx() + 45.dp.toPx() * effectiveAmp).coerceAtMost(h)
                val topY = (h - glassH).coerceAtLeast(0f)
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color.White.copy(alpha = 0.25f * effAlpha),
                            palette.primaryLight.copy(alpha = 0.75f * effAlpha),
                            palette.primaryVibrant.copy(alpha = 0.55f * effAlpha)
                        ),
                        startY = topY,
                        endY = h
                    ),
                    topLeft = Offset(curL, topY),
                    size = Size(blockW, glassH)
                )
            }

            // 21. Velvet Ambient Calm (Dynamic)
            "velvet_aura" -> {
                val calmH = (20.dp.toPx() + 35.dp.toPx() * effectiveAmp).coerceAtMost(h)
                val topY = (h - calmH).coerceAtLeast(0f)
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            palette.primary.copy(alpha = 0.35f * effAlpha),
                            palette.deep.copy(alpha = 0.50f * effAlpha)
                        ),
                        startY = topY,
                        endY = h
                    ),
                    topLeft = Offset(curL, topY),
                    size = Size(blockW, calmH)
                )
            }

            else -> {
                // Fallback to Breathing Horizon (Default)
                val barH = (18.dp.toPx() + (h * 0.55f) * effectiveAmp).coerceAtMost(h)
                val topY = (h - barH).coerceAtLeast(0f)
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            palette.secondary.copy(alpha = 0.35f * effAlpha),
                            palette.primaryLight.copy(alpha = 0.85f * effAlpha),
                            palette.primaryVibrant.copy(alpha = 0.90f * effAlpha)
                        ),
                        startY = topY,
                        endY = h
                    ),
                    topLeft = Offset(curL, topY),
                    size = Size(blockW, barH)
                )
            }
        }
    }
}
