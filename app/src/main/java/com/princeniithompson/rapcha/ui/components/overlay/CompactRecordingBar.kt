package com.princeniithompson.rapcha.ui.components.overlay

import android.os.Build
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.princeniithompson.rapcha.R
import com.princeniithompson.rapcha.service.floating.BubbleChromeGeometry
import com.princeniithompson.rapcha.service.floating.SessionClockFormat
import com.princeniithompson.rapcha.ui.components.renderGlowAnimation
import kotlin.math.PI

private val cachedNoiseImage: androidx.compose.ui.graphics.ImageBitmap by lazy {
    val bitmap = android.graphics.Bitmap.createBitmap(64, 64, android.graphics.Bitmap.Config.ARGB_8888)
    val random = java.util.Random(1337)
    val pixels = IntArray(64 * 64)
    for (i in pixels.indices) {
        val v = random.nextInt(255)
        pixels[i] = android.graphics.Color.argb((0.035f * 255).toInt(), v, v, v)
    }
    bitmap.setPixels(pixels, 0, 64, 0, 0, 64, 64)
    bitmap.asImageBitmap()
}

/**
 * Compact voice-typing bubble matching the target frosted-glass design scaled to
 * the compact "Whisper Flows" container dimensions:
 * - Left: White 3D circular X button (48dp diameter) with dark icon tint and 16% black outline
 * - Center: Lit frosted glass capsule pill (90dp wide x 48dp tall) driven by the app's shared glow engine
 *           with dark smoky base (#1E1B26 @ 82%), hardware-blurred glow, noise grain, top sheen,
 *           rim gradient, and top edge specular highlight line.
 * - Right: Lifebuoy ring checkmark button (48dp diameter) with black center badge
 * - Gap: 5dp between components
 */
