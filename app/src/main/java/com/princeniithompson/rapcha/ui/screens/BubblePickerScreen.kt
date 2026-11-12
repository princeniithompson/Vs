package com.princeniithompson.rapcha.ui.screens

import android.graphics.ColorMatrixColorFilter
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.princeniithompson.rapcha.service.floating.BubbleType
import com.princeniithompson.rapcha.service.floating.BubbleTypeRegistry
import com.princeniithompson.rapcha.service.floating.RecordingGlowPreview
import com.princeniithompson.rapcha.ui.components.overlay.AuroraColorPalette
import com.princeniithompson.rapcha.ui.components.overlay.rememberDynamicAuroraPalette
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * BUBBLE PICKER PAGE
 *
 * Requirements:
 * - Top bar: Back button + serif title "Bubble". Hide bottom nav.
 * - Horizontal pager with 1 page per registered bubble type. Neighbor cards peeking (~88% width, 12dp gap).
 * - Each card: 28dp radius stage-style card (~230dp tall) with live animated bubble preview at real size,
 *   selected glow & mode, plus top-left frosted name tag.
 * - Dots below following the page.
 * - Auto-scroll every 19 seconds, restarting timer on touch.
 * - Selection: Tapping card makes that bubble active (animate pop 0.95 to 1 over 240ms) and saves it.
 * - Active card: Full color, 2.5dp primary outer ring with 3dp transparent gap + 24dp primary check badge.
 * - Inactive cards: alpha 0.38, saturation 0.25, scale 0.94 over 350ms.
 */
@Composable
fun BubblePickerScreen(
    activeBubbleId: String,
    selectedGlowStyleId: String,
    tone: String,
    onChooseBubble: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler { onBack() }

    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val palette = rememberDynamicAuroraPalette()

    val bubbleTypes = remember { BubbleTypeRegistry.getAll() }
    val initialPage = remember(activeBubbleId) {
        val idx = bubbleTypes.indexOfFirst { it.id == activeBubbleId }
        if (idx >= 0) idx else 0
    }

    val pagerState = rememberPagerState(
        initialPage = initialPage,
        pageCount = { bubbleTypes.size }
    )
    val scope = rememberCoroutineScope()

    // 19-second automatic carousel auto-scroll
    LaunchedEffect(pagerState.settledPage) {
        delay(RecordingGlowPreview.IDLE_PAGE_MS)
        if (!pagerState.isScrollInProgress) {
            val nextPage = (pagerState.settledPage + 1) % bubbleTypes.size
            pagerState.animateScrollToPage(
                page = nextPage,
                animationSpec = tween(durationMillis = 750)
            )
        }
    }

    val stageAccentColor = getGlowAccentColor(selectedGlowStyleId, palette)
    val animatedStageAccent by animateColorAsState(
        targetValue = stageAccentColor.copy(alpha = 0.35f),
        animationSpec = tween(durationMillis = 300),
        label = "stageAccent"
    )

    val cardSurface = if (isDark) Color(0xFF16151F) else Color.White

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            // Top Bar
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .padding(horizontal = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.align(Alignment.CenterStart)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back to Style",
                        tint = MaterialTheme.colorScheme.onBackground
                    )
                }

                Text(
                    text = "Bubble",
                    fontSize = 28.sp,
                    fontFamily = FontFamily.Serif,
                    fontWeight = FontWeight.Normal,
                    color = if (isDark) Color(0xFFF1F5F9) else Color(0xFF1A202C),
                    modifier = Modifier.align(Alignment.Center)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Horizontal Carousel Pager (Neighbor cards peeking: contentPadding horizontal 24.dp)
            HorizontalPager(
                state = pagerState,
                contentPadding = PaddingValues(horizontal = 24.dp),
                pageSpacing = 12.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(260.dp)
            ) { page ->
                val type = bubbleTypes.getOrNull(page) ?: return@HorizontalPager
                val isSelected = type.id == activeBubbleId

                PickerCardTile(
                    bubbleType = type,
                    isSelected = isSelected,
                    selectedGlowStyleId = selectedGlowStyleId,
                    tone = tone,
                    cardSurface = cardSurface,
                    animatedStageAccent = animatedStageAccent,
                    isDark = isDark,
                    onClick = {
                        onChooseBubble(type.id)
                    }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Pager Dots Indicator
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                repeat(bubbleTypes.size) { index ->
                    val isCurrent = pagerState.currentPage == index
                    val dotWidth by animateFloatAsState(
                        targetValue = if (isCurrent) 22f else 6f,
                        animationSpec = spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMediumLow),
                        label = "dotWidth"
                    )
                    val dotColor by animateColorAsState(
                        targetValue = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                        animationSpec = tween(durationMillis = 350),
                        label = "dotColor"
                    )

                    Box(
                        modifier = Modifier
                            .padding(horizontal = 3.dp)
                            .height(6.dp)
                            .width(dotWidth.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(dotColor)
                    )
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun PickerCardTile(
    bubbleType: BubbleType,
    isSelected: Boolean,
    selectedGlowStyleId: String,
    tone: String,
    cardSurface: Color,
    animatedStageAccent: Color,
    isDark: Boolean,
    onClick: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val pop = remember { Animatable(1f) }

    val cardScale by animateFloatAsState(
        targetValue = if (isSelected) 1.0f else 0.94f,
        animationSpec = tween(durationMillis = 350),
        label = "cardScale"
    )

    val cardAlpha by animateFloatAsState(
        targetValue = if (isSelected) 1.0f else 0.38f,
        animationSpec = tween(durationMillis = 350),
        label = "cardAlpha"
    )

    val shape = RoundedCornerShape(28.dp)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(230.dp)
            .graphicsLayer {
                scaleX = cardScale * pop.value
                scaleY = cardScale * pop.value
                alpha = cardAlpha
                renderEffect = if (!isSelected && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val matrix = android.graphics.ColorMatrix().apply { setSaturation(0.25f) }
                    android.graphics.RenderEffect
                        .createColorFilterEffect(ColorMatrixColorFilter(matrix))
                        .asComposeRenderEffect()
                } else {
                    null
                }
            }
            .then(
                if (isSelected) {
                    Modifier
                        .border(2.5.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(31.dp))
                        .padding(5.5.dp)
                } else Modifier
            )
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        cardSurface,
                        animatedStageAccent
                    )
                )
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                scope.launch {
                    pop.snapTo(0.95f)
                    pop.animateTo(1f, tween(240))
                }
                onClick()
            }
            .testTag("picker_card_${bubbleType.id}"),
        contentAlignment = Alignment.Center
    ) {
        // Bubble Live Preview
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            bubbleType.previewComposable(selectedGlowStyleId, tone, 0f)
        }

        // Top-Left Name Tag
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(14.dp)
                .clip(RoundedCornerShape(percent = 50))
                .background(cardSurface.copy(alpha = 0.85f))
                .padding(horizontal = 10.dp, vertical = 4.dp)
        ) {
            Text(
                text = bubbleType.displayName,
                color = if (isDark) Color(0xFFF1F5F9) else Color(0xFF1A202C),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
        }

        // Selected Check Badge at Top Right
        if (isSelected) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp)
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

private fun getGlowAccentColor(styleId: String, palette: AuroraColorPalette): Color {
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
