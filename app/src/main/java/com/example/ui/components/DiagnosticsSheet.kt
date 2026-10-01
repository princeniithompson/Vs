package com.example.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.media.MediaPlayer
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.SheetState
import androidx.compose.material3.Slider
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.AppLogRepository
import com.example.data.AudioRecording
import com.example.data.AudioRecordingRepository
import com.example.data.ConnectionState
import com.example.data.DiagnosticLogEntry
import com.example.data.DiagnosticNote
import com.example.data.DiagnosticSource
import com.example.data.DiagnosticType
import com.example.data.LiveStats
import com.example.data.LogEntry
import com.example.data.LogLevel
import kotlinx.coroutines.delay
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
    var selectedTabIndex by remember { mutableIntStateOf(0) }
    var showClearConfirmationDialog by remember { mutableStateOf(false) }

    // Multi-Day / History Collapsible Day Sections tracking (defaults open for first/latest day)
    val dayHeaderFormat = remember { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()) }
    val displayDayFormat = remember { SimpleDateFormat("EEEE, MMMM dd, yyyy", Locale.getDefault()) }
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    val noteDateFormat = remember { SimpleDateFormat("MMM dd, yyyy · HH:mm:ss", Locale.getDefault()) }

    // Group diagnostic entries by day
    val groupedEntries = remember(diagnosticEntries) {
        diagnosticEntries.groupBy { dayHeaderFormat.format(Date(it.timestamp)) }
    }

    // Collapsed days state: key is dayStr, value is boolean (true = expanded)
    val expandedDays = remember { mutableStateMapOf<String, Boolean>() }

    // Auto-expand newest day if not explicitly set
    val latestDay = groupedEntries.keys.firstOrNull()
    if (latestDay != null && !expandedDays.containsKey(latestDay)) {
        expandedDays[latestDay] = true
    }

    val saveDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        uri?.let { targetUri ->
            try {
                context.contentResolver.openOutputStream(targetUri)?.use { stream ->
                    stream.write(AppLogRepository.getFullExportFormatted().toByteArray(Charsets.UTF_8))
                }
                Toast.makeText(context, "Diagnostics exported successfully!", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(context, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    // Confirmation Dialog for Clear
    if (showClearConfirmationDialog) {
        AlertDialog(
            onDismissRequest = { showClearConfirmationDialog = false },
            title = {
                Text(
                    text = "Clear all diagnostics?",
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            },
            text = {
                Text(
                    text = "This deletes everything logged so far (including multi-day event history and user notes) and can't be undone.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showClearConfirmationDialog = false
                        onClearAllDiagnostics()
                        Toast.makeText(context, "All diagnostics and notes cleared.", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    ),
                    modifier = Modifier.testTag("confirm_clear_diagnostics_button")
                ) {
                    Text("Clear")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showClearConfirmationDialog = false },
                    modifier = Modifier.testTag("cancel_clear_diagnostics_button")
                ) {
                    Text("Cancel")
                }
            },
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(16.dp)
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        modifier = Modifier.testTag("diagnostics_bottom_sheet")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f)
                .padding(horizontal = 20.dp)
        ) {
            // Header Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Diagnostics & Protocol Monitor",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Unified Multi-Day Persistent Engine",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.testTag("diagnostics_close_button")
                ) {
                    Icon(Icons.Filled.Close, contentDescription = "Close diagnostics")
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Action Toolbar (Copy All, Export .txt, Clear All gated)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        RoundedCornerShape(12.dp)
                    )
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Total Summary Pill
                Text(
                    text = "${diagnosticEntries.size} events · ${notes.size} notes",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(start = 4.dp)
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Copy combined history + notes
                    TextButton(
                        onClick = {
                            val export = AppLogRepository.getFullExportFormatted()
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val clip = ClipData.newPlainText("VoxStream Diagnostics", export)
                            clipboard.setPrimaryClip(clip)
                            Toast.makeText(context, "Copied full history & notes to clipboard!", Toast.LENGTH_SHORT).show()
                        },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.primary),
                        modifier = Modifier.testTag("copy_logs_button")
                    ) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = "Copy diagnostics", modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Copy", fontSize = 12.sp)
                    }

                    // Export .txt
                    TextButton(
                        onClick = {
                            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                            saveDocumentLauncher.launch("voxstream_diagnostics_$timestamp.txt")
                        },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.secondary),
                        modifier = Modifier.testTag("download_logs_button")
                    ) {
                        Icon(Icons.Filled.Download, contentDescription = "Export txt", modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(".txt", fontSize = 12.sp)
                    }

                    // Clear (Gated by confirmation dialog)
                    TextButton(
                        onClick = { showClearConfirmationDialog = true },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.testTag("clear_logs_button")
                    ) {
                        Icon(Icons.Filled.Delete, contentDescription = "Clear diagnostics", modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Clear", fontSize = 12.sp)
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Weekly Smart Vocabulary Simulation Card (Testable Simulate Control)
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("simulate_weekly_vocab_card"),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                ),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Filled.AutoAwesome,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Weekly Smart Vocabulary",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        Text(
                            text = "Analyze last 7 days of real transcripts with context-aware Gemini AI",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = onSimulateWeeklySmartVocab,
                        enabled = !isAnalyzingSmartVocab,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.testTag("simulate_7_days_button")
                    ) {
                        if (isAnalyzingSmartVocab) {
                            Text("Analyzing...", fontSize = 12.sp)
                        } else {
                            Text("Simulate 7 days", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Navigation Tabs: Live, History, Notes, Audio
            val tabTitles = listOf("Live", "History", "Notes", "Audio")
            val tabIcons = listOf(Icons.Filled.Radio, Icons.Filled.History, Icons.Filled.EditNote, Icons.Filled.GraphicEq)

            SecondaryTabRow(
                selectedTabIndex = selectedTabIndex,
                containerColor = Color.Transparent,
                contentColor = MaterialTheme.colorScheme.primary,
                modifier = Modifier.fillMaxWidth()
            ) {
                tabTitles.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTabIndex == index,
                        onClick = { selectedTabIndex = index },
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = tabIcons[index],
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = when (index) {
                                        1 -> if (diagnosticEntries.isNotEmpty()) "$title (${diagnosticEntries.size})" else title
                                        2 -> if (notes.isNotEmpty()) "$title (${notes.size})" else title
                                        3 -> if (recordings.isNotEmpty()) "$title (${recordings.size})" else title
                                        else -> title
                                    },
                                    fontWeight = if (selectedTabIndex == index) FontWeight.Bold else FontWeight.Normal
                                )
                            }
                        },
                        modifier = Modifier.testTag("tab_${title.lowercase()}")
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Tab Content
            when (selectedTabIndex) {
                0 -> LiveTabContent(
                    stats = stats,
                    connectionState = connectionState,
                    selectedModel = selectedModel,
                    isSmartMode = isSmartMode,
                    apiKeyConfigured = apiKeyConfigured,
                    customApiKey = customApiKey,
                    liveLogs = liveLogs,
                    onCustomApiKeyChange = onCustomApiKeyChange
                )
                1 -> HistoryTabContent(
                    groupedEntries = groupedEntries,
                    expandedDays = expandedDays,
                    displayDayFormat = displayDayFormat,
                    timeFormat = timeFormat
                )
                2 -> NotesTabContent(
                    notes = notes,
                    noteDateFormat = noteDateFormat,
                    onAddNote = onAddNote
                )
                3 -> AudioTabContent(
                    recordings = recordings,
                    isDictating = isDictating,
                    onDeleteRecording = onDeleteRecording,
                    onClearAllRecordings = onClearAllRecordings
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

// -------------------------------------------------------------
// SECTION 1: LIVE TAB (Real-time Socket & Protocol Monitor)
// -------------------------------------------------------------
@Composable
private fun LiveTabContent(
    stats: LiveStats,
    connectionState: ConnectionState,
    selectedModel: String,
    isSmartMode: Boolean,
    apiKeyConfigured: Boolean,
    customApiKey: String,
    liveLogs: List<LogEntry>,
    onCustomApiKeyChange: (String) -> Unit
) {
    var showApiKeyInput by remember { mutableStateOf(false) }
    var tempApiKey by remember(customApiKey) { mutableStateOf(customApiKey) }
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()) }

    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight()
            .testTag("live_tab_container")
    ) {
        // Status Metrics Card
        item {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                ),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    // Protocol State & setupComplete
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "setupComplete Status:",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (stats.setupCompleted) {
                                Icon(
                                    imageVector = Icons.Filled.CheckCircle,
                                    contentDescription = "setupComplete Received",
                                    tint = Color(0xFF10B981),
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "CONFIRMED (Step B OK)",
                                    color = Color(0xFF10B981),
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.labelMedium
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Filled.HourglassEmpty,
                                    contentDescription = "Waiting for setupComplete",
                                    tint = Color(0xFFF59E0B),
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "Awaiting Step B",
                                    color = Color(0xFFF59E0B),
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Socket State:",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        val stateLabel = when (connectionState) {
                            is ConnectionState.Idle -> "IDLE"
                            is ConnectionState.Connecting -> "CONNECTING..."
                            is ConnectionState.ConnectedWaitingSetup -> "WAITING SETUP_COMPLETE"
                            is ConnectionState.Streaming -> "STREAMING AUDIO"
                            is ConnectionState.Stopping -> "STOPPING"
                            is ConnectionState.Error -> "ERROR: ${connectionState.message.take(24)}"
                        }
                        Text(
                            text = stateLabel,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = if (connectionState is ConnectionState.Error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Model:",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = selectedModel,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Transcription Mode:",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = if (isSmartMode) "SMART (Cleaned + Punctuated)" else "VERBATIM (Raw Fastest)",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = if (isSmartMode) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Counters Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        MetricItem("Pre-buffered", "${stats.chunksBuffered}")
                        MetricItem("100ms Sent", "${stats.chunksSent}")
                        MetricItem("KB Streamed", "${stats.bytesSent / 1024} KB")
                        MetricItem("Interim/Final", "${stats.interimCount}/${stats.finalizedCount}")
                    }

                    if (stats.lastError != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                                .padding(8.dp)
                        ) {
                            Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Last Error: ${stats.lastError}",
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }
            }
        }

        item { Spacer(modifier = Modifier.height(10.dp)) }

        // API Key Accordion
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showApiKeyInput = !showApiKeyInput }
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.Key,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = if (apiKeyConfigured) Color(0xFF10B981) else Color(0xFFF59E0B)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (apiKeyConfigured) "Gemini API Key: Ready" else "Gemini API Key: Needed",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (apiKeyConfigured) Color(0xFF10B981) else Color(0xFFF59E0B)
                    )
                }
                Icon(
                    imageVector = if (showApiKeyInput) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = null
                )
            }

            AnimatedVisibility(visible = showApiKeyInput) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp, bottom = 12.dp)
                ) {
                    Text(
                        text = "Configured via Secrets panel or enter an override key directly:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = tempApiKey,
                            onValueChange = { tempApiKey = it },
                            placeholder = { Text("AIzaSy...") },
                            singleLine = true,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("api_key_override_input"),
                            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = {
                                onCustomApiKeyChange(tempApiKey)
                                showApiKeyInput = false
                            },
                            modifier = Modifier.testTag("save_api_key_button")
                        ) {
                            Text("Save")
                        }
                    }
                }
            }
        }

        item { Spacer(modifier = Modifier.height(10.dp)) }

        // Live Log Feed Header
        item {
            Text(
                text = "Active Session Socket Frames (${liveLogs.size})",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(bottom = 6.dp)
            )
        }

        // Live Frames list
        if (liveLogs.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                            RoundedCornerShape(10.dp)
                        )
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No active socket frames recorded right now.\nStart recording in-app to watch the live protocol stream.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            items(liveLogs, key = { it.id }) { entry ->
                LogItemView(entry = entry, timeFormat = timeFormat)
                Spacer(modifier = Modifier.height(4.dp))
            }
        }
    }
}

