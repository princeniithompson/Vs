package com.princeniithompson.rapcha.ui.screens

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import kotlin.math.absoluteValue
import android.os.Build
import com.princeniithompson.rapcha.data.HistoryItem
import com.princeniithompson.rapcha.data.HistoryOverflowMenu
import com.princeniithompson.rapcha.data.HistoryRepository
import com.princeniithompson.rapcha.data.RetryClipStore
import com.princeniithompson.rapcha.service.FloatingBubbleService
import com.princeniithompson.rapcha.ui.VoiceTypingViewModel
import com.princeniithompson.rapcha.data.LyraRating
import com.princeniithompson.rapcha.ui.components.InAppVoiceTypingBubble
import com.princeniithompson.rapcha.ui.components.LyraHistoryStages
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: VoiceTypingViewModel,
    onOpenMenu: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val historyItems by HistoryRepository.historyItems.collectAsState()
    var unratedOnly by remember { mutableStateOf(false) }
    val unratedCount = remember(historyItems) {
        historyItems.count { it.session?.reviewable == true && it.session.rating == LyraRating.UNRATED }
    }
    val visibleHistory = remember(historyItems, unratedOnly) {
        if (!unratedOnly) historyItems
        else historyItems.filter { it.session?.reviewable == true && it.session.rating == LyraRating.UNRATED }
    }

    // Group history items by date header ("Today", "Yesterday", "Sep 21, 2026", etc.)
    val groupedHistory = remember(visibleHistory) {
        visibleHistory.groupBy { it.getFormattedDateHeader() }
    }

    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Fixed Sticky Top Header Bar
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .background(MaterialTheme.colorScheme.background)
                .padding(horizontal = 14.dp),
            contentAlignment = Alignment.Center
        ) {
            IconButton(
                onClick = onOpenMenu,
                modifier = Modifier.align(Alignment.CenterStart)
            ) {
                Icon(
                    imageVector = Icons.Filled.Menu,
                    contentDescription = "Open Settings Menu",
                    tint = MaterialTheme.colorScheme.onBackground
                )
            }
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

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 90.dp)
        ) {

            // 2. Top Carousel: Voice-Typing Bubble (Page 0) + Real Stats Cards (Pages 1, 2, 3)
            item {
                TopCarouselSection(viewModel = viewModel, isDark = isDark)
                Spacer(modifier = Modifier.height(20.dp))
            }

            item {
                var showConfirmDialog by remember { mutableStateOf(false) }
                var clearedBackupItems by remember { mutableStateOf<List<HistoryItem>?>(null) }
                var snackbarMessage by remember { mutableStateOf<String?>(null) }

                // 5-second undo timer coroutine
                LaunchedEffect(clearedBackupItems) {
                    if (clearedBackupItems != null) {
                        delay(5000L)
                        clearedBackupItems = null
                        snackbarMessage = null
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FilterChip(
                            selected = !unratedOnly,
                            onClick = { unratedOnly = false },
                            label = { Text("All") }
                        )
                        FilterChip(
                            selected = unratedOnly,
                            onClick = { unratedOnly = true },
                            label = { Text(if (unratedCount > 0) "Unrated $unratedCount" else "Unrated") }
                        )
                    }

                    // Only show the reset/refresh button when on the Unrated page AND there are unrated items
                    if (unratedOnly && unratedCount > 0) {
                        androidx.compose.material3.OutlinedButton(
                            onClick = { showConfirmDialog = true },
                            modifier = Modifier
                                .height(36.dp)
                                .testTag("clear_unrated_button"),
                            shape = RoundedCornerShape(18.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp,
                                if (isDark) Color(0xFF334155) else Color(0xFFCBD5E1)
                            )
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Refresh,
                                contentDescription = "Clear Unrated",
                                modifier = Modifier.size(15.dp),
                                tint = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Reset",
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.Medium,
                                color = if (isDark) Color(0xFFCBD5E1) else Color(0xFF475569)
                            )
                        }
                    }
                }

                // Premium confirmation dialog in simple 10-year-old language
                if (showConfirmDialog) {
                    androidx.compose.material3.AlertDialog(
                        onDismissRequest = { showConfirmDialog = false },
                        shape = RoundedCornerShape(24.dp),
                        containerColor = if (isDark) Color(0xFF1E1D2B) else Color.White,
                        title = {
                            Text(
                                text = "Clear Unrated History?",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isDark) Color(0xFFF8FAFC) else Color(0xFF0F172A)
                            )
                        },
                        text = {
                            Text(
                                text = "Are you sure you want to do this? All unrated dictations on this page will be removed.",
                                fontSize = 14.5.sp,
                                lineHeight = 21.sp,
                                color = if (isDark) Color(0xFF94A3B8) else Color(0xFF475569)
                            )
                        },
                        confirmButton = {
                            androidx.compose.material3.Button(
                                onClick = {
                                    showConfirmDialog = false
                                    val removed = HistoryRepository.clearUnratedHistory()
                                    if (removed.isNotEmpty()) {
                                        clearedBackupItems = removed
                                        snackbarMessage = "Cleared unrated history"
                                    }
                                },
                                shape = RoundedCornerShape(16.dp),
                                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary
                                )
                            ) {
                                Text("Yes, clear it", fontWeight = FontWeight.SemiBold)
                            }
                        },
                        dismissButton = {
                            androidx.compose.material3.TextButton(
                                onClick = { showConfirmDialog = false }
                            ) {
                                Text(
                                    text = "Cancel",
                                    color = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B),
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    )
                }

                // 5-second Undo Android-style Notification Snackbar at bottom
                if (snackbarMessage != null && clearedBackupItems != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 8.dp)
                    ) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (isDark) Color(0xFF262433) else Color(0xFF1E293B)
                            ),
                            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = snackbarMessage ?: "Cleared unrated history",
                                    fontSize = 13.5.sp,
                                    color = Color.White,
                                    fontWeight = FontWeight.Normal
                                )
                                androidx.compose.material3.TextButton(
                                    onClick = {
                                        clearedBackupItems?.let { items ->
                                            HistoryRepository.restoreHistoryItems(items)
                                        }
                                        clearedBackupItems = null
                                        snackbarMessage = null
                                    }
                                ) {
                                    Text(
                                        text = "UNDO",
                                        fontSize = 13.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primaryContainer
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 3. Grouped Daily History List
            if (groupedHistory.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 48.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No dictations yet",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (isDark) Color(0xFF94A3B8) else Color(0xFF718096),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 32.dp)
                        )
                    }
                }
            } else {
                groupedHistory.forEach { (dateHeader, itemsInGroup) ->
                    // Section Header with elegant Serif typography
                    item(key = "header_$dateHeader") {
                        Text(
                            text = dateHeader,
                            fontSize = 28.sp,
                            fontFamily = FontFamily.Serif,
                            fontWeight = FontWeight.Normal,
                            color = if (isDark) Color(0xFFF1F5F9) else Color(0xFF1A202C),
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
                        )
                    }

                    // Cards in this date group
                    items(itemsInGroup, key = { it.id }) { item ->
                        HistoryCardItem(
                            item = item,
                            isDark = isDark,
                            showFullBreakdown = unratedOnly,
                            onCopy = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                val clip = android.content.ClipData.newPlainText("Transcription", item.text)
                                clipboard.setPrimaryClip(clip)
                                Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                            },
                            onShare = {
                                val sendIntent = Intent().apply {
                                    action = Intent.ACTION_SEND
                                    putExtra(Intent.EXTRA_TEXT, item.text)
                                    type = "text/plain"
                                }
                                context.startActivity(Intent.createChooser(sendIntent, "Share Transcription"))
                            },
                            onDelete = {
                                item.retryClipId?.let { clipId ->
                                    RetryClipStore.discard(clipId)
                                    FloatingBubbleService.instance?.noteRetryClipDeleted(clipId)
                                }
                                HistoryRepository.deleteHistoryItem(item.id)
                            },
                            onRetry = {
                                val clipId = item.retryClipId
                                val hasAudio = RetryClipStore.clipFile(clipId) != null
                                if (!HistoryOverflowMenu.retryEnabled(clipId, hasAudio)) {
                                    Toast.makeText(context, HistoryOverflowMenu.NO_AUDIO, Toast.LENGTH_SHORT).show()
                                } else {
                                    val retryIntent = Intent(context, FloatingBubbleService::class.java).apply {
                                        action = FloatingBubbleService.ACTION_RETRY_CLIP
                                        putExtra(FloatingBubbleService.EXTRA_CLIP_ID, clipId)
                                        putExtra(FloatingBubbleService.EXTRA_FROM_HISTORY, true)
                                    }
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                        context.startForegroundService(retryIntent)
                                    } else {
                                        context.startService(retryIntent)
                                    }
                                }
                            }
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                    }
                }
            }
        }
    }
}

