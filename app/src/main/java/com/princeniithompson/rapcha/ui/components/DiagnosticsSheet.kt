package com.princeniithompson.rapcha.ui.components

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.princeniithompson.rapcha.data.AudioRecording
import com.princeniithompson.rapcha.data.ConnectionState
import com.princeniithompson.rapcha.data.DiagnosticLogEntry
import com.princeniithompson.rapcha.data.DiagnosticNote
import com.princeniithompson.rapcha.data.LiveStats
import com.princeniithompson.rapcha.data.LogEntry
import com.princeniithompson.rapcha.ui.components.diagnostics.DiagnosticsActionMenu
import com.princeniithompson.rapcha.ui.components.diagnostics.DiagnosticsAudioSection
import com.princeniithompson.rapcha.ui.components.diagnostics.DiagnosticsClearConfirmDialog
import com.princeniithompson.rapcha.ui.components.diagnostics.DiagnosticsCountBadge
import com.princeniithompson.rapcha.ui.components.diagnostics.DiagnosticsEventTracker
import com.princeniithompson.rapcha.ui.components.diagnostics.DiagnosticsExportUtils
import com.princeniithompson.rapcha.ui.components.diagnostics.DiagnosticsHistorySection
import com.princeniithompson.rapcha.ui.components.diagnostics.DiagnosticsLabeledIconButton
import com.princeniithompson.rapcha.ui.components.diagnostics.DiagnosticsLiveSection
import com.princeniithompson.rapcha.ui.components.diagnostics.DiagnosticsMenuAction
import com.princeniithompson.rapcha.ui.components.diagnostics.DiagnosticsNotesSection
import com.princeniithompson.rapcha.ui.components.diagnostics.DiagnosticsPalette
import com.princeniithompson.rapcha.ui.components.diagnostics.DiagnosticsSquareIconButton
import com.princeniithompson.rapcha.ui.components.diagnostics.DiagnosticsToolbarSection
import com.princeniithompson.rapcha.ui.components.diagnostics.premiumSurface
import com.princeniithompson.rapcha.ui.components.diagnostics.standardDiagnosticsMenu
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
 * Full diagnostics panel. The hub and each section keep the same data actions
 * (copy, export, clear, back) on the premium slate-and-card layout.
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
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var currentSubScreen by remember { mutableStateOf(DiagnosticSubScreen.HUB) }
    var showClearConfirmationDialog by remember { mutableStateOf(false) }
    var requestAudioClear by remember { mutableStateOf(false) }

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

    val exportTxt = {
        saveDocumentLauncher.launch(DiagnosticsExportUtils.generateExportFileName())
    }
    val requestClear = { showClearConfirmationDialog = true }
    val menuActions = standardDiagnosticsMenu(
        context = context,
        onExportTxt = exportTxt,
        onRequestClear = requestClear
    )

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
        containerColor = DiagnosticsPalette.slate,
        dragHandle = { BottomSheetDefaults.DragHandle(color = Color(0xFFC5CDD8)) },
        modifier = Modifier.testTag("diagnostics_bottom_sheet")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.95f)
                .background(DiagnosticsPalette.slate)
                .padding(horizontal = 16.dp)
        ) {
            AnimatedContent(
                targetState = currentSubScreen,
                transitionSpec = {
                    if (targetState != DiagnosticSubScreen.HUB) {
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
                        val appDetectionEvents by com.princeniithompson.rapcha.data.AppDetectionLogRepository.events.collectAsState()
                        DiagnosticsHubView(
                            eventCount = diagnosticEntries.size,
                            noteCount = notes.size,
                            recordingCount = recordings.size,
                            appDetectionCount = appDetectionEvents.size,
                            connectionState = connectionState,
                            onExportTxt = exportTxt,
                            onRequestClear = requestClear,
                            onDismiss = onDismiss,
                            onNavigateTo = { destination -> currentSubScreen = destination }
                        )
                    }
                    DiagnosticSubScreen.APP_DETECTION -> {
                        com.princeniithompson.rapcha.ui.screens.AppDetectionDiagnosticsScreen(
                            onBackClick = { currentSubScreen = DiagnosticSubScreen.HUB },
                            onDismiss = onDismiss,
                            onExportTxt = exportTxt,
                            onRequestClearDiagnostics = requestClear
                        )
                    }
                    DiagnosticSubScreen.LIVE -> {
                        SubScreenScaffold(
                            title = "Voice Activity & Speed",
                            subtitle = "Live typing speed, word count & voice notes",
                            onBackClick = { currentSubScreen = DiagnosticSubScreen.HUB },
                            onDismiss = onDismiss,
                            menuActions = menuActions
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
                            subtitle = "Multi-day persistent event log & AI",
                            onBackClick = { currentSubScreen = DiagnosticSubScreen.HUB },
                            onDismiss = onDismiss,
                            menuActions = menuActions
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
                        val audioActions = menuActions + DiagnosticsMenuAction(
                            label = "Clear saved audio",
                            onClick = { requestAudioClear = true },
                            destructive = true,
                            testTag = "menu_clear_audio",
                            icon = Icons.Filled.DeleteSweep
                        )
                        SubScreenScaffold(
                            title = "Audio Recordings",
                            subtitle = "Saved WAV files & player",
                            onBackClick = { currentSubScreen = DiagnosticSubScreen.HUB },
                            onDismiss = onDismiss,
                            menuActions = audioActions
                        ) {
                            DiagnosticsAudioSection(
                                recordings = recordings,
                                isDictating = isDictating,
                                onDeleteRecording = onDeleteRecording,
                                onClearAllRecordings = onClearAllRecordings,
                                requestClearAll = requestAudioClear,
                                onClearAllRequestHandled = { requestAudioClear = false }
                            )
                        }
                    }
                    DiagnosticSubScreen.NOTES -> {
                        SubScreenScaffold(
                            title = "Field Notes",
                            subtitle = "Manual user observations and logs",
                            onBackClick = { currentSubScreen = DiagnosticSubScreen.HUB },
                            onDismiss = onDismiss,
                            menuActions = menuActions
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

@Composable
private fun DiagnosticsHubView(
    eventCount: Int,
    noteCount: Int,
    recordingCount: Int,
    appDetectionCount: Int,
    connectionState: ConnectionState,
    onExportTxt: () -> Unit,
    onRequestClear: () -> Unit,
    onDismiss: () -> Unit,
    onNavigateTo: (DiagnosticSubScreen) -> Unit
) {
    val liveBadge = when (connectionState) {
        is ConnectionState.Idle -> "READY"
        is ConnectionState.Connecting -> "CONNECTING"
        is ConnectionState.Streaming -> "STREAMING"
        is ConnectionState.ConnectedWaitingSetup -> "WAITING"
        is ConnectionState.Error -> "ERROR"
        is ConnectionState.Stopping -> "STOPPING"
    }
    val liveBadgeBg = when (connectionState) {
        is ConnectionState.Error -> DiagnosticsPalette.redSoft
        is ConnectionState.Connecting,
        is ConnectionState.ConnectedWaitingSetup,
        is ConnectionState.Stopping -> DiagnosticsPalette.amberSoft
        else -> DiagnosticsPalette.greenSoft
    }
    val liveBadgeFg = when (connectionState) {
        is ConnectionState.Error -> DiagnosticsPalette.red
        is ConnectionState.Connecting,
        is ConnectionState.ConnectedWaitingSetup,
        is ConnectionState.Stopping -> DiagnosticsPalette.amber
        else -> DiagnosticsPalette.green
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("diagnostics_hub_container")
    ) {
        item {
            DiagnosticsToolbarSection(
                eventCount = eventCount,
                noteCount = noteCount,
                connectionState = connectionState,
                onExportTxt = onExportTxt,
                onRequestClear = onRequestClear,
                onDismiss = onDismiss
            )
        }

        item {
            Spacer(modifier = Modifier.height(22.dp))
            Text(
                text = "Explore",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = DiagnosticsPalette.ink,
                modifier = Modifier.padding(bottom = 12.dp)
            )
        }

        item {
            DiagnosticSectionNavCard(
                title = "App Detection",
                subtitle = "Raw metadata extracted during app scans",
                badgeText = "$appDetectionCount events",
                badgeBg = DiagnosticsPalette.blueSoft,
                badgeFg = DiagnosticsPalette.blue,
                icon = Icons.Filled.Star,
                iconBg = DiagnosticsPalette.blueIcon,
                onClick = { onNavigateTo(DiagnosticSubScreen.APP_DETECTION) },
                testTag = "nav_card_app_detection"
            )
            Spacer(modifier = Modifier.height(12.dp))
        }

        item {
            DiagnosticSectionNavCard(
                title = "Voice Activity & Speed",
                subtitle = "Live typing speed, word count & voice notes",
                badgeText = liveBadge,
                badgeBg = liveBadgeBg,
                badgeFg = liveBadgeFg,
                icon = Icons.Filled.AccountBalanceWallet,
                iconBg = DiagnosticsPalette.voiceIcon,
                onClick = { onNavigateTo(DiagnosticSubScreen.LIVE) },
                testTag = "nav_card_live"
            )
            Spacer(modifier = Modifier.height(12.dp))
        }

        item {
            DiagnosticSectionNavCard(
                title = "Event History",
                subtitle = "Multi-day persistent event log & AI",
                badgeText = "$eventCount events",
                badgeBg = DiagnosticsPalette.purpleSoft,
                badgeFg = DiagnosticsPalette.purple,
                icon = Icons.Filled.Schedule,
                iconBg = DiagnosticsPalette.purpleIcon,
                onClick = { onNavigateTo(DiagnosticSubScreen.HISTORY) },
                testTag = "nav_card_history"
            )
            Spacer(modifier = Modifier.height(12.dp))
        }

        item {
            DiagnosticSectionNavCard(
                title = "Audio Recordings",
                subtitle = "Voice memos and system audio logs",
                badgeText = "$recordingCount files",
                badgeBg = DiagnosticsPalette.redSoft,
                badgeFg = DiagnosticsPalette.audioIcon,
                icon = Icons.Filled.Mic,
                iconBg = DiagnosticsPalette.audioIcon,
                onClick = { onNavigateTo(DiagnosticSubScreen.AUDIO) },
                testTag = "nav_card_audio"
            )
            Spacer(modifier = Modifier.height(12.dp))
        }

        item {
            DiagnosticSectionNavCard(
                title = "Field Notes",
                subtitle = "Manual user observations and logs",
                badgeText = "$noteCount notes",
                badgeBg = DiagnosticsPalette.amberSoft,
                badgeFg = DiagnosticsPalette.amberIcon,
                icon = Icons.Filled.Edit,
                iconBg = DiagnosticsPalette.amberIcon,
                onClick = { onNavigateTo(DiagnosticSubScreen.NOTES) },
                testTag = "nav_card_notes"
            )
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .premiumSurface()
            .clickable(onClick = onClick)
            .testTag(testTag)
            .padding(horizontal = 14.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(iconBg),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(24.dp)
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
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = DiagnosticsPalette.ink,
                    modifier = Modifier.weight(1f, fill = false),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                DiagnosticsCountBadge(
                    text = badgeText,
                    container = badgeBg,
                    content = badgeFg
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = DiagnosticsPalette.muted
            )
        }

        Spacer(modifier = Modifier.width(6.dp))

        Icon(
            imageVector = Icons.Filled.ChevronRight,
            contentDescription = "Open $title",
            tint = Color(0xFF94A3B8),
            modifier = Modifier.size(22.dp)
        )
    }
}

@Composable
private fun SubScreenScaffold(
    title: String,
    subtitle: String,
    onBackClick: () -> Unit,
    onDismiss: () -> Unit,
    menuActions: List<DiagnosticsMenuAction>,
    content: @Composable () -> Unit
) {
    var showOptions by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            DiagnosticsSquareIconButton(
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back to Diagnostics",
                onClick = onBackClick,
                testTag = "subscreen_back_button"
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = DiagnosticsPalette.ink,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = DiagnosticsPalette.muted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Box {
                DiagnosticsLabeledIconButton(
                    icon = Icons.Filled.MoreVert,
                    label = "Options",
                    onClick = { showOptions = true },
                    testTag = "subscreen_options_button"
                )
                DiagnosticsActionMenu(
                    expanded = showOptions,
                    onDismissRequest = { showOptions = false },
                    actions = menuActions
                )
            }

            DiagnosticsLabeledIconButton(
                icon = Icons.Filled.Close,
                label = "Close",
                onClick = onDismiss,
                testTag = "subscreen_close_button"
            )
        }

        Box(modifier = Modifier.weight(1f)) {
            content()
        }
    }
}
