package com.princeniithompson.rapcha.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.princeniithompson.rapcha.service.floating.BubbleChromeStyle
import com.princeniithompson.rapcha.service.floating.DynamicTonePolicy
import com.princeniithompson.rapcha.service.floating.RecordingGlowPreview
import com.princeniithompson.rapcha.ui.components.overlay.AuroraColorPalette
import com.princeniithompson.rapcha.ui.components.overlay.CompactRecordingBar
import com.princeniithompson.rapcha.ui.components.overlay.rememberDynamicAuroraPalette
import kotlin.math.PI
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun RecordingGlowScreen(
    selectedStyleId: String,
    activeChrome: BubbleChromeStyle,
    tone: String,
    idle: Boolean,
    onSelectStyle: (String) -> Unit,
    onChooseChrome: (BubbleChromeStyle) -> Unit,
    onChooseTone: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    initialPreviewPage: Int = 0
) {
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val palette = rememberDynamicAuroraPalette()

    // Read initial saved page synchronously before first frame
    val initialPage = remember { RecordingGlowPreview.pageFor(activeChrome) }
    val pagerState = rememberPagerState(
        initialPage = initialPage,
        pageCount = { RecordingGlowPreview.PAGE_COUNT }
    )

    // 19-second automatic page browsing when idle (never selects, only browses pages)
    LaunchedEffect(idle, pagerState.settledPage) {
        if (idle) {
            delay(RecordingGlowPreview.IDLE_PAGE_MS)
            if (!pagerState.isScrollInProgress) {
                val nextPage = (pagerState.settledPage + 1) % RecordingGlowPreview.PAGE_COUNT
                pagerState.animateScrollToPage(
                    page = nextPage,
                    animationSpec = tween(durationMillis = 750)
                )
            }
        }
    }

    // Motion animation variables for live glow rendering
    val motion = rememberInfiniteTransition(label = "glowStage")
    val phase1 by motion.animateFloat(
        initialValue = 0f,
        targetValue = (2f * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(2600, easing = LinearEasing), RepeatMode.Restart),
        label = "phase1"
    )
    val phase2 by motion.animateFloat(
        initialValue = (2f * PI).toFloat(),
        targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(3600, easing = LinearEasing), RepeatMode.Restart),
        label = "phase2"
    )
    val amplitude by motion.animateFloat(
        initialValue = 0.82f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            tween(1100, easing = androidx.compose.animation.core.FastOutSlowInEasing),
            RepeatMode.Reverse
        ),
        label = "amplitude"
    )

    // Primary accent tint for stage card background bottom gradient
    val stageAccentColor = remember(selectedStyleId, palette) {
        getGlowStyleAccentColor(selectedStyleId, palette)
    }
    val animatedStageAccent by animateColorAsState(
        targetValue = stageAccentColor.copy(alpha = 0.70f),
        animationSpec = tween(durationMillis = 300),
        label = "stageAccent"
    )

    val activeStyleId = remember(selectedStyleId) {
        if (GlowAnimationCatalogue.styles.any { isGlowMatch(it.id, it.name, selectedStyleId) }) {
            selectedStyleId
        } else {
            GlowAnimationCatalogue.DEFAULT_ID
        }
    }

    // Selection status of currently showing page on stage
    val currentlyShowingStyle = RecordingGlowPreview.styleForPage(pagerState.currentPage)
    val isShowingSelectedBubble = (activeChrome == currentlyShowingStyle)
    val selectionAlpha by animateFloatAsState(
        targetValue = if (isShowingSelectedBubble) 1f else 0f,
        animationSpec = tween(durationMillis = 150),
        label = "selectionAlpha"
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 90.dp)
        ) {
            // 1. Top Header Bar
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier.align(Alignment.Center)
                    ) {
                        androidx.compose.foundation.Image(
                            painter = androidx.compose.ui.res.painterResource(id = com.princeniithompson.rapcha.R.drawable.ic_lifebuoy_ring),
                            contentDescription = "Rapcha Logo",
                            modifier = Modifier
                                .size(34.dp)
                                .padding(end = 8.dp)
                        )
                        Text(
                            text = "Rapcha",
                            color = MaterialTheme.colorScheme.onBackground,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // 2. SECTION: BUBBLE
            item {
                Text(
                    text = "Bubble",
                    fontSize = 28.sp,
                    fontFamily = FontFamily.Serif,
                    fontWeight = FontWeight.Normal,
                    color = if (isDark) Color(0xFFF1F5F9) else Color(0xFF1A202C),
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
                )
            }

            // 3. STAGE CARD (Preview Carousel with Outer Ring and Check Badge)
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Outer Ring Box (padding guarantees ring fits without layout shift or clipping)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(
                                width = 2.5.dp,
                                color = MaterialTheme.colorScheme.primary.copy(alpha = selectionAlpha),
                                shape = RoundedCornerShape(33.5.dp)
                            )
                            .padding(5.5.dp)
                    ) {
                        // Stage Card
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(230.dp)
                                .clip(RoundedCornerShape(28.dp))
                                .background(
                                    Brush.verticalGradient(
                                        colors = listOf(
                                            Color(0xEB161E2E), // Home hero top color
                                            animatedStageAccent
                                        )
                                    )
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            HorizontalPager(
                                state = pagerState,
                                contentPadding = PaddingValues(horizontal = 16.dp),
                                pageSpacing = 12.dp,
                                modifier = Modifier.fillMaxSize()
                            ) { page ->
                                val pageStyle = RecordingGlowPreview.styleForPage(page)
                                StageBubblePage(
                                    onTap = {
                                        // Tap selects the currently showing bubble (if not already selected)
                                        if (activeChrome != pageStyle) {
                                            onChooseChrome(pageStyle)
                                        }
                                    }
                                ) {
                                    if (pageStyle == BubbleChromeStyle.ORIGINAL) {
                                        FloatingDictationPopup(
                                            transcriptText = "",
                                            isRecording = false,
                                            isPendingFinalizing = false,
                                            isPolishing = false,
                                            audioAmplitude = 0f,
                                            glowStyleId = selectedStyleId,
                                            onCancelClick = {},
                                            onPolishClick = {},
                                            onCompleteClick = {},
                                            onLifebuoyClick = {},
                                            blankPreview = true,
                                            mimicTalkingWhenSilent = true
                                        )
                                    } else {
                                        CompactRecordingBar(
                                            elapsedSeconds = 0,
                                            appName = "WhatsApp",
                                            glowStyleId = selectedStyleId,
                                            palette = palette,
                                            audioAmplitude = 0f,
                                            processing = false,
                                            mimicTalkingWhenSilent = true,
                                            showAppName = true,
                                            onCancel = {},
                                            onComplete = {}
                                        )
                                    }
                                }
                            }

                            // Check badge at top-right inside stage card (12dp from edges)
                            if (selectionAlpha > 0.01f) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(12.dp)
                                        .size(24.dp)
                                        .graphicsLayer { alpha = selectionAlpha }
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.primary),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Check,
                                        contentDescription = "Selected",
                                        tint = MaterialTheme.colorScheme.onPrimary,
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Pager Dynamic Morphing Dots
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        repeat(RecordingGlowPreview.PAGE_COUNT) { index ->
                            val isSelected = pagerState.currentPage == index
                            val dotWidth by animateDpAsState(
                                targetValue = if (isSelected) 22.dp else 6.dp,
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioMediumBouncy,
                                    stiffness = Spring.StiffnessMediumLow
                                ),
                                label = "dotWidth"
                            )
                            val dotColor by animateColorAsState(
                                targetValue = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                animationSpec = tween(durationMillis = 350),
                                label = "dotColor"
                            )

                            Box(
                                modifier = Modifier
                                    .height(6.dp)
                                    .width(dotWidth)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(dotColor)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))
            }

            // 4. SECTION: GLOW + FILTER CHIPS ROW
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Glow",
                        fontSize = 28.sp,
                        fontFamily = FontFamily.Serif,
                        fontWeight = FontWeight.Normal,
                        color = if (isDark) Color(0xFFF1F5F9) else Color(0xFF1A202C)
                    )

                    // Luminous / Deep filter chips
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FilterChip(
                            selected = tone == DynamicTonePolicy.LUMINOUS,
                            onClick = { onChooseTone(DynamicTonePolicy.LUMINOUS) },
                            label = {
                                Text(
                                    text = "Luminous",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Filled.WbSunny,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors()
                        )

                        FilterChip(
                            selected = tone == DynamicTonePolicy.DEEP,
                            onClick = { onChooseTone(DynamicTonePolicy.DEEP) },
                            label = {
                                Text(
                                    text = "Deep",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Filled.DarkMode,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors()
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
            }

            // 5. GLOW GRID (2 Columns, 8 Styles)
            item {
                val styles = GlowAnimationCatalogue.styles
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    for (i in styles.indices step 2) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            val item1 = styles[i]
                            val isSelected1 = isGlowMatch(item1.id, item1.name, activeStyleId)
                            GlowGridTile(
                                style = item1,
                                palette = palette,
                                phase1 = phase1,
                                phase2 = phase2,
                                amplitude = amplitude,
                                selected = isSelected1,
                                isDark = isDark,
                                modifier = Modifier.weight(1f),
                                onClick = { onSelectStyle(item1.id) }
                            )

                            if (i + 1 < styles.size) {
                                val item2 = styles[i + 1]
                                val isSelected2 = isGlowMatch(item2.id, item2.name, activeStyleId)
                                GlowGridTile(
                                    style = item2,
                                    palette = palette,
                                    phase1 = phase1,
                                    phase2 = phase2,
                                    amplitude = amplitude,
                                    selected = isSelected2,
                                    isDark = isDark,
                                    modifier = Modifier.weight(1f),
                                    onClick = { onSelectStyle(item2.id) }
                                )
                            } else {
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StageBubblePage(
    onTap: () -> Unit,
    content: @Composable () -> Unit
) {
    val pop = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier.graphicsLayer {
                scaleX = pop.value
                scaleY = pop.value
            }
        ) {
            content()
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clickable {
                        scope.launch {
                            pop.snapTo(0.95f)
                            pop.animateTo(1f, tween(240))
                        }
                        onTap()
                    }
            )
        }
    }
}

@Composable
private fun GlowGridTile(
    style: GlowAnimationStyle,
    palette: AuroraColorPalette,
    phase1: Float,
    phase2: Float,
    amplitude: Float,
    selected: Boolean,
    isDark: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()

    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = tween(durationMillis = 150),
        label = "tilePress"
    )

    val shape = RoundedCornerShape(20.dp)

    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .then(
                if (selected) {
                    Modifier
                        .border(2.5.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(23.dp))
                        .padding(5.5.dp)
                } else Modifier
            )
            .aspectRatio(1.25f)
            .then(
                if (!isDark) {
                    Modifier.shadow(2.dp, shape)
                } else Modifier
            )
            .clip(shape)
            .background(Color(0xFF0F172A))
            .clickable(
                interactionSource = interaction,
                indication = androidx.compose.material3.ripple(),
                onClick = onClick
            )
            .testTag("glow_tile_${style.id}")
    ) {
        // Live Glow Canvas Background
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawBehind {
                    renderGlowAnimation(
                        styleId = style.id,
                        palette = palette,
                        effectiveAmp = amplitude,
                        glowBrightness = if (selected) 1.0f else 0.88f,
                        phase1 = phase1,
                        phase2 = phase2
                    )
                }
        )

        // Bottom 35% dark gradient scrim for readability
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1.25f)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color.Black.copy(alpha = 0.35f)
                        ),
                        startY = 0.65f
                    )
                )
        )

        // Title at Bottom Left
        Text(
            text = style.name,
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(12.dp)
        )

        // Selected Check Badge at Top Right
        if (selected) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = "Selected",
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(14.dp)
                )
            }
        }
    }
}