@Composable
fun CompactRecordingBar(
    elapsedSeconds: Int,
    appName: String,
    glowStyleId: String,
    palette: AuroraColorPalette,
    audioAmplitude: Float = 0.35f,
    processing: Boolean = false,
    statusLabel: String? = null,
    mimicTalkingWhenSilent: Boolean = false,
    showAppName: Boolean = true,
    onCancel: () -> Unit,
    onComplete: () -> Unit,
    onDragStart: () -> Unit = {},
    onDrag: (Float, Float) -> Unit = { _, _ -> },
    onDragEnd: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "compact_pill_glow_anim")
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
    val spin by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(2500, easing = LinearEasing), RepeatMode.Restart),
        label = "spin"
    )

    val simulatedTalking = (0.45f + 0.35f * kotlin.math.abs(kotlin.math.sin(phase1.toDouble() * 2 * PI)) + 0.2f * kotlin.math.abs(kotlin.math.cos(phase2.toDouble() * 1.5 * PI))).toFloat()
    val effectiveAmp = if (audioAmplitude > 0.05f) {
        audioAmplitude.coerceIn(0.15f, 1.0f)
    } else if (mimicTalkingWhenSilent) {
        simulatedTalking.coerceIn(0.25f, 0.85f)
    } else {
        (0.20f + 0.08f * kotlin.math.sin(phase1.toDouble() * 2 * PI)).toFloat().coerceIn(0.15f, 0.35f)
    }
    val baseAmp = effectiveAmp
    val glowBrightness = 0.45f + 0.55f * baseAmp

    val cancelDiameter = BubbleChromeGeometry.CANCEL_DP.dp
    val pillWidth = BubbleChromeGeometry.PILL_WIDTH_DP.dp
    val pillHeight = BubbleChromeGeometry.PILL_HEIGHT_DP.dp
    val completeDiameter = BubbleChromeGeometry.COMPLETE_DP.dp
    val gap = BubbleChromeGeometry.GAP_DP.dp
    val pillShape = RoundedCornerShape(percent = 50)

    val density = LocalDensity.current
    val textShadow = remember(density) {
        Shadow(
            color = Color.Black.copy(alpha = 0.30f),
            offset = Offset(0f, density.density),
            blurRadius = 2f * density.density
        )
    }

    Row(
        modifier = modifier
            .wrapContentSize()
            .padding(BubbleChromeGeometry.WINDOW_PADDING_DP.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        // 1. Left: White 3D circular Cancel / X button (48dp diameter)
        Box(
            modifier = Modifier
                .size(cancelDiameter)
                .shadow(
                    elevation = 4.dp,
                    shape = CircleShape,
                    ambientColor = Color.Black.copy(alpha = 0.28f),
                    spotColor = Color.Black.copy(alpha = 0.28f)
                )
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.96f))
                .border(
                    width = 1.dp,
                    color = Color.Black.copy(alpha = 0.16f),
                    shape = CircleShape
                )
                .clickable(onClick = onCancel),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = "Cancel",
                tint = Color(0xFF1B1B1F),
                modifier = Modifier.size(18.dp)
            )
        }

        Spacer(modifier = Modifier.width(gap))

        // 2. Center: Lit Frosted Glass Capsule Pill (90dp wide x 48dp tall)
        Box(
            modifier = Modifier
                .size(width = pillWidth, height = pillHeight)
                .shadow(
                    elevation = 4.dp,
                    shape = pillShape,
                    ambientColor = Color.Black.copy(alpha = 0.28f),
                    spotColor = Color.Black.copy(alpha = 0.28f)
                )
                .clip(pillShape)
                .background(Color(0xFF1E1B26).copy(alpha = 0.82f))
                .border(
                    width = 1.dp,
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.28f),
                            Color.White.copy(alpha = 0.10f)
                        )
                    ),
                    shape = pillShape
                )
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { onDragStart() },
                        onDragEnd = { onDragEnd() },
                        onDragCancel = { onDragEnd() }
                    ) { change, drag ->
                        change.consume()
                        onDrag(drag.x, drag.y)
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            // Layer B: Blurred Glow Layer from Engine (with Android 12+ RenderEffect blur & fallback)
            Canvas(
                modifier = Modifier
                    .matchParentSize()
                    .then(
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            Modifier.graphicsLayer {
                                val blurPx = size.height * 0.35f
                                if (blurPx > 0f) {
                                    renderEffect = android.graphics.RenderEffect.createBlurEffect(
                                        blurPx,
                                        blurPx,
                                        android.graphics.Shader.TileMode.CLAMP
                                    ).asComposeRenderEffect()
                                }
                            }
                        } else Modifier
                    )
            ) {
                val w = size.width
                val h = size.height
                if (w <= 0f || h <= 0f) return@Canvas

                // Ambient halo behind glow
                val haloRadius = w * 0.65f
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            palette.primaryLight.copy(alpha = 0.22f * glowBrightness),
                            palette.secondary.copy(alpha = 0.10f * glowBrightness),
                            Color.Transparent
                        ),
                        center = Offset(w / 2f, h * 0.60f),
                        radius = haloRadius
                    ),
                    center = Offset(w / 2f, h * 0.60f),
                    radius = haloRadius
                )

                // Shared Glow Engine Renderer
                renderGlowAnimation(
                    styleId = glowStyleId,
                    palette = palette,
                    effectiveAmp = baseAmp,
                    glowBrightness = glowBrightness,
                    phase1 = phase1,
                    phase2 = phase2,
                    completionMorphProgress = 0f,
                    entranceProgress = 1f,
                    waitingLoopProgress = 0f
                )

                // Soft dark scrim centered behind text for >= 4.5:1 contrast
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = 0.32f),
                            Color.Black.copy(alpha = 0.16f),
                            Color.Transparent
                        ),
                        center = Offset(w / 2f, h * 0.48f),
                        radius = w * 0.45f
                    ),
                    center = Offset(w / 2f, h * 0.48f),
                    radius = w * 0.45f
                )
            }

            // Layer C, D, E, F: Noise Grain, Top Sheen, and Top Specular Highlight Line
            Canvas(modifier = Modifier.matchParentSize()) {
                val w = size.width
                val h = size.height
                if (w <= 0f || h <= 0f) return@Canvas

                // C) Frost Grain: Tile small 64x64 noise bitmap at 3.5% alpha
                val noiseSize = cachedNoiseImage.width.toFloat()
                var tileX = 0f
                while (tileX < w) {
                    var tileY = 0f
                    while (tileY < h) {
                        drawImage(
                            image = cachedNoiseImage,
                            dstOffset = androidx.compose.ui.unit.IntOffset(tileX.toInt(), tileY.toInt()),
                            dstSize = androidx.compose.ui.unit.IntSize(
                                (w - tileX).coerceAtMost(noiseSize).toInt(),
                                (h - tileY).coerceAtMost(noiseSize).toInt()
                            )
                        )
                        tileY += noiseSize
                    }
                    tileX += noiseSize
                }

                // D) Top Sheen: White 12% fading to 0% by middle
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.12f),
                            Color.Transparent
                        ),
                        startY = 0f,
                        endY = h * 0.50f
                    ),
                    topLeft = Offset.Zero,
                    size = Size(w, h * 0.50f)
                )

                // F) Thin Specular Line along top edge: white 35% at center fading to 0% at ends following curve
                val strokeWidthPx = 1.2.dp.toPx()
                val cornerRadiusPx = h / 2f
                val specularPath = Path().apply {
                    val inset = strokeWidthPx / 2f
                    addRoundRect(
                        RoundRect(
                            left = inset,
                            top = inset,
                            right = w - inset,
                            bottom = h - inset,
                            cornerRadius = CornerRadius(cornerRadiusPx, cornerRadiusPx)
                        )
                    )
                }
                clipRect(left = 0f, top = 0f, right = w, bottom = h * 0.42f) {
                    drawPath(
                        path = specularPath,
                        brush = Brush.horizontalGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color.White.copy(alpha = 0.35f),
                                Color.Transparent
                            ),
                            startX = w * 0.15f,
                            endX = w * 0.85f
                        ),
                        style = Stroke(width = strokeWidthPx)
                    )
                }
            }

            // Layer G: Typography (Medium Timer & Auto-Fitted App Name, unblurred on top)
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 2.dp, bottom = 4.dp, start = 8.dp, end = 8.dp)
            ) {
                val timerDisplay = if (processing) "..." else SessionClockFormat.format(elapsedSeconds)
                Text(
                    text = timerDisplay,
                    color = Color.White,
                    fontSize = 14.5.sp,
                    lineHeight = 16.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = (-0.3).sp,
                    maxLines = 1,
                    textAlign = TextAlign.Center,
                    style = TextStyle(
                        shadow = textShadow,
                        platformStyle = PlatformTextStyle(includeFontPadding = false)
                    )
                )

                if (showAppName) {
                    Spacer(modifier = Modifier.height(1.dp))

                    val appDisplayName = statusLabel?.takeIf { it.isNotBlank() } ?: appName.ifBlank { "WhatsApp" }
                    AutoFittedAppName(
                        text = appDisplayName,
                        maxWidth = (BubbleChromeGeometry.PILL_WIDTH_DP - 16).dp,
                        color = Color.White.copy(alpha = 0.85f),
                        textShadow = textShadow
                    )
                }
            }
        }

        Spacer(modifier = Modifier.width(gap))

        // 3. Right: Circular Lifebuoy Check Button (48dp diameter)
        Box(
            modifier = Modifier
                .size(completeDiameter)
                .shadow(
                    elevation = 4.dp,
                    shape = CircleShape,
                    ambientColor = Color.Black.copy(alpha = 0.28f),
                    spotColor = Color.Black.copy(alpha = 0.28f)
                )
                .clip(CircleShape)
                .clickable(onClick = onComplete),
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = painterResource(id = R.drawable.ic_lifebuoy_ring),
                contentDescription = "Complete",
                modifier = Modifier
                    .size(completeDiameter)
                    .graphicsLayer { if (processing) rotationZ = spin }
            )
            // Black circular center badge
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF111116)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier
                        .size(13.dp)
                        .graphicsLayer { if (processing) rotationZ = spin }
                )
            }
        }
    }
}

