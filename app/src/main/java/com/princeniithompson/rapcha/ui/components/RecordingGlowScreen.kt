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
import androidx.compose.foundation.layout.navigationBarsPadding
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
import com.princeniithompson.rapcha.service.floating.BubbleChromeStyle
import com.princeniithompson.rapcha.service.floating.DynamicTonePolicy
import com.princeniithompson.rapcha.service.floating.RecordingGlowPreview
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
    modifier: Modifier = Modifier
) {
    val resolved = RecordingGlowList.resolve(selectedStyleId)
    LaunchedEffect(selectedStyleId) {
        if (RecordingGlowList.isRetired(selectedStyleId)) onSelectStyle(resolved)
    }
    val pagerState = rememberPagerState(pageCount = { RecordingGlowPreview.PAGE_COUNT })
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
            .background(Color(RecordingGlowColors.PAGE))
            .verticalScroll(scroll)
            .navigationBarsPadding()
            .padding(14.dp)
            .padding(bottom = 20.dp)
    ) {
        Box(modifier = Modifier.fillMaxWidth().height(34.dp)) {
            HeaderButton(Icons.AutoMirrored.Filled.ArrowBack, Modifier.align(Alignment.CenterStart), onDismiss)
            Text(
                text = "Recording glows",
                color = Color(RecordingGlowColors.TEXT),
                fontSize = 17.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.align(Alignment.Center)
            )
            HeaderButton(Icons.Filled.Close, Modifier.align(Alignment.CenterEnd), onDismiss)
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
                        .background(Color(if (current) RecordingGlowColors.ACCENT else RecordingGlowColors.DOT))
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        HorizontalPager(
            state = pagerState,
            contentPadding = PaddingValues(horizontal = 20.dp),
            pageSpacing = 12.dp,
            modifier = Modifier.fillMaxWidth().height(190.dp)
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
                    WordsBubblePreview(glowStyleId = resolved, tone = tone)
                } else {
                    MiniBubblePreview(
                        glowStyleId = resolved,
                        tone = tone,
                        animate = active
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(14.dp))
        WordsMiniSwitch(
            active = activeChrome,
            onChoose = { chosen ->
                token++
                onChooseChrome(chosen)
                scope.launch {
                    pagerState.animateScrollToPage(RecordingGlowPreview.pageFor(chosen))
                }
            }
        )
        Spacer(modifier = Modifier.height(14.dp))
        GlowModeControl(tone = tone, onChoose = { token++; onChooseTone(it) })
        Spacer(modifier = Modifier.height(16.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            RecordingGlowList.glows.forEach { glow ->
                val selected = glow.id == resolved
                GlowRow(glow = glow, deep = tone == DynamicTonePolicy.DEEP, selected = selected) {
                    token++
                    onSelectStyle(glow.id)
                }
            }
        }
    }
}

@Composable
private fun HeaderButton(icon: ImageVector, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(Color(RecordingGlowColors.HEADER_FILL))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = Color(RecordingGlowColors.TEXT), modifier = Modifier.size(18.dp))
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
                .then(
                    if (active) {
                        Modifier
                            .shadow(12.dp, RoundedCornerShape(28.dp), spotColor = Color(RecordingGlowColors.ACCENT), ambientColor = Color(RecordingGlowColors.ACCENT))
                            .border(2.dp, Color(RecordingGlowColors.ACCENT), RoundedCornerShape(28.dp))
                            .padding(3.dp)
                    } else Modifier
                )
                .clickable {
                    scope.launch {
                        pop.snapTo(0.95f)
                        pop.animateTo(1f, tween(240))
                    }
                    onTap()
                }
        ) {
            content()
        }
    }
}

@Composable
private fun GlowModeControl(tone: String, onChoose: (String) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(percent = 50))
                .background(Color(RecordingGlowColors.MODE_TRACK))
                .padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            ModeOption("Luminous", Icons.Filled.WbSunny, tone == DynamicTonePolicy.LUMINOUS) {
                onChoose(DynamicTonePolicy.LUMINOUS)
            }
            ModeOption("Deep", Icons.Filled.DarkMode, tone == DynamicTonePolicy.DEEP) {
                onChoose(DynamicTonePolicy.DEEP)
            }
        }
    }
}

@Composable
private fun ModeOption(label: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.96f else 1f, tween(150), label = "press")
    val fill by animateColorAsState(
        if (selected) Color(RecordingGlowColors.ACCENT) else Color.Transparent,
        tween(200),
        label = "fill"
    )
    val ink = if (selected) Color(RecordingGlowColors.ON_SELECTED) else Color(RecordingGlowColors.MODE_OFF)
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
private fun GlowRow(glow: RecordingGlow, deep: Boolean, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 58.dp)
            .clip(shape)
            .background(Color(if (selected) RecordingGlowColors.ROW_ON else RecordingGlowColors.ROW))
            .then(
                if (selected) Modifier.border(1.5.dp, Color(RecordingGlowColors.ROW_RING), shape) else Modifier
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(width = 54.dp, height = 42.dp)
                .clip(RoundedCornerShape(13.dp))
                .drawBehind { drawRect(glowBrush(glow, deep, size)) }
        )
        Text(
            text = glow.name,
            color = Color(RecordingGlowColors.TEXT),
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f).padding(horizontal = 12.dp)
        )
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .then(
                    if (selected) Modifier.background(Color(RecordingGlowColors.ACCENT))
                    else Modifier.border(2.dp, Color(RecordingGlowColors.HOLLOW), CircleShape)
                ),
            contentAlignment = Alignment.Center
        ) {
            if (selected) {
                Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
            }
        }
    }
}