private fun getGlowStyleAccentColor(styleId: String, palette: AuroraColorPalette): Color {
    return when (styleId) {
        "breathing_horizon" -> palette.primary
        "sunset_mirage" -> Color(0xFFFF5252)
        "gentle_slice" -> palette.primaryLight
        "pulsing_spotlight" -> Color(0xFFFFB74D)
        "electric_sunburst" -> Color(0xFFFFA000)
        "bioluminescent_deep" -> Color(0xFF00E5FF)
        "liquid_glass" -> Color(0xFF81D4FA)
        "velvet_aura" -> Color(0xFF7E57C2)
        else -> palette.primary
    }
}

private fun isGlowMatch(styleId: String, styleName: String, target: String): Boolean {
    if (target.isBlank()) return false
    val t = target.trim()
    if (styleId.equals(t, ignoreCase = true)) return true
    if (styleName.equals(t, ignoreCase = true)) return true
    val normStyleId = styleId.replace("_", "").lowercase()
    val normTarget = t.replace("_", "").replace(" ", "").lowercase()
    if (normStyleId == normTarget) return true
    val normName = styleName.replace("_", "").replace(" ", "").lowercase()
    if (normName == normTarget) return true
    if (styleId == "gentle_slice" && t.startsWith("gentle", ignoreCase = true)) return true
    if (styleId == "pulsing_spotlight" && (t.startsWith("flashlight", ignoreCase = true) || t.startsWith("spotlight", ignoreCase = true))) return true
    return false
}
