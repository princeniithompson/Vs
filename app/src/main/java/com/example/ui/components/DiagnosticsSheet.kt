package com.example.ui.components

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.automirrored.filled.Input
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.AudioRecording
import com.example.data.ConnectionState
import com.example.data.DiagnosticLogEntry
import com.example.data.DiagnosticNote
import com.example.data.LiveStats
import com.example.data.LogEntry
import com.example.ui.components.diagnostics.DiagnosticsAudioSection
import com.example.ui.components.diagnostics.DiagnosticsClearConfirmDialog
import com.example.ui.components.diagnostics.DiagnosticsEventTracker
import com.example.ui.components.diagnostics.DiagnosticsExportUtils
import com.example.ui.components.diagnostics.DiagnosticsHistorySection
import com.example.ui.components.diagnostics.DiagnosticsLiveSection
import com.example.ui.components.diagnostics.DiagnosticsNotesSection
import com.example.ui.components.diagnostics.DiagnosticsToolbarSection
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Diagnostic destinations matching the bottom-to-top full page presentation.
 */
enum class DiagnosticSubScreen {
    HUB,
    LIVE,
    HISTORY,
    AUDIO,
    NOTES,
    APP_DETECTION
}

/**
 * Full diagnostics panel matching the home page's clean white aesthetic.
 * Sections are selected via spacious cards on the hub screen and slide up smoothly
 * from the bottom into their own dedicated full-height pages with a top Back arrow.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsBottomSheet(
    sheetState: SheetState,
    stats: LiveStats,
    connectionState: ConnectionState,
    liveLogs: List<LogEntry>,
    diagnosticEntries: List<DiagnosticLogEntry>,
    notes: List<DiagnosticNote>,
    recordings: List<AudioRecording> = emptyList(),
    isDictating: Boolean = false,
    selectedModel: String,
    apiKeyConfigured: Boolean,
    customApiKey: String,
    isSmartMode: Boolean = false,
    onCustomApiKeyChange: (String) -> Unit,
    onAddNote: (String) -> Unit,
    onClearAllDiagnostics: () -> Unit,
    onDeleteRecording: (AudioRecording) -> Unit = {},
    onClearAllRecordings: () -> Unit = {},
    isAnalyzingSmartVocab: Boolean = false,
    onSimulateWeeklySmartVocab: () -> Unit = {},
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var currentSubScreen by remember { mutableStateOf(DiagnosticSubScreen.HUB) }
    var showClearConfirmationDialog by remember { mutableStateOf(false) }

    // Intercept hardware/gesture back to navigate back to Hub first
    BackHandler(enabled = currentSubScreen != DiagnosticSubScreen.HUB) {
        currentSubScreen = DiagnosticSubScreen.HUB
    }

    val dayHeaderFormat = remember { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()) }
    val displayDayFormat = remember { SimpleDateFormat("EEEE, MMMM dd, yyyy", Locale.getDefault()) }
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    val noteDateFormat = remember { SimpleDateFormat("MMM dd, yyyy · HH:mm:ss", Locale.getDefault()) }

    val groupedEntries = remember(diagnosticEntries) {
        DiagnosticsEventTracker.buildLogHistory(diagnosticEntries, dayHeaderFormat)
    }

    val expandedDays = remember { mutableStateMapOf<String, Boolean>() }
    val latestDay = groupedEntries.keys.firstOrNull()
    if (latestDay != null && !expandedDays.containsKey(latestDay)) {
        expandedDays[latestDay] = true
    }

    val saveDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        uri?.let { targetUri ->
            DiagnosticsExportUtils.writeExportToUri(context, targetUri)
        }
    }

    if (showClearConfirmationDialog) {
        DiagnosticsClearConfirmDialog(
            onDismissRequest = { showClearConfirmationDialog = false },
            onConfirm = {
                showClearConfirmationDialog = false
                onClearAllDiagnostics()
                Toast.makeText(context, "All diagnostics and notes cleared.", Toast.LENGTH_SHORT).show()
            }
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color.White,
        modifier = Modifier.testTag("diagnostics_bottom_sheet")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.95f)
                .padding(horizontal = 20.dp)
        ) {
            AnimatedContent(
                targetState = currentSubScreen,
                transitionSpec = {
                    if (targetState != DiagnosticSubScreen.HUB) {
                        // Slide up from bottom into dedicated sub-screen
                        (slideInVertically(
                            initialOffsetY = { it },
                            animationSpec = tween(320, easing = FastOutSlowInEasing)
                        ) + fadeIn()).togetherWith(
                            slideOutVertically(
                                targetOffsetY = { -it / 4 },
                                animationSpec = tween(280, easing = FastOutSlowInEasing)
                            ) + fadeOut()
                        )
                    } else {
                        // Slide back down to hub
                        (slideInVertically(
                            initialOffsetY = { -it / 4 },
                            animationSpec = tween(320, easing = FastOutSlowInEasing)
                        ) + fadeIn()).togetherWith(
                            slideOutVertically(
                                targetOffsetY = { it },
                                animationSpec = tween(280, easing = FastOutSlowInEasing)
                            ) + fadeOut()
                        )
                    }
                },
                label = "DiagnosticScreenTransition",
                modifier = Modifier.fillMaxSize()
            ) { subScreen ->
                when (subScreen) {
                    DiagnosticSubScreen.HUB -> {
                        val appDetectionEvents by com.example.data.AppDetectionLogRepository.events.collectAsState()
                        DiagnosticsHubView(
                            eventCount = diagnosticEntries.size,
                            noteCount = notes.size,
                            recordingCount = recordings.size,
                            appDetectionCount = appDetectionEvents.size,
                            connectionState = connectionState,
                            isAnalyzingSmartVocab = isAnalyzingSmartVocab,
                            onSimulateWeeklySmartVocab = onSimulateWeeklySmartVocab,
                            onExportTxt = {
                                saveDocumentLauncher.launch(DiagnosticsExportUtils.generateExportFileName())
                            },
                            onRequestClear = { showClearConfirmationDialog = true },
                            onDismiss = onDismiss,
                            onNavigateTo = { destination -> currentSubScreen = destination }
                        )
                    }
                    DiagnosticSubScreen.APP_DETECTION -> {
                        com.example.ui.screens.AppDetectionDiagnosticsScreen(
                            onBackClick = { currentSubScreen = DiagnosticSubScreen.HUB }
                        )
                    }
                    DiagnosticSubScreen.LIVE -> {
                        SubScreenScaffold(
                            title = "Voice Activity & Speed",
                            subtitle = "Live typing speed, word count & activity story",
                            onBackClick = { currentSubScreen = DiagnosticSubScreen.HUB },
                            onDismiss = onDismiss
                        ) {
                            DiagnosticsLiveSection(
                                stats = stats,
                                connectionState = connectionState,
                                selectedModel = selectedModel,
                                isSmartMode = isSmartMode,
                                apiKeyConfigured = apiKeyConfigured,
                                customApiKey = customApiKey,
                                liveLogs = liveLogs,
                                onCustomApiKeyChange = onCustomApiKeyChange
                            )
                        }
                    }
                    DiagnosticSubScreen.HISTORY -> {
                        SubScreenScaffold(
                            title = "Event History",
                            subtitle = "${diagnosticEntries.size} multi-day persistent events",
                            onBackClick = { currentSubScreen = DiagnosticSubScreen.HUB },
                            onDismiss = onDismiss
                        ) {
                            DiagnosticsHistorySection(
                                groupedEntries = groupedEntries,
                                expandedDays = expandedDays,
                                displayDayFormat = displayDayFormat,
                                timeFormat = timeFormat
                            )
                        }
                    }
                    DiagnosticSubScreen.AUDIO -> {
                        SubScreenScaffold(
                            title = "Audio Recordings",
                            subtitle = "${recordings.size} local WAV audio files",
                            onBackClick = { currentSubScreen = DiagnosticSubScreen.HUB },
                            onDismiss = onDismiss
                        ) {
                            DiagnosticsAudioSection(
                                recordings = recordings,
                                isDictating = isDictating,
                                onDeleteRecording = onDeleteRecording,
                                onClearAllRecordings = onClearAllRecordings
                            )
                        }
                    }
                    DiagnosticSubScreen.NOTES -> {
                        SubScreenScaffold(
                            title = "Field Notes",
                            subtitle = "${notes.size} chronological testing observations",
                            onBackClick = { currentSubScreen = DiagnosticSubScreen.HUB },
                            onDismiss = onDismiss
                        ) {
                            DiagnosticsNotesSection(
                                notes = notes,
                                noteDateFormat = noteDateFormat,
                                onAddNote = onAddNote
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Diagnostics Hub Screen displaying overview tools and tappable cards to open dedicated sub-screens.
 */