// -------------------------------------------------------------
// SECTION 2: HISTORY TAB (Multi-Day Collapsible Event Log)
// -------------------------------------------------------------
@Composable
private fun HistoryTabContent(
    groupedEntries: Map<String, List<DiagnosticLogEntry>>,
    expandedDays: MutableMap<String, Boolean>,
    displayDayFormat: SimpleDateFormat,
    timeFormat: SimpleDateFormat
) {
    if (groupedEntries.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .padding(32.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = Icons.Filled.History,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "No diagnostic history recorded yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Events from both the app and the floating bubble will accumulate here day-by-day and persist across reboots.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }
        return
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight()
            .testTag("history_tab_container")
    ) {
        groupedEntries.forEach { (dayKey, entriesForDay) ->
            val isExpanded = expandedDays[dayKey] ?: false
            val firstDate = entriesForDay.firstOrNull()?.timestamp ?: System.currentTimeMillis()
            val dayLabel = displayDayFormat.format(Date(firstDate))

            // Day Header Accordion Card
            item(key = "header_$dayKey") {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clickable { expandedDays[dayKey] = !isExpanded }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = dayLabel,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "${entriesForDay.size} events",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Icon(
                                imageVector = if (isExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                                contentDescription = if (isExpanded) "Collapse" else "Expand",
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // Entries for this day (shown when expanded)
            if (isExpanded) {
                items(entriesForDay, key = { it.id }) { entry ->
                    DiagnosticEntryRow(entry = entry, timeFormat = timeFormat)
                    Spacer(modifier = Modifier.height(4.dp))
                }
                item(key = "divider_$dayKey") {
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }
    }
}

@Composable
private fun DiagnosticEntryRow(entry: DiagnosticLogEntry, timeFormat: SimpleDateFormat) {
    val sourceBadgeBg = if (entry.source == DiagnosticSource.BUBBLE) {
        Color(0xFF8B5CF6) // Purple / Bubble
    } else {
        Color(0xFF00E5FF) // Teal / In-App
    }
    val sourceBadgeFg = if (entry.source == DiagnosticSource.BUBBLE) Color.White else Color(0xFF00363D)

    val (typeColor, typeText) = when (entry.type) {
        DiagnosticType.SESSION_START -> Pair(Color(0xFF3B82F6), "START")
        DiagnosticType.SESSION_END -> Pair(Color(0xFF6B7280), "END")
        DiagnosticType.TRANSCRIPT_FINAL -> Pair(Color(0xFF10B981), "TEXT")
        DiagnosticType.POLISH_CALLED -> Pair(Color(0xFFA855F7), "POLISH")
        DiagnosticType.POLISH_SUCCESS -> Pair(Color(0xFF059669), "POLISHED")
        DiagnosticType.POLISH_FAILED -> Pair(Color(0xFFDC2626), "POLISH_ERR")
        DiagnosticType.ERROR -> Pair(Color(0xFFEF4444), "ERROR")
        DiagnosticType.WARNING -> Pair(Color(0xFFF59E0B), "WARN")
        DiagnosticType.NOISY_ENVIRONMENT -> Pair(Color(0xFFF97316), "NOISY")
        DiagnosticType.SESSION_RECONNECT -> Pair(Color(0xFFEAB308), "RECONNECT")
        DiagnosticType.SAFE_MODE_TRIGGERED -> Pair(Color(0xFFF59E0B), "SAFE_MODE")
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.5f))
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Timestamp
        Text(
            text = timeFormat.format(Date(entry.timestamp)),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace
        )

        Spacer(modifier = Modifier.width(8.dp))

        // Source Badge (APP vs BUBBLE)
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(sourceBadgeBg)
                .padding(horizontal = 5.dp, vertical = 2.dp)
        ) {
            Text(
                text = entry.source.name,
                color = sourceBadgeFg,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(modifier = Modifier.width(6.dp))

        // Event Type Badge
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(typeColor.copy(alpha = 0.2f))
                .padding(horizontal = 5.dp, vertical = 2.dp)
        ) {
            Text(
                text = typeText,
                color = typeColor,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        // Event Message
        Text(
            text = entry.message,
            style = MaterialTheme.typography.bodySmall,
            fontSize = 12.sp,
            color = if (entry.type == DiagnosticType.ERROR || entry.type == DiagnosticType.POLISH_FAILED) MaterialTheme.colorScheme.error
                   else MaterialTheme.colorScheme.onSurface,
            fontWeight = if (entry.type == DiagnosticType.ERROR) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.weight(1f)
        )
    }
}

// -------------------------------------------------------------
// SECTION 3: NOTES TAB (User Testing & Bug Field Notes)
// -------------------------------------------------------------
@Composable
private fun NotesTabContent(
    notes: List<DiagnosticNote>,
    noteDateFormat: SimpleDateFormat,
    onAddNote: (String) -> Unit
) {
    var noteInput by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight()
            .testTag("notes_tab_container")
    ) {
        // Input Box for adding note
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            ),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = "Add Diagnostic Field Note",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Jot down anything unusual (e.g. \"latency felt high\", \"cut off at 0:14\"). It auto-timestamps and lines up in exports.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = noteInput,
                    onValueChange = { noteInput = it },
                    placeholder = { Text("Describe what happened...") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("add_note_input"),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface
                    ),
                    maxLines = 3
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    Button(
                        onClick = {
                            if (noteInput.isNotBlank()) {
                                onAddNote(noteInput)
                                noteInput = ""
                            }
                        },
                        enabled = noteInput.isNotBlank(),
                        modifier = Modifier.testTag("submit_add_note_button")
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Add Note")
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        Text(
            text = "Chronological Notes (${notes.size})",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(modifier = Modifier.height(8.dp))

        if (notes.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
                        RoundedCornerShape(10.dp)
                    )
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No notes added yet.\nEnter a note above to record testing observations.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                items(notes, key = { it.id }) { note ->
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                        ),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Filled.EditNote,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = noteDateFormat.format(Date(note.timestamp)),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = note.text,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        }
    }
}

// -------------------------------------------------------------
// HELPER COMPONENTS
// -------------------------------------------------------------
@Composable
private fun MetricItem(label: String, value: String) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 11.sp
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun LogItemView(entry: LogEntry, timeFormat: SimpleDateFormat) {
    var expanded by remember { mutableStateOf(false) }

    val (badgeColor, badgeText) = when (entry.level) {
        LogLevel.INFO -> Pair(Color(0xFF3B82F6), "INFO")
        LogLevel.SENT -> Pair(Color(0xFF8B5CF6), "OUT")
        LogLevel.RECEIVED -> Pair(Color(0xFF10B981), "IN")
        LogLevel.ERROR -> Pair(Color(0xFFEF4444), "ERR")
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f))
            .clickable(enabled = entry.payload != null) { expanded = !expanded }
            .padding(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(badgeColor)
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    text = badgeText,
                    color = Color.White,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = timeFormat.format(Date(entry.timestamp)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 10.sp
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = entry.message,
                style = MaterialTheme.typography.bodySmall,
                fontSize = 11.sp,
                fontWeight = if (entry.level == LogLevel.ERROR) FontWeight.Bold else FontWeight.Normal,
                color = if (entry.level == LogLevel.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            if (entry.payload != null) {
                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (expanded && entry.payload != null) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = entry.payload,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    lineHeight = 13.sp
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
                    .padding(6.dp)
            )
        }
    }
}

// -------------------------------------------------------------
// SECTION 4: AUDIO TAB (Recorded Session Audio WAV Player & Export)
// -------------------------------------------------------------
@Composable
private fun AudioTabContent(
    recordings: List<AudioRecording>,
    isDictating: Boolean,
    onDeleteRecording: (AudioRecording) -> Unit,
    onClearAllRecordings: () -> Unit
) {
    val context = LocalContext.current
    var showClearConfirmation by remember { mutableStateOf(false) }

    // Currently playing recording state: null if none playing
    var playingFile by remember { mutableStateOf<File?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var currentPositionMs by remember { mutableIntStateOf(0) }
    var totalDurationMs by remember { mutableIntStateOf(0) }

    val mediaPlayer = remember { MediaPlayer() }

    // Coroutine ticker for smooth seekbar updates
    LaunchedEffect(playingFile, isPlaying) {
        while (isPlaying && playingFile != null) {
            try {
                if (mediaPlayer.isPlaying) {
                    currentPositionMs = mediaPlayer.currentPosition
                    totalDurationMs = mediaPlayer.duration
                }
            } catch (e: Exception) {
                // ignore
            }
            delay(200)
        }
    }

    // Stop playback if dictation starts
    LaunchedEffect(isDictating) {
        if (isDictating && isPlaying) {
            try {
                mediaPlayer.stop()
                mediaPlayer.reset()
            } catch (e: Exception) {
                // ignore
            }
            isPlaying = false
            playingFile = null
            currentPositionMs = 0
            totalDurationMs = 0
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            try {
                mediaPlayer.release()
            } catch (e: Exception) {
                // ignore
            }
        }
    }

    fun playAudio(file: File) {
        if (isDictating) {
            Toast.makeText(context, "Playback disabled during active dictation", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            if (playingFile == file && isPlaying) {
                // Pause currently playing
                mediaPlayer.pause()
                isPlaying = false
                return
            }

            if (playingFile == file && !isPlaying) {
                // Resume
                mediaPlayer.start()
                isPlaying = true
                return
            }

            // Playing a new file: stop old one
            mediaPlayer.reset()
            mediaPlayer.setDataSource(file.absolutePath)
            mediaPlayer.prepare()
            mediaPlayer.start()

            playingFile = file
            isPlaying = true
            currentPositionMs = 0
            totalDurationMs = mediaPlayer.duration

            mediaPlayer.setOnCompletionListener {
                isPlaying = false
                playingFile = null
                currentPositionMs = 0
            }
        } catch (e: Exception) {
            Log.e("AudioTab", "Error playing recording", e)
            Toast.makeText(context, "Playback error: ${e.message}", Toast.LENGTH_SHORT).show()
            isPlaying = false
            playingFile = null
        }
    }

    fun seekTo(positionMs: Int) {
        try {
            mediaPlayer.seekTo(positionMs)
            currentPositionMs = positionMs
        } catch (e: Exception) {
            Log.e("AudioTab", "Error seeking", e)
        }
    }

    val dateTimeFormat = remember { SimpleDateFormat("MMM dd, yyyy · HH:mm:ss", Locale.getDefault()) }

    Column(modifier = Modifier.fillMaxSize()) {
        // Privacy Note Banner
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
            ),
            shape = RoundedCornerShape(12.dp)
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Filled.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "Privacy Note: Files stay strictly on your phone. Nothing is uploaded or shared unless you explicitly tap Share or Download.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    lineHeight = 16.sp
                )
            }
        }

        // Top Toolbar: Storage count + Clear All button
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            val totalBytes = recordings.sumOf { it.sizeBytes }
            val formattedTotalSize = formatFileSize(totalBytes)
            Text(
                text = "${recordings.size} recordings ($formattedTotalSize)",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (recordings.isNotEmpty()) {
                TextButton(
                    onClick = { showClearConfirmation = true },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Icon(
                        imageVector = Icons.Filled.DeleteSweep,
                        contentDescription = "Clear all recordings",
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Clear All")
                }
            }
        }

        if (recordings.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Filled.GraphicEq,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "No saved audio recordings yet",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Dictations from the app or floating bubble will appear here.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(recordings, key = { it.fileName }) { recording ->
                    val isThisPlaying = playingFile == recording.file && isPlaying

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isThisPlaying)
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.40f)
                            else
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.60f)
                        ),
                        border = if (isThisPlaying) {
                            BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
                        } else null
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            // Row 1: Source badge, Timestamp, Duration & File Size
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    val isBubble = recording.source == DiagnosticSource.BUBBLE
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(
                                                if (isBubble) Color(0xFF8B5CF6).copy(alpha = 0.20f)
                                                else MaterialTheme.colorScheme.primary.copy(alpha = 0.20f)
                                            )
                                            .padding(horizontal = 8.dp, vertical = 3.dp)
                                    ) {
                                        Text(
                                            text = recording.source.name,
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = if (isBubble) Color(0xFF8B5CF6) else MaterialTheme.colorScheme.primary
                                        )
                                    }

                                    Spacer(modifier = Modifier.width(8.dp))

                                    Text(
                                        text = dateTimeFormat.format(Date(recording.timestamp)),
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }

                                Text(
                                    text = "${formatDurationMs(recording.durationMs)} · ${formatFileSize(recording.sizeBytes)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            // Transcript snippet
                            if (!recording.transcriptSnippet.isNullOrBlank()) {
                                Text(
                                    text = "“${recording.transcriptSnippet}”",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontStyle = FontStyle.Italic,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                            }

                            // Mini Player Bar (Inside Card)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.70f))
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                IconButton(
                                    onClick = { playAudio(recording.file) },
                                    enabled = !isDictating,
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(
                                        imageVector = if (isThisPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                        contentDescription = if (isThisPlaying) "Pause" else "Play",
                                        tint = if (isDictating) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f) else MaterialTheme.colorScheme.primary
                                    )
                                }

                                Column(modifier = Modifier.weight(1f).padding(horizontal = 8.dp)) {
                                    val currentMs = if (playingFile == recording.file) currentPositionMs else 0
                                    val maxMs = if (playingFile == recording.file && totalDurationMs > 0) totalDurationMs else recording.durationMs.toInt().coerceAtLeast(1)

                                    Slider(
                                        value = currentMs.toFloat().coerceIn(0f, maxMs.toFloat()),
                                        onValueChange = { targetPos ->
                                            if (playingFile == recording.file) {
                                                seekTo(targetPos.toInt())
                                            }
                                        },
                                        valueRange = 0f..maxMs.toFloat(),
                                        modifier = Modifier.height(24.dp)
                                    )

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = formatDurationMs(currentMs.toLong()),
                                            fontSize = 10.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(
                                            text = formatDurationMs(maxMs.toLong()),
                                            fontSize = 10.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            // Action buttons: Share, Save to Downloads, Delete
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedButton(
                                    onClick = {
                                        AudioRecordingRepository.shareRecording(context, recording)
                                    },
                                    modifier = Modifier.height(34.dp),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)
                                ) {
                                    Icon(Icons.Filled.Share, contentDescription = "Share", modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Share", fontSize = 11.sp)
                                }

                                Spacer(modifier = Modifier.width(8.dp))

                                OutlinedButton(
                                    onClick = {
                                        val success = AudioRecordingRepository.saveToDownloads(context, recording)
                                        if (success) {
                                            Toast.makeText(context, "Saved to Downloads folder", Toast.LENGTH_SHORT).show()
                                        } else {
                                            Toast.makeText(context, "Failed to save to Downloads", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    modifier = Modifier.height(34.dp),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)
                                ) {
                                    Icon(Icons.Filled.Download, contentDescription = "Save to Downloads", modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Downloads", fontSize = 11.sp)
                                }

                                Spacer(modifier = Modifier.width(8.dp))

                                IconButton(
                                    onClick = { onDeleteRecording(recording) },
                                    modifier = Modifier.size(34.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Delete,
                                        contentDescription = "Delete recording",
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Confirmation Dialog for Clear All
    if (showClearConfirmation) {
        AlertDialog(
            onDismissRequest = { showClearConfirmation = false },
            title = { Text("Delete all saved audio?") },
            text = { Text("This will permanently delete all local audio files. This cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearConfirmation = false
                        onClearAllRecordings()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Delete All")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirmation = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

private fun formatDurationMs(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.US, "%02d:%02d", minutes, seconds)
}

private fun formatFileSize(bytes: Long): String {
    return when {
        bytes >= 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes.toDouble() / (1024 * 1024))
        bytes >= 1024 -> String.format(Locale.US, "%d KB", bytes / 1024)
        else -> "$bytes B"
    }
}
