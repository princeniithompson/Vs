package com.princeniithompson.rapcha.ui.components

import android.graphics.ColorMatrixColorFilter
import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import com.princeniithompson.rapcha.service.floating.BubbleChromeStyle
import com.princeniithompson.rapcha.service.floating.DynamicTonePolicy
import com.princeniithompson.rapcha.service.floating.RecordingGlowPreview
import com.princeniithompson.rapcha.ui.components.overlay.CompactRecordingBar
import com.princeniithompson.rapcha.ui.components.overlay.rememberDynamicAuroraPalette
import kotlin.math.PI
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
    val colors = rememberGlowPageColors()
    val palette = rememberDynamicAuroraPalette()
    val pagerState = rememberPagerState(
        initialPage = initialPreviewPage,
        pageCount = { RecordingGlowPreview.PAGE_COUNT }
    )
    val scope = rememberCoroutineScope()
    var token by remember { mutableIntStateOf(0) }
    LaunchedEffect(token, idle) {
        if (!RecordingGlowPreview.shouldAutoAdvance(idle, userInteracting = false, onScreen = true)) return@LaunchedEffect
        kotlinx.coroutines.delay(RecordingGlowPreview.IDLE_PAGE_MS)
        pagerState.animateScrollToPage(RecordingGlowPreview.nextPage(pagerState.settledPage))
    }
    val scroll = rememberScrollState()
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.page)
            .verticalScroll(scroll)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(top = 8.dp)
            .padding(horizontal = 14.dp)
            .padding(bottom = 20.dp)
    ) {
        Box(modifier = Modifier.fillMaxWidth().height(48.dp)) {
            HeaderButton(Icons.AutoMirrored.Filled.ArrowBack, colors, Modifier.align(Alignment.CenterStart), onDismiss)
            Text(
                text = "Recording glows",
                color = colors.text,
                fontSize = 17.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.align(Alignment.Center)
            )
            HeaderButton(Icons.Filled.Close, colors, Modifier.align(Alignment.CenterEnd), onDismiss)
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            repeat(RecordingGlowPreview.PAGE_COUNT) { index ->
                val current = pagerState.currentPage == index
                val width by animateDpAsState(if (current) 24.dp else 8.dp, tween(300), label = "dot")
                Box(
                    modifier = Modifier
                        .padding(horizontal = 4.dp)
                        .width(width)
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (current) colors.accent else colors.dot)
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        HorizontalPager(
            state = pagerState,
            contentPadding = PaddingValues(horizontal = 20.dp),
            pageSpacing = 12.dp,
            modifier = Modifier.fillMaxWidth().height(230.dp)
        ) { page ->
            val pageStyle = RecordingGlowPreview.styleForPage(page)
            val active = activeChrome == pageStyle
            BubblePage(
                active = active,
                onTap = {
                    token++
                    onChooseChrome(pageStyle)
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
                        blankPreview = true
                    )
                } else {
                    CompactRecordingBar(
                        elapsedSeconds = 0,
                        appName = "App",
                        glowStyleId = selectedStyleId,
                        palette = palette,
                        audioAmplitude = 0f,
                        processing = false,
                        onCancel = {},
                        onComplete = {}
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(14.dp))
        WordsMiniSwitch(
            active = activeChrome,
            rimColor = colors.rim,
            trackColor = colors.track,
            onChoose = { chosen ->
                token++
                onChooseChrome(chosen)
                scope.launch {
                    pagerState.animateScrollToPage(RecordingGlowPreview.pageFor(chosen))
                }
            }
        )
        Spacer(modifier = Modifier.height(14.dp))
        GlowModeControl(colors = colors, tone = tone, onChoose = { token++; onChooseTone(it) })
        Spacer(modifier = Modifier.height(16.dp))
        val motion = rememberInfiniteTransition(label = "glowCards")
        val phase1 by motion.animateFloat(
            0f, (2f * PI).toFloat(),
            infiniteRepeatable(tween(2600, easing = LinearEasing), RepeatMode.Restart),
            label = "phase1"
        )
        val phase2 by motion.animateFloat(
            (2f * PI).toFloat(), 0f,
            infiniteRepeatable(tween(3600, easing = LinearEasing), RepeatMode.Restart),
            label = "phase2"
        )
        val amplitude by motion.animateFloat(
            0.18f, 0.85f,
            infiniteRepeatable(tween(1400, easing = androidx.compose.animation.core.FastOutSlowInEasing), RepeatMode.Reverse),
            label = "amp"
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            GlowAnimationCatalogue.styles.forEach { style ->
                GlowRow(
                    name = style.name,
                    styleId = style.id,
                    palette = palette,
                    phase1 = phase1,
                    phase2 = phase2,
                    amplitude = amplitude,
                    colors = colors,
                    selected = style.id == selectedStyleId
                ) {
                    token++
                    onSelectStyle(style.id)
                }
            }
        }
    }
}

@Composable
private fun HeaderButton(icon: ImageVector, colors: GlowPageColors, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier.size(48.dp).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(colors.header),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = colors.text, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun BubblePage(active: Boolean, onTap: () -> Unit, content: @Composable () -> Unit) {
    val scale by animateFloatAsState(if (active) 1f else 0.94f, tween(350), label = "scale")
    val fade by animateFloatAsState(if (active) 1f else 0.38f, tween(350), label = "fade")
    val pop = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .graphicsLayer {
                    scaleX = scale * pop.value
                    scaleY = scale * pop.value
                    alpha = fade
                    renderEffect = if (!active && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        val matrix = android.graphics.ColorMatrix().apply { setSaturation(0.25f) }
                        android.graphics.RenderEffect
                            .createColorFilterEffect(ColorMatrixColorFilter(matrix))
                            .asComposeRenderEffect()
                    } else {
                        null
                    }
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
private fun GlowModeControl(colors: GlowPageColors, tone: String, onChoose: (String) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(percent = 50))
                .background(colors.modeTrack)
                .padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            ModeOption("Luminous", Icons.Filled.WbSunny, colors, tone == DynamicTonePolicy.LUMINOUS) {
                onChoose(DynamicTonePolicy.LUMINOUS)
            }
            ModeOption("Deep", Icons.Filled.DarkMode, colors, tone == DynamicTonePolicy.DEEP) {
                onChoose(DynamicTonePolicy.DEEP)
            }
        }
    }
}

@Composable
private fun ModeOption(label: String, icon: ImageVector, colors: GlowPageColors, selected: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.96f else 1f, tween(150), label = "press")
    val fill by animateColorAsState(
        if (selected) colors.accent else Color.Transparent,
        tween(200),
        label = "fill"
    )
    val ink = if (selected) colors.onAccent else colors.modeOff
    Row(
        modifier = Modifier
            .heightIn(min = 48.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(percent = 50))
            .then(if (selected) Modifier.shadow(2.dp, RoundedCornerShape(percent = 50)) else Modifier)
            .background(fill)
            .clickable(interactionSource = interaction, indication = androidx.compose.material3.ripple(), onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(16.dp))
        Spacer(modifier = Modifier.width(6.dp))
        Text(label, color = ink, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun GlowRow(
    name: String,
    styleId: String,
    palette: com.princeniithompson.rapcha.ui.components.overlay.AuroraColorPalette,
    phase1: Float,
    phase2: Float,
    amplitude: Float,
    colors: GlowPageColors,
    selected: Boolean,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(84.dp)
            .clip(shape)
            .background(if (selected) colors.rowOn else colors.row)
            .then(if (selected) Modifier.border(1.5.dp, colors.accent, shape) else Modifier)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(width = 68.dp, height = 54.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF0F172A))
                .drawBehind {
                    renderGlowAnimation(
                        styleId = styleId,
                        palette = palette,
                        effectiveAmp = amplitude,
                        glowBrightness = if (selected) 0.90f else 0.65f,
                        phase1 = phase1,
                        phase2 = phase2
                    )
                }
        )
        Text(
            text = name,
            color = if (selected) colors.accent else colors.text,
            fontSize = 15.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
            maxLines = 1,
            modifier = Modifier.weight(1f).padding(horizontal = 12.dp)
        )
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .then(
                    if (selected) Modifier.background(colors.accent)
                    else Modifier.border(1.5.dp, colors.dot, CircleShape)
                ),
            contentAlignment = Alignment.Center
        ) {
            if (selected) {
                Icon(Icons.Filled.Check, contentDescription = null, tint = colors.onAccent, modifier = Modifier.size(15.dp))
            }
        }
    }
}

private data class GlowPageColors(
    val page: Color,
    val text: Color,
    val header: Color,
    val dot: Color,
    val accent: Color,
    val onAccent: Color,
    val modeTrack: Color,
    val modeOff: Color,
    val row: Color,
    val rowOn: Color,
    val rim: Color,
    val track: Color
)

@Composable
private fun rememberGlowPageColors(): GlowPageColors {
    val scheme = MaterialTheme.colorScheme
    val dynamic = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    fun Color.solid(): Color = copy(alpha = 1f)
    return if (dynamic) {
        GlowPageColors(
            page = scheme.surface.solid(),
            text = scheme.onSurface.solid(),
            header = scheme.surfaceContainerHigh.solid(),
            dot = scheme.outlineVariant.solid(),
            accent = scheme.primary.solid(),
            onAccent = scheme.onPrimary.solid(),
            modeTrack = scheme.surfaceContainer.solid(),
            modeOff = scheme.onSurface.solid(),
            row = scheme.surfaceContainer.solid(),
            rowOn = scheme.primaryContainer.solid(),
            rim = scheme.surface.solid(),
            track = scheme.primaryContainer.solid()
        )
    } else {
        GlowPageColors(
            page = Color(RecordingGlowColors.PAGE),
            text = Color(RecordingGlowColors.TEXT),
            header = Color(RecordingGlowColors.HEADER_FILL),
            dot = Color(RecordingGlowColors.DOT),
            accent = Color(RecordingGlowColors.ACCENT),
            onAccent = Color(RecordingGlowColors.ON_SELECTED),
            modeTrack = Color(RecordingGlowColors.MODE_TRACK),
            modeOff = Color(RecordingGlowColors.MODE_OFF),
            row = Color(RecordingGlowColors.ROW),
            rowOn = Color(RecordingGlowColors.ROW_ON),
            rim = Color(0xFFEEF6DC),
            track = Color(0xFFB7D07A)
        )
    }
}