@Composable
private fun DiagnosticsHubView(
    eventCount: Int,
    noteCount: Int,
    recordingCount: Int,
    appDetectionCount: Int,
    connectionState: ConnectionState,
    isAnalyzingSmartVocab: Boolean,
    onSimulateWeeklySmartVocab: () -> Unit,
    onExportTxt: () -> Unit,
    onRequestClear: () -> Unit,
    onDismiss: () -> Unit,
    onNavigateTo: (DiagnosticSubScreen) -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("diagnostics_hub_container")
    ) {
        item {
            DiagnosticsToolbarSection(
                eventCount = eventCount,
                noteCount = noteCount,
                isAnalyzingSmartVocab = isAnalyzingSmartVocab,
                onSimulateWeeklySmartVocab = onSimulateWeeklySmartVocab,
                onExportTxt = onExportTxt,
                onRequestClear = onRequestClear,
                onDismiss = onDismiss
            )
        }

        item {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Diagnostics Sections",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF0F172A),
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }

        // 1. App Detection Diagnostics Card (Prominent Card at Top)
        item {
            DiagnosticSectionNavCard(
                title = "App Detection Diagnostics",
                subtitle = "Raw metadata extracted during bubble activation (packageName, title, nodes, AI prompt/response)",
                badgeText = "$appDetectionCount events",
                badgeBg = Color(0xFFE0F2FE),
                badgeFg = Color(0xFF0284C7),
                icon = Icons.Filled.AutoAwesome,
                iconBg = Color(0xFF0284C7),
                onClick = { onNavigateTo(DiagnosticSubScreen.APP_DETECTION) },
                testTag = "nav_card_app_detection"
            )
            Spacer(modifier = Modifier.height(10.dp))
        }

        // 2. Live Protocol Monitor Card
        item {
            val liveStateLabel = when (connectionState) {
                is ConnectionState.Idle -> "IDLE"
                is ConnectionState.Connecting -> "CONNECTING"
                is ConnectionState.Streaming -> "STREAMING"
                is ConnectionState.ConnectedWaitingSetup -> "WAITING"
                is ConnectionState.Error -> "ERROR"
                is ConnectionState.Stopping -> "STOPPING"
            }
            val liveStateColor = if (connectionState is ConnectionState.Error) Color(0xFFDC2626) else Color(0xFF006874)

            DiagnosticSectionNavCard(
                title = "Voice Activity & Speed",
                subtitle = "Live typing speed, word count & friendly activity story",
                badgeText = liveStateLabel,
                badgeBg = liveStateColor.copy(alpha = 0.12f),
                badgeFg = liveStateColor,
                icon = Icons.Filled.Radio,
                iconBg = Color(0xFF006874),
                onClick = { onNavigateTo(DiagnosticSubScreen.LIVE) },
                testTag = "nav_card_live"
            )
            Spacer(modifier = Modifier.height(10.dp))
        }

        // 2. Event History Card
        item {
            DiagnosticSectionNavCard(
                title = "Event History",
                subtitle = "Multi-day persistent event log & AI polish history",
                badgeText = "$eventCount events",
                badgeBg = Color(0xFFF3E8FF),
                badgeFg = Color(0xFF7E22CE),
                icon = Icons.Filled.History,
                iconBg = Color(0xFF6B4EA2),
                onClick = { onNavigateTo(DiagnosticSubScreen.HISTORY) },
                testTag = "nav_card_history"
            )
            Spacer(modifier = Modifier.height(10.dp))
        }

        // 3. Audio Recordings Card
        item {
            DiagnosticSectionNavCard(
                title = "Audio Recordings",
                subtitle = "Saved dictation audio WAV files & waveform player",
                badgeText = "$recordingCount files",
                badgeBg = Color(0xFFE0F2FE),
                badgeFg = Color(0xFF0284C7),
                icon = Icons.Filled.GraphicEq,
                iconBg = Color(0xFF0284C7),
                onClick = { onNavigateTo(DiagnosticSubScreen.AUDIO) },
                testTag = "nav_card_audio"
            )
            Spacer(modifier = Modifier.height(10.dp))
        }

        // 4. Field Notes Card
        item {
            DiagnosticSectionNavCard(
                title = "Field Notes",
                subtitle = "Testing observations, issue memos & feedback",
                badgeText = "$noteCount notes",
                badgeBg = Color(0xFFDCFCE7),
                badgeFg = Color(0xFF15803D),
                icon = Icons.Filled.EditNote,
                iconBg = Color(0xFF059669),
                onClick = { onNavigateTo(DiagnosticSubScreen.NOTES) },
                testTag = "nav_card_notes"
            )
            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}

/**
 * Reusable full-width navigation card on the Hub screen.
 */
@Composable
private fun DiagnosticSectionNavCard(
    title: String,
    subtitle: String,
    badgeText: String,
    badgeBg: Color,
    badgeFg: Color,
    icon: ImageVector,
    iconBg: Color,
    onClick: () -> Unit,
    testTag: String
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag(testTag)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Icon in colored circle
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(iconBg),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(22.dp)
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF0F172A)
                    )

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(badgeBg)
                            .padding(horizontal = 7.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = badgeText,
                            color = badgeFg,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(modifier = Modifier.height(2.dp))

                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF64748B),
                    maxLines = 1
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            Icon(
                imageVector = Icons.Filled.ChevronRight,
                contentDescription = "Open $title",
                tint = Color(0xFF94A3B8),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

/**
 * Top App Bar and layout wrapper for sub-screens that slide up from the bottom.
 */
@Composable
private fun SubScreenScaffold(
    title: String,
    subtitle: String,
    onBackClick: () -> Unit,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // Sub-screen Header Bar with Back Arrow
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = onBackClick,
                    modifier = Modifier
                        .size(40.dp)
                        .background(Color(0xFFF1F5F9), RoundedCornerShape(10.dp))
                        .testTag("subscreen_back_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back to Diagnostics",
                        tint = Color(0xFF334155),
                        modifier = Modifier.size(20.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF0F172A)
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF64748B)
                    )
                }
            }

            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .size(40.dp)
                    .background(Color(0xFFF1F5F9), RoundedCornerShape(10.dp))
                    .testTag("subscreen_close_button")
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "Close diagnostics",
                    tint = Color(0xFF334155),
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        Box(modifier = Modifier.weight(1f)) {
            content()
        }
    }
}
