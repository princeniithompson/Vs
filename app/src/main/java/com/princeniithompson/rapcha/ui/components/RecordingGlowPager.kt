package com.princeniithompson.rapcha.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.princeniithompson.rapcha.service.FloatingBubbleManager
import com.princeniithompson.rapcha.service.floating.BubbleChromeStyle
import com.princeniithompson.rapcha.service.floating.DynamicTonePolicy
import com.princeniithompson.rapcha.service.floating.RecordingGlowPreview
import com.princeniithompson.rapcha.ui.components.overlay.CompactRecordingBar
import com.princeniithompson.rapcha.ui.components.overlay.getDynamicTonePalette
import com.princeniithompson.rapcha.ui.components.overlay.rememberDynamicAuroraPalette
import kotlin.math.PI

@Composable
fun RecordingGlowCustomization(
    selectedStyleId: String,
    activeChrome: BubbleChromeStyle,
    idle: Boolean
) {
    val context = LocalContext.current
    val palette = rememberDynamicAuroraPalette()
    val tone by FloatingBubbleManager.selectedColorTone.collectAsState()
    val pagerState = rememberPagerState(pageCount = { RecordingGlowPreview.PAGE_COUNT })
    var nudge by remember { mutableIntStateOf(0) }
    LaunchedEffect(pagerState.settledPage, nudge, idle) {
        if (!RecordingGlowPreview.shouldAutoAdvance(idle, userInteracting = false, onScreen = true)) return@LaunchedEffect
        kotlinx.coroutines.delay(RecordingGlowPreview.IDLE_PAGE_MS)
        if (!pagerState.isScrollInProgress && idle) {
            pagerState.animateScrollToPage(RecordingGlowPreview.nextPage(pagerState.settledPage))
        }
    }
    val motion = rememberInfiniteTransition(label = "glowPreview")
    val phase1 by motion.animateFloat(
        0f,
        (2f * PI).toFloat(),
        infiniteRepeatable(tween(2600, easing = LinearEasing), RepeatMode.Restart),
        label = "a"
    )
    val phase2 by motion.animateFloat(
        (2f * PI).toFloat(),
        0f,
        infiniteRepeatable(tween(3600, easing = LinearEasing), RepeatMode.Restart),
        label = "b"
    )
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        HorizontalPager(
            state = pagerState,
            contentPadding = PaddingValues(horizontal = 4.dp),
            pageSpacing = 10.dp,
            modifier = Modifier.fillMaxWidth().height(210.dp)
        ) { page ->
            val pageStyle = if (page == 0) BubbleChromeStyle.ORIGINAL else BubbleChromeStyle.COMPACT
            val active = activeChrome == pageStyle
            Column {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(150.dp)
                        .clip(RoundedCornerShape(22.dp))
                        .background(
                            if (pageStyle == BubbleChromeStyle.COMPACT) Color(0xFF101418)
                            else palette.primary.copy(alpha = 0.18f)
                        )
                        .border(1.dp, palette.primaryVibrant.copy(alpha = 0.35f), RoundedCornerShape(22.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    if (pageStyle == BubbleChromeStyle.ORIGINAL) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            renderGlowAnimation(selectedStyleId, palette, 0.6f, 0.9f, phase1, phase2)
                        }
                        Text(
                            text = "Original bubble keeps the transcript on screen.",
                            color = Color.White,
                            fontSize = 13.sp,
                            modifier = Modifier.align(Alignment.CenterStart).padding(16.dp)
                        )
                    } else {
                        CompactRecordingBar(
                            elapsedSeconds = 84,
                            appName = "App",
                            glowStyleId = selectedStyleId,
                            palette = palette,
                            onCancel = {},
                            onComplete = {}
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(42.dp)
                        .clip(RoundedCornerShape(21.dp))
                        .background(if (active) palette.primaryVibrant else palette.primary.copy(alpha = 0.16f))
                        .clickable {
                            nudge += 1
                            val next = RecordingGlowPreview.styleAfterPreview(activeChrome, page, buttonPressed = true)
                            FloatingBubbleManager.setBubbleChrome(context, next)
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (active) {
                            Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.size(6.dp))
                        }
                        Text(
                            text = if (active) "Active" else "Select as active",
                            color = if (active) Color.White else palette.deep,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.Center
        ) {
            repeat(RecordingGlowPreview.PAGE_COUNT) { index ->
                val on = pagerState.currentPage == index
                Box(
                    modifier = Modifier
                        .padding(horizontal = 4.dp)
                        .size(if (on) 8.dp else 6.dp)
                        .clip(CircleShape)
                        .background(if (on) palette.primaryVibrant else palette.primary.copy(alpha = 0.35f))
                )
            }
        }
        Spacer(modifier = Modifier.height(10.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(DynamicTonePolicy.LUMINOUS to "Luminous", DynamicTonePolicy.DEEP to "Deep").forEach { (id, label) ->
                val selected = tone == id
                val swatch = remember(id) { getDynamicTonePalette(context, id) }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(36.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(if (selected) swatch.primaryVibrant else swatch.primary.copy(alpha = 0.2f))
                        .clickable {
                            nudge += 1
                            FloatingBubbleManager.setColorTone(context, id)
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        label,
                        color = if (selected) Color.White else swatch.deep,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp
                    )
                }
            }
        }
    }
}
