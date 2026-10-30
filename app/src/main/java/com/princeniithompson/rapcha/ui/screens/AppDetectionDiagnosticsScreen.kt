package com.princeniithompson.rapcha.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.princeniithompson.rapcha.data.AppDetectionEvent
import com.princeniithompson.rapcha.data.AppDetectionLogRepository
import com.princeniithompson.rapcha.ui.components.diagnostics.DiagnosticsActionMenu
import com.princeniithompson.rapcha.ui.components.diagnostics.DiagnosticsCountBadge
import com.princeniithompson.rapcha.ui.components.diagnostics.DiagnosticsLabeledIconButton
import com.princeniithompson.rapcha.ui.components.diagnostics.DiagnosticsMenuAction
import com.princeniithompson.rapcha.ui.components.diagnostics.DiagnosticsPalette
import com.princeniithompson.rapcha.ui.components.diagnostics.DiagnosticsSquareIconButton
import com.princeniithompson.rapcha.ui.components.diagnostics.premiumSurface
import com.princeniithompson.rapcha.ui.components.diagnostics.standardDiagnosticsMenu

@Composable
fun AppDetectionDiagnosticsScreen(
    onBackClick: () -> Unit,
    onDismiss: () -> Unit = {},
    onExportTxt: () -> Unit = {},
    onRequestClearDiagnostics: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val events by AppDetectionLogRepository.events.collectAsState()
    var showClearDialog by remember { mutableStateOf(false) }
    var showOptions by remember { mutableStateOf(false) }
    val expandedMap = remember { mutableStateMapOf<String, Boolean>() }

    val menuActions = standardDiagnosticsMenu(
        context = context,
        onExportTxt = onExportTxt,
        onRequestClear = onRequestClearDiagnostics,
        extra = listOf(
            DiagnosticsMenuAction(
                label = "Copy app detection report",
                onClick = { copyAppDetectionReport(context) },
                testTag = "copy_all_button",
                icon = Icons.Filled.ContentCopy
            ),
            DiagnosticsMenuAction(
                label = "Clear app detection logs",
                onClick = { showClearDialog = true },
                destructive = true,
                testTag = "clear_logs_button",
                icon = Icons.Filled.Delete
            )
        )
    )

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = {
                Text(
                    text = "Clear App Detection Logs",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = "Are you sure you want to clear all recorded app detection and classification logs?",
                    style = MaterialTheme.typography.bodyLarge
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearDialog = false
                        AppDetectionLogRepository.clearEvents()
                        Toast.makeText(context, "App detection logs cleared.", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text(
                        text = "Clear",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showClearDialog = false },
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text(text = "Cancel", style = MaterialTheme.typography.titleMedium)
                }
            }
        )
    }

    Column(modifier = modifier.fillMaxSize()) {
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
                    text = "App Detection",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = DiagnosticsPalette.ink
                )
                Text(
                    text = "Raw metadata extracted during app scans",
                    style = MaterialTheme.typography.bodyMedium,
                    color = DiagnosticsPalette.muted
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

        if (events.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Filled.Info,
                        contentDescription = null,
                        modifier = Modifier.size(56.dp),
                        tint = DiagnosticsPalette.muted
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "No app detection events yet",
                        style = MaterialTheme.typography.titleMedium,
                        color = DiagnosticsPalette.ink,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Trigger the floating bubble in apps to inspect metadata.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = DiagnosticsPalette.muted,
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f),
                contentPadding = PaddingValues(bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(events, key = { it.id }) { event ->
                    AppDetectionEventCard(
                        event = event,
                        expanded = expandedMap[event.id] == true,
                        onToggle = { expandedMap[event.id] = expandedMap[event.id] != true }
                    )
                }
            }
        }
    }
}

