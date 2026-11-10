package com.princeniithompson.rapcha.ui.components

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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.princeniithompson.rapcha.service.FloatingBubbleManager
import com.princeniithompson.rapcha.service.floating.BubbleChromeStyle
import com.princeniithompson.rapcha.service.floating.DynamicTonePolicy
import com.princeniithompson.rapcha.service.floating.GlowModePaint
import com.princeniithompson.rapcha.service.floating.RecordingGlowPreview
import androidx.compose.runtime.remember
import com.princeniithompson.rapcha.ui.components.overlay.CompactRecordingBar
import com.princeniithompson.rapcha.ui.components.overlay.getDynamicTonePalette
import com.princeniithompson.rapcha.ui.components.overlay.rememberDynamicAuroraPalette

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
    LaunchedEffect(pagerState.settledPage, idle) {
        if (!RecordingGlowPreview.shouldAutoAdvance(idle, userInteracting = false, onScreen = true)) return@LaunchedEffect
        kotlinx.coroutines.delay(RecordingGlowPreview.IDLE_PAGE_MS)
        if (!pagerState.isScrollInProgress && idle) {
            pagerState.animateScrollToPage(RecordingGlowPreview.nextPage(pagerState.settledPage))
        }
    }
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        HorizontalPager(
            state = pagerState,
            contentPadding = PaddingValues(horizontal = 4.dp),
            pageSpacing = 10.dp,
            modifier = Modifier.fillMaxWidth().height(200.dp)
        ) { page ->
            val pageStyle = RecordingGlowPreview.styleForPage(page)
            Box(modifier = Modifier.fillMaxSize()) {
                if (pageStyle == BubbleChromeStyle.ORIGINAL) {
                    Box(
                        modifier = Modifier.fillMaxWidth().height(190.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        WordsBubblePreview(glowStyleId = selectedStyleId)
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(190.dp)
                            .clip(RoundedCornerShape(22.dp))
                            .background(Color(0xFF101418))
                            .border(1.dp, Color(GlowModePaint.LUMINOUS), RoundedCornerShape(22.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        CompactRecordingBar(
                            elapsedSeconds = 3,
                            appName = "App",
                            glowStyleId = selectedStyleId,
                            palette = palette,
                            audioAmplitude = 0.55f,
                            onCancel = {},
                            onComplete = {}
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable {
                            FloatingBubbleManager.setBubbleChrome(context, pageStyle)
                        }
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        WordsMiniSwitch(
            active = activeChrome,
            onChoose = { chosen ->
                FloatingBubbleManager.setBubbleChrome(context, chosen)
            }
        )
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
                        .background(if (on) Color(GlowModePaint.DEEP) else Color(GlowModePaint.LUMINOUS))
                )
            }
        }
        Spacer(modifier = Modifier.height(10.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(DynamicTonePolicy.LUMINOUS to "Luminous", DynamicTonePolicy.DEEP to "Deep").forEach { (id, label) ->
                val selected = tone == id
                val swatch = remember(id) { getDynamicTonePalette(context, id) }
                val fill = if (selected) swatch.primaryVibrant else swatch.primary
                val ink = if (selected) Color(GlowModePaint.ON_SELECTED) else swatch.deep
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(36.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(Color(GlowModePaint.opaque(fill.toArgb())))
                        .clickable { FloatingBubbleManager.setColorTone(context, id) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        label,
                        color = Color(GlowModePaint.opaque(ink.toArgb())),
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp
                    )
                }
            }
        }
    }
}
