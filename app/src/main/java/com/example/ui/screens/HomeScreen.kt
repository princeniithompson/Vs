package com.example.ui.screens

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
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import kotlin.math.absoluteValue
import com.example.data.HistoryItem
import com.example.data.HistoryRepository
import com.example.ui.VoiceTypingViewModel
import com.example.ui.components.InAppVoiceTypingBubble
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

    // Group history items by date header ("Today", "Yesterday", "Sep 21, 2026", etc.)
    val groupedHistory = remember(historyItems) {
        historyItems.groupBy { it.getFormattedDateHeader() }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFFF6F8FA)) // Calm clean background
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 90.dp)
        ) {
            // 1. Top Header Bar
            item {
                CenterAlignedTopAppBar(
                    title = {
                        Text(
                            text = "|||| VoxStream",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF1E2430)
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onOpenMenu) {
                            Icon(
                                imageVector = Icons.Filled.Menu,
                                contentDescription = "Open Settings Menu",
                                tint = Color(0xFF1E2430)
                            )
                        }
                    },
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                        containerColor = Color.Transparent
                    )
                )
            }

            // 2. Top Carousel: Voice-Typing Bubble (Page 0) + Real Stats Cards (Pages 1, 2, 3)
            item {
                TopCarouselSection(viewModel = viewModel)
                Spacer(modifier = Modifier.height(20.dp))
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
                            color = Color(0xFF718096),
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
                            color = Color(0xFF1A202C),
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
                        )
                    }

                    // Cards in this date group
                    items(itemsInGroup, key = { it.id }) { item ->
                        HistoryCardItem(
                            item = item,
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
                                HistoryRepository.deleteHistoryItem(item.id)
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
private fun TopCarouselSection(viewModel: VoiceTypingViewModel) {
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
            delay(19_000L) // 19 good seconds
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
                            pageIndex = 1
                        )
                    }
                    2 -> {
                        // Page 2: Apps Used (100% Real Data)
                        StatCardView(
                            statNumber = "$uniqueApps ${if (uniqueApps == 1) "app" else "apps"}",
                            statColor = Color(0xFF1E293B), // Dark slate
                            description = if (uniqueApps > 0) {
                                "Number of applications you are flowing in"
                            } else {
                                "Number of applications you are flowing in\nDictate across apps to track usage"
                            },
                            pageIndex = 2
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
                            pageIndex = 3
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
                    targetValue = if (isSelected) Color(0xFF1E293B) else Color(0xFFCBD5E1),
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
    pageIndex: Int
) {
    Card(
        modifier = Modifier
            .fillMaxSize()
            .testTag("stats_card_$pageIndex"),
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color.White
        ),
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
                color = Color(0xFF4A5568),
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
    onDelete: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .testTag("history_card_${item.id}"),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color.White
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            // 1. Transcribed Text
            Text(
                text = item.text,
                fontSize = 15.sp,
                lineHeight = 22.sp,
                fontWeight = FontWeight.Normal,
                color = Color(0xFF1A202C)
            )

            Spacer(modifier = Modifier.height(12.dp))

            // 2. Timestamp and App Indicator (e.g. 4:25 PM · Google AI Studio)
            Text(
                text = "${item.getFormattedTime()} · ${item.appContext}",
                fontSize = 12.sp,
                color = Color(0xFF718096),
                fontWeight = FontWeight.Medium
            )

            Spacer(modifier = Modifier.height(12.dp))

            // 3. Action Buttons Row: Copy | Share | ...
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Copy Button
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xFFF1F5F9))
                        .clickable(onClick = onCopy)
                        .padding(horizontal = 14.dp, vertical = 7.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Filled.ContentCopy,
                            contentDescription = "Copy",
                            tint = Color(0xFF334155),
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Copy",
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFF334155)
                        )
                    }
                }

                // Share Button
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xFFF1F5F9))
                        .clickable(onClick = onShare)
                        .padding(horizontal = 14.dp, vertical = 7.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Filled.Share,
                            contentDescription = "Share",
                            tint = Color(0xFF334155),
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Share",
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFF334155)
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
                        DropdownMenuItem(
                            text = { Text("Share") },
                            onClick = {
                                showMenu = false
                                onShare()
                            },
                            leadingIcon = {
                                Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(16.dp))
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