@Composable
private fun AppDetectionEventCard(
    event: AppDetectionEvent,
    expanded: Boolean,
    onToggle: () -> Unit
) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .premiumSurface()
            .clickable(onClick = onToggle)
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = event.resolvedAppName,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = DiagnosticsPalette.ink
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = event.formattedTime,
                    style = MaterialTheme.typography.bodyMedium,
                    color = DiagnosticsPalette.muted
                )
            }
            DiagnosticsCountBadge(
                text = event.finalCategory,
                container = categoryBackground(event.finalCategory),
                content = categoryForeground(event.finalCategory)
            )
            IconButton(
                onClick = onToggle,
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = DiagnosticsPalette.muted
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        Text(
            text = "Package: ${event.rawPackageName}",
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            color = DiagnosticsPalette.ink
        )
        Text(
            text = event.classificationSource,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = DiagnosticsPalette.blue
        )

        AnimatedVisibility(visible = expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(DiagnosticsPalette.slate)
                    .padding(14.dp)
            ) {
                DetailRow(label = "Window Title", value = event.rawWindowTitle ?: "None")
                Spacer(modifier = Modifier.height(8.dp))
                DetailRow(label = "PackageManager Label", value = event.packageManagerLabel ?: "None")

                if (event.topScreenTexts.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "Top screen texts",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = DiagnosticsPalette.ink
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    event.topScreenTexts.forEach { textSnippet ->
                        Text(
                            text = "• $textSnippet",
                            style = MaterialTheme.typography.bodyLarge,
                            color = DiagnosticsPalette.ink,
                            modifier = Modifier.padding(start = 8.dp, bottom = 4.dp)
                        )
                    }
                }

                if (!event.aiPromptSent.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "AI prompt sent",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = DiagnosticsPalette.ink
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = event.aiPromptSent ?: "",
                        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                        color = DiagnosticsPalette.ink,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color.White, RoundedCornerShape(12.dp))
                            .padding(12.dp)
                    )
                }

                if (!event.aiRawResponse.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "AI raw response",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = DiagnosticsPalette.ink
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = event.aiRawResponse ?: "",
                        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                        color = DiagnosticsPalette.ink,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color.White, RoundedCornerShape(12.dp))
                            .padding(12.dp)
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedButton(
                    onClick = {
                        val eventText = buildString {
                            appendLine("App Detection Event [${event.formattedTime}]")
                            appendLine("Resolved App Name: ${event.resolvedAppName}")
                            appendLine("Category: ${event.finalCategory}")
                            appendLine("Source: ${event.classificationSource}")
                            appendLine("Package: ${event.rawPackageName}")
                            appendLine("Window Title: ${event.rawWindowTitle ?: "None"}")
                            appendLine("PackageManager Label: ${event.packageManagerLabel ?: "None"}")
                            if (event.topScreenTexts.isNotEmpty()) {
                                appendLine("Top Screen Texts: ${event.topScreenTexts.joinToString(", ")}")
                            }
                            if (!event.aiPromptSent.isNullOrBlank()) {
                                appendLine("Prompt: ${event.aiPromptSent}")
                            }
                            if (!event.aiRawResponse.isNullOrBlank()) {
                                appendLine("Response: ${event.aiRawResponse}")
                            }
                        }
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("App Detection Event", eventText))
                        Toast.makeText(context, "Copied event trace to clipboard", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.ContentCopy,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Copy Event Trace",
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium,
            color = DiagnosticsPalette.muted
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            color = DiagnosticsPalette.ink
        )
    }
}

private fun copyAppDetectionReport(context: Context) {
    val report = AppDetectionLogRepository.exportAsFormattedText()
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("App Detection Diagnostics Report", report))
    Toast.makeText(context, "Copied all logs to clipboard", Toast.LENGTH_SHORT).show()
}

private fun categoryBackground(category: String): Color = when (category.uppercase()) {
    "AI" -> Color(0xFFEEF2FF)
    "SOCIAL" -> Color(0xFFF0FDF4)
    "EMAIL" -> Color(0xFFFEF3C7)
    "WORK" -> DiagnosticsPalette.purpleSoft
    else -> DiagnosticsPalette.pill
}

private fun categoryForeground(category: String): Color = when (category.uppercase()) {
    "AI" -> Color(0xFF4F46E5)
    "SOCIAL" -> Color(0xFF16A34A)
    "EMAIL" -> Color(0xFFD97706)
    "WORK" -> DiagnosticsPalette.purple
    else -> DiagnosticsPalette.ink
}