/**
 * Dynamically measures and fits the detected application name so it remains visible
 * at all times inside the compact pill without clipping or disappearing, adapting
 * smoothly from short names (e.g. TikTok, Google) to longer names (e.g. WhatsApp,
 * Instagram, Telegram, Google AI Studio).
 */
@Composable
private fun AutoFittedAppName(
    text: String,
    maxWidth: Dp,
    color: Color,
    textShadow: Shadow? = null,
    modifier: Modifier = Modifier
) {
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val maxWidthPx = with(density) { maxWidth.toPx() }

    val calculatedSizeSp = remember(text, maxWidthPx) {
        if (text.isBlank()) return@remember 9.5f
        // Candidate font sizes tested sequentially down to 5.0sp
        val candidateSizes = listOf(9.5f, 9.0f, 8.5f, 8.0f, 7.5f, 7.0f, 6.5f, 6.0f, 5.5f, 5.0f)
        for (candidate in candidateSizes) {
            val result = textMeasurer.measure(
                text = text,
                style = TextStyle(
                    fontSize = candidate.sp,
                    fontWeight = FontWeight.Normal,
                    platformStyle = PlatformTextStyle(includeFontPadding = false)
                ),
                maxLines = 1
            )
            if (result.size.width <= maxWidthPx) {
                return@remember candidate
            }
        }
        // If still wider than maxWidthPx at 5.0sp, scale down proportionally to guaranteed fit
        val baseMeasure = textMeasurer.measure(
            text = text,
            style = TextStyle(
                fontSize = 5.0f.sp,
                fontWeight = FontWeight.Normal,
                platformStyle = PlatformTextStyle(includeFontPadding = false)
            ),
            maxLines = 1
        )
        if (baseMeasure.size.width > 0f && baseMeasure.size.width > maxWidthPx) {
            ((maxWidthPx / baseMeasure.size.width) * 5.0f).coerceIn(4.0f, 5.0f)
        } else {
            5.0f
        }
    }

    Text(
        text = text,
        color = color,
        fontSize = calculatedSizeSp.sp,
        lineHeight = (calculatedSizeSp + 1.5f).sp,
        fontWeight = FontWeight.Normal,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.Center,
        style = TextStyle(
            shadow = textShadow,
            platformStyle = PlatformTextStyle(includeFontPadding = false)
        ),
        modifier = modifier.widthIn(max = maxWidth)
    )
}