/**
 * Top Carousel: Contains the Voice-Typing Bubble on Page 0 and Real Stats Cards on Pages 1, 2, 3.
 * Features 19-second automatic scrolling when idle. Auto-scrolling is disabled during active dictation.
 */
@Composable
private fun TopCarouselSection(viewModel: VoiceTypingViewModel, isDark: Boolean = false) {
    val isRecording by viewModel.isRecording.collectAsState()
    val finalizedTranscript by viewModel.finalizedTranscript.collectAsState()
    val interimTranscript by viewModel.interimTranscript.collectAsState()

    val hasActiveTranscription = isRecording || finalizedTranscript.isNotBlank() || interimTranscript.isNotBlank()

    // Real dynamic stats calculated from HistoryRepository
    val historyItems by HistoryRepository.historyItems.collectAsState()
    val totalWords = remember(historyItems) { HistoryRepository.getTotalWordsSpoken() }
    val uniqueApps = remember(historyItems) { HistoryRepository.getUniqueAppsCount() }
    val avgWpm = remember(historyItems) { HistoryRepository.getAverageWpm() }

    val formattedWords = remember(totalWords) { "%,d".format(totalWords) }

    val totalPages = 4 // 0: Voice Bubble, 1: Words Spoken, 2: Apps Used, 3: WPM
    val pagerState = rememberPagerState(pageCount = { totalPages })

    // Automatic scrolling every 19 seconds when idle - key on settledPage so animation is never cancelled mid-scroll
    LaunchedEffect(hasActiveTranscription, pagerState.settledPage) {
        if (!hasActiveTranscription) {
            delay(com.princeniithompson.rapcha.service.floating.RecordingGlowPreview.IDLE_PAGE_MS)
            if (!hasActiveTranscription && !pagerState.isScrollInProgress) {
                val nextPage = (pagerState.settledPage + 1) % totalPages
                pagerState.animateScrollToPage(
                    page = nextPage,
                    animationSpec = tween(
                        durationMillis = if (nextPage == 0) 900 else 750,
                        easing = androidx.compose.animation.core.FastOutSlowInEasing
                    )
                )
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        HorizontalPager(
            state = pagerState,
            contentPadding = PaddingValues(horizontal = 16.dp),
            pageSpacing = 12.dp,
            modifier = Modifier
                .fillMaxWidth()
                .height(210.dp)
        ) { page ->
            Box(
                modifier = Modifier.fillMaxSize()
            ) {
                when (page) {
                    0 -> {
                        // Page 0: Voice-Typing Bubble Card (Strictly clipped inside 26dp card)
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(RoundedCornerShape(26.dp))
                        ) {
                            InAppVoiceTypingBubble(
                                viewModel = viewModel,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                    1 -> {
                        // Page 1: Words Spoken (100% Real Data)
                        StatCardView(
                            statNumber = "$formattedWords words",
                            statColor = Color(0xFF0F766E), // Deep emerald
                            description = if (totalWords > 0) {
                                "spoken so far across your dictation sessions"
                            } else {
                                "spoken so far\nComplete a dictation session to start tracking"
                            },
                            pageIndex = 1,
                            isDark = isDark
                        )
                    }
                    2 -> {
                        // Page 2: Apps Used (100% Real Data)
                        StatCardView(
                            statNumber = "$uniqueApps ${if (uniqueApps == 1) "app" else "apps"}",
                            statColor = if (isDark) Color(0xFF93C5FD) else Color(0xFF1E293B),
                            description = if (uniqueApps > 0) {
                                "Number of applications you are flowing in"
                            } else {
                                "Number of applications you are flowing in\nDictate across apps to track usage"
                            },
                            pageIndex = 2,
                            isDark = isDark
                        )
                    }
                    3 -> {
                        // Page 3: WPM (100% Real Data)
                        StatCardView(
                            statNumber = "$avgWpm wpm",
                            statColor = Color(0xFF0D9488), // Teal
                            description = if (avgWpm > 0) {
                                "This is your words per minute speaking speed"
                            } else {
                                "Words per minute\nCalculated automatically from your voice typing"
                            },
                            pageIndex = 3,
                            isDark = isDark
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Material 3 Expressive Dynamic Morphing Pill Indicators
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            repeat(totalPages) { index ->
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
}

/**
 * Reusable clean stats card view matching Wispr Flow aesthetic
 */
@Composable
private fun StatCardView(
    statNumber: String,
    statColor: Color,
    description: String,
    pageIndex: Int,
    isDark: Boolean = false
) {
    Card(
        modifier = Modifier
            .fillMaxSize()
            .testTag("stats_card_$pageIndex"),
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isDark) Color(0xFF16151F) else Color.White
        ),
        border = if (isDark) androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF262433)) else null,
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = statNumber,
                fontSize = 34.sp,
                fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.Normal,
                color = statColor,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = description,
                fontSize = 14.5.sp,
                fontWeight = FontWeight.Normal,
                color = if (isDark) Color(0xFFCBD5E1) else Color(0xFF4A5568),
                textAlign = TextAlign.Center,
                lineHeight = 20.sp
            )
        }
    }
}

/**
 * Daily History Card matching the reference screenshots
 */
@Composable
private fun HistoryCardItem(
    item: HistoryItem,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    onRetry: () -> Unit,
    showFullBreakdown: Boolean = false,
    isDark: Boolean = false
) {
    var showMenu by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .testTag("history_card_${item.id}"),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isDark) Color(0xFF16151F) else Color.White
        ),
        border = if (isDark) androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF262433)) else null,
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            // 1. Transcribed Text (Show only final output on "All" tab; show full stages on "Unrated" tab)
            val comparison = item.session?.takeIf {
                (it.pipeline == com.princeniithompson.rapcha.data.SessionPipeline.TWO_STEP ||
                    it.pipeline == com.princeniithompson.rapcha.data.SessionPipeline.THREE_STEP) &&
                    it.stages.isNotEmpty()
            }
            if (!showFullBreakdown || comparison == null) {
                Text(
                    text = item.text,
                    fontSize = 15.sp,
                    lineHeight = 22.sp,
                    fontWeight = FontWeight.Normal,
                    color = if (isDark) Color(0xFFF8FAFC) else Color(0xFF1A202C)
                )
            } else {
                LyraHistoryStages(comparison, isDark = isDark)
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 2. Timestamp and App Indicator (e.g. 4:25 PM · Google AI Studio)
            val displayAppContext = if (!showFullBreakdown) {
                item.appContext.removeSuffix(" · Three-step").removeSuffix(" · Two-step")
            } else {
                item.appContext
            }
            Text(
                text = "${item.getFormattedTime()} · $displayAppContext",
                fontSize = 12.sp,
                color = if (isDark) Color(0xFF94A3B8) else Color(0xFF718096),
                fontWeight = FontWeight.Medium
            )

            Spacer(modifier = Modifier.height(12.dp))

            // 3. Action Buttons Row: Copy | Share | ...
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val btnBg = if (isDark) Color(0xFF232230) else Color(0xFFF1F5F9)
                val btnInk = if (isDark) Color(0xFFE2E8F0) else Color(0xFF334155)

                // Copy Button
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(btnBg)
                        .clickable(onClick = onCopy)
                        .padding(horizontal = 14.dp, vertical = 7.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Filled.ContentCopy,
                            contentDescription = "Copy",
                            tint = btnInk,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Copy",
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.Medium,
                            color = btnInk
                        )
                    }
                }

                // Share Button
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(btnBg)
                        .clickable(onClick = onShare)
                        .padding(horizontal = 14.dp, vertical = 7.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Filled.Share,
                            contentDescription = "Share",
                            tint = btnInk,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Share",
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.Medium,
                            color = btnInk
                        )
                    }
                }

                Spacer(modifier = Modifier.weight(1f))

                // Three-dots overflow button
                Box {
                    IconButton(
                        onClick = { showMenu = true },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.MoreVert,
                            contentDescription = "More options",
                            tint = Color(0xFF94A3B8),
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Copy text") },
                            onClick = {
                                showMenu = false
                                onCopy()
                            },
                            leadingIcon = {
                                Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                            }
                        )
                        val retryReady = HistoryOverflowMenu.retryEnabled(
                            item.retryClipId,
                            RetryClipStore.clipFile(item.retryClipId) != null
                        )
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text("Retry")
                                    if (!retryReady) {
                                        Text(
                                            text = HistoryOverflowMenu.NO_AUDIO,
                                            fontSize = 11.sp,
                                            color = Color(0xFF94A3B8)
                                        )
                                    }
                                }
                            },
                            enabled = retryReady,
                            onClick = {
                                showMenu = false
                                onRetry()
                            },
                            leadingIcon = {
                                Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                            onClick = {
                                showMenu = false
                                onDelete()
                            },
                            leadingIcon = {
                                Icon(Icons.Filled.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                            }
                        )
                    }
                }
            }
        }
    }
}
