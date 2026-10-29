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
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animate
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import com.princeniithompson.rapcha.service.FloatingBubbleManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import com.princeniithompson.rapcha.data.AudioRecording
import com.princeniithompson.rapcha.data.ConnectionState
import com.princeniithompson.rapcha.data.DiagnosticLogEntry
import com.princeniithompson.rapcha.data.DiagnosticNote
import com.princeniithompson.rapcha.data.LiveStats
import com.princeniithompson.rapcha.data.LogEntry
import com.princeniithompson.rapcha.ui.components.diagnostics.DiagnosticsActionMenu
import com.princeniithompson.rapcha.ui.components.diagnostics.DiagnosticsAudioSection
import com.princeniithompson.rapcha.ui.components.diagnostics.DiagnosticsClearConfirmDialog
import com.princeniithompson.rapcha.ui.components.diagnostics.DiagnosticsDragHandle
import com.princeniithompson.rapcha.ui.components.diagnostics.DiagnosticsEventTracker
import com.princeniithompson.rapcha.ui.components.diagnostics.DiagnosticsExportUtils
import com.princeniithompson.rapcha.ui.components.diagnostics.DiagnosticsHistorySection
import com.princeniithompson.rapcha.ui.components.diagnostics.DiagnosticsLabeledIconButton
import com.princeniithompson.rapcha.ui.components.diagnostics.DiagnosticsLiveSection
import com.princeniithompson.rapcha.ui.components.diagnostics.DiagnosticsMenuAction
import com.princeniithompson.rapcha.ui.components.diagnostics.DiagnosticsNotesSection
import com.princeniithompson.rapcha.ui.components.diagnostics.DiagnosticsSectionRow
import com.princeniithompson.rapcha.ui.components.diagnostics.DiagnosticsSquareIconButton
import com.princeniithompson.rapcha.ui.components.diagnostics.DiagnosticsToolbarSection
import com.princeniithompson.rapcha.ui.components.diagnostics.ProvideDiagnosticsColors
import com.princeniithompson.rapcha.ui.components.diagnostics.diagnosticsColors
import com.princeniithompson.rapcha.ui.components.diagnostics.diagnosticsCountText
import com.princeniithompson.rapcha.ui.components.diagnostics.diagnosticsNestedScrollGuard
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
    BackHandler(enabled = currentSubScreen == DiagnosticSubScreen.HUB) {
        onDismiss()
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

    ProvideDiagnosticsColors {
        val colors = diagnosticsColors()
        val bubbleOnRight by FloatingBubbleManager.bubbleAnchoredRight.collectAsState()
        val bubbleGutter = 72.dp
        var dragOffsetPx by remember { mutableFloatStateOf(0f) }
        val dismissThreshold = with(LocalDensity.current) { 120.dp.toPx() }
        val dragScope = rememberCoroutineScope()
        val enterOffset = remember { Animatable(1600f) }
        LaunchedEffect(sheetState) {
            enterOffset.animateTo(0f, tween(280, easing = FastOutSlowInEasing))
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.32f))
        ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .offset { IntOffset(0, (enterOffset.value + dragOffsetPx).roundToInt()) }
                .background(colors.canvas)
                .statusBarsPadding()
                .navigationBarsPadding()
                .diagnosticsNestedScrollGuard()
                .testTag("diagnostics_bottom_sheet")
                .padding(
                    start = if (bubbleOnRight) 16.dp else 16.dp + bubbleGutter,
                    end = if (bubbleOnRight) 16.dp + bubbleGutter else 16.dp
                )
        ) {
            DiagnosticsDragHandle(
                onDragDelta = { delta ->
                    dragOffsetPx = (dragOffsetPx + delta).coerceAtLeast(0f)
                },
                onDragStopped = { velocity ->
                    val shouldDismiss = dragOffsetPx > dismissThreshold || velocity > 1500f
                    if (shouldDismiss) {
                        onDismiss()
                    } else {
                        val from = dragOffsetPx
                        dragScope.launch {
                            animate(initialValue = from, targetValue = 0f) { value, _ ->
                                dragOffsetPx = value
                            }
                        }
                    }
                }
            )
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
                            subtitle = "Typing speed and words",
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
                            subtitle = "Saved events by day",
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
                            subtitle = "Play saved voice files",
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
                            subtitle = "Notes you write here",
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
    val colors = diagnosticsColors()
    val voiceValue = when (connectionState) {
        is ConnectionState.Error -> "Error"
        is ConnectionState.Connecting,
        is ConnectionState.ConnectedWaitingSetup,
        is ConnectionState.Stopping -> "Wait"
        is ConnectionState.Streaming,
        is ConnectionState.Idle -> "OK"
    }
    val voiceColor = when (connectionState) {
        is ConnectionState.Error -> colors.danger
        is ConnectionState.Connecting,
        is ConnectionState.ConnectedWaitingSetup,
        is ConnectionState.Stopping -> colors.warning
        else -> colors.ok
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .diagnosticsNestedScrollGuard()
            .testTag("diagnostics_hub_container"),
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            DiagnosticsToolbarSection(
                connectionState = connectionState,
                onExportTxt = onExportTxt,
                onRequestClear = onRequestClear,
                onDismiss = onDismiss
            )
        }

        item {
            DiagnosticsSectionRow(
                title = "App detection",
                detail = "Package, window, and class",
                value = diagnosticsCountText(appDetectionCount),
                icon = Icons.Outlined.GridView,
                valueColor = colors.secondary,
                onClick = { onNavigateTo(DiagnosticSubScreen.APP_DETECTION) },
                testTag = "nav_card_app_detection"
            )
        }
        item {
            DiagnosticsSectionRow(
                title = "Voice activity",
                detail = "Typing speed and words",
                value = voiceValue,
                icon = Icons.Outlined.GraphicEq,
                valueColor = voiceColor,
                onClick = { onNavigateTo(DiagnosticSubScreen.LIVE) },
                testTag = "nav_card_live"
            )
        }
        item {
            DiagnosticsSectionRow(
                title = "Event history",
                detail = "Saved events by day",
                value = diagnosticsCountText(eventCount),
                icon = Icons.Outlined.History,
                valueColor = colors.secondary,
                onClick = { onNavigateTo(DiagnosticSubScreen.HISTORY) },
                testTag = "nav_card_history"
            )
        }
        item {
            DiagnosticsSectionRow(
                title = "Audio recordings",
                detail = "Play saved voice files",
                value = diagnosticsCountText(recordingCount),
                icon = Icons.Outlined.Mic,
                valueColor = colors.secondary,
                onClick = { onNavigateTo(DiagnosticSubScreen.AUDIO) },
                testTag = "nav_card_audio"
            )
        }
        item {
            DiagnosticsSectionRow(
                title = "Field notes",
                detail = "Notes you write here",
                value = diagnosticsCountText(noteCount),
                icon = Icons.Outlined.EditNote,
                valueColor = colors.secondary,
                onClick = { onNavigateTo(DiagnosticSubScreen.NOTES) },
                testTag = "nav_card_notes"
            )
        }
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
    val colors = diagnosticsColors()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .diagnosticsNestedScrollGuard()
    ) {
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
                    color = colors.ink
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.secondary
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
