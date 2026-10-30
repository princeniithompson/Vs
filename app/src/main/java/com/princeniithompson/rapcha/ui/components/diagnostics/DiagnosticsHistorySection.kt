package com.princeniithompson.rapcha.ui.components.diagnostics

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.princeniithompson.rapcha.data.DiagnosticLogEntry
import com.princeniithompson.rapcha.data.DiagnosticSource
import com.princeniithompson.rapcha.data.DiagnosticType
import java.text.SimpleDateFormat
import java.util.Date

/**
 * History page: multi-day event log, grouped and expandable, with the same entries as before.
 */
@Composable
fun DiagnosticsHistorySection(
    groupedEntries: Map<String, List<DiagnosticLogEntry>>,
    expandedDays: MutableMap<String, Boolean>,
    displayDayFormat: SimpleDateFormat,
    timeFormat: SimpleDateFormat,
    modifier: Modifier = Modifier
) {
    if (groupedEntries.isEmpty()) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .padding(32.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = Icons.Filled.History,
                    contentDescription = null,
                    modifier = Modifier.size(56.dp),
                    tint = DiagnosticsPalette.muted
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "No diagnostic history recorded yet.",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = DiagnosticsPalette.ink,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Events from both the app and the floating bubble will accumulate here day-by-day and persist across reboots.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = DiagnosticsPalette.muted,
                    textAlign = TextAlign.Center
                )
            }
        }
        return
    }

    LazyColumn(
        modifier = modifier
            .fillMaxWidth()
            .fillMaxHeight()
            .testTag("history_tab_container"),
        contentPadding = PaddingValues(bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        groupedEntries.forEach { (dayKey, entriesForDay) ->
            val isExpanded = expandedDays[dayKey] ?: false
            val firstDate = entriesForDay.firstOrNull()?.timestamp ?: System.currentTimeMillis()
            val dayLabel = displayDayFormat.format(Date(firstDate))

            item(key = "header_$dayKey") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .premiumSurface()
                        .clickable { expandedDays[dayKey] = !isExpanded }
                        .heightIn(min = 64.dp)
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = dayLabel,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = DiagnosticsPalette.ink
                        )
                        Text(
                            text = "${entriesForDay.size} events",
                            style = MaterialTheme.typography.bodyMedium,
                            color = DiagnosticsPalette.muted
                        )
                    }
                    Icon(
                        imageVector = if (isExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = if (isExpanded) "Collapse" else "Expand",
                        modifier = Modifier.size(28.dp),
                        tint = DiagnosticsPalette.muted
                    )
                }
            }

            if (isExpanded) {
                items(entriesForDay, key = { it.id }) { entry ->
                    DiagnosticEntryRow(entry = entry, timeFormat = timeFormat)
                }
                item(key = "divider_$dayKey") {
                    Spacer(modifier = Modifier.height(4.dp))
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DiagnosticEntryRow(entry: DiagnosticLogEntry, timeFormat: SimpleDateFormat) {
    val (sourceBg, sourceFg) = if (entry.source == DiagnosticSource.BUBBLE) {
        Pair(DiagnosticsPalette.purpleSoft, DiagnosticsPalette.purple)
    } else {
        Pair(DiagnosticsPalette.greenSoft, DiagnosticsPalette.teal)
    }

    val (typeBg, typeFg, typeText) = when (entry.type) {
        DiagnosticType.SESSION_START -> Triple(Color(0xFFDBEAFE), Color(0xFF1D4ED8), "START")
        DiagnosticType.SESSION_END -> Triple(Color(0xFFF1F5F9), DiagnosticsPalette.muted, "END")
        DiagnosticType.TRANSCRIPT_FINAL -> Triple(Color(0xFFD1FAE5), Color(0xFF047857), "TEXT")
        DiagnosticType.POLISH_CALLED -> Triple(DiagnosticsPalette.purpleSoft, DiagnosticsPalette.purple, "POLISH")
        DiagnosticType.POLISH_SUCCESS -> Triple(DiagnosticsPalette.greenSoft, DiagnosticsPalette.green, "POLISHED")
        DiagnosticType.POLISH_FAILED -> Triple(DiagnosticsPalette.redSoft, DiagnosticsPalette.red, "POLISH_ERR")
        DiagnosticType.ERROR -> Triple(DiagnosticsPalette.redSoft, DiagnosticsPalette.red, "ERROR")
        DiagnosticType.WARNING -> Triple(DiagnosticsPalette.amberSoft, DiagnosticsPalette.amber, "WARN")
        DiagnosticType.NOISY_ENVIRONMENT -> Triple(Color(0xFFFFEDD5), Color(0xFFC2410C), "NOISY")
        DiagnosticType.SESSION_RECONNECT -> Triple(Color(0xFFFEF9C3), Color(0xFFA16207), "RECONNECT")
        DiagnosticType.SAFE_MODE_TRIGGERED -> Triple(DiagnosticsPalette.amberSoft, DiagnosticsPalette.amber, "SAFE_MODE")
        DiagnosticType.AUDIO_STYLE_CHANGED -> Triple(Color(0xFFE0E7FF), Color(0xFF4338CA), "AUDIO_STYLE")
        DiagnosticType.VOICE_ID_EVENT -> Triple(Color(0xFFEDE9FE), Color(0xFF6D28D9), "VOICE_ID")
        DiagnosticType.CRASH -> Triple(DiagnosticsPalette.redSoft, Color(0xFF991B1B), "CRASH")
        DiagnosticType.PROCESS_EXIT -> Triple(Color(0xFFFFEDD5), Color(0xFF9A3412), "EXIT")
        DiagnosticType.INTERIM_STALL -> Triple(DiagnosticsPalette.amberSoft, DiagnosticsPalette.amber, "STALL")
        DiagnosticType.INTERIM_RESUMED -> Triple(Color(0xFFD1FAE5), Color(0xFF047857), "RESUMED")
        DiagnosticType.SESSION_HEARTBEAT -> Triple(Color(0xFFF1F5F9), DiagnosticsPalette.muted, "BEAT")
        DiagnosticType.SPLIT -> Triple(Color(0xFFDBEAFE), Color(0xFF1D4ED8), "SPLIT")
        DiagnosticType.PIECE_FINAL -> Triple(Color(0xFFD1FAE5), Color(0xFF047857), "PIECE")
        DiagnosticType.DEDUP -> Triple(Color(0xFFE0E7FF), Color(0xFF4338CA), "DEDUP")
        DiagnosticType.SOCKET_OPEN -> Triple(DiagnosticsPalette.greenSoft, DiagnosticsPalette.teal, "OPEN")
        DiagnosticType.SOCKET_CLOSE -> Triple(Color(0xFFF1F5F9), DiagnosticsPalette.muted, "CLOSE")
        DiagnosticType.CONNECTION_AGE -> Triple(DiagnosticsPalette.amberSoft, DiagnosticsPalette.amber, "AGE")
        DiagnosticType.KEYBOARD_SHOWN -> Triple(Color(0xFFDBEAFE), Color(0xFF1D4ED8), "KEY")
        DiagnosticType.KEYBOARD_HIDDEN -> Triple(Color(0xFFF1F5F9), DiagnosticsPalette.muted, "KEY_OFF")
        DiagnosticType.BUBBLE_SHOWN -> Triple(DiagnosticsPalette.greenSoft, DiagnosticsPalette.green, "BUBBLE")
        DiagnosticType.BUBBLE_HIDDEN -> Triple(Color(0xFFF1F5F9), DiagnosticsPalette.muted, "BUBBLE_OFF")
        DiagnosticType.BUBBLE_TAP -> Triple(Color(0xFFE0E7FF), Color(0xFF4338CA), "TAP")
        DiagnosticType.MAIN_THREAD_STALL -> Triple(DiagnosticsPalette.redSoft, DiagnosticsPalette.red, "STALL_UI")
        DiagnosticType.A11Y_EVENT -> Triple(DiagnosticsPalette.amberSoft, DiagnosticsPalette.amber, "A11Y")
        DiagnosticType.TEXT_INSERTED -> Triple(DiagnosticsPalette.greenSoft, DiagnosticsPalette.green, "INSERT")
        DiagnosticType.SAFE_MODE_ENGAGED -> Triple(DiagnosticsPalette.amberSoft, DiagnosticsPalette.amber, "SAFE_ON")
        DiagnosticType.SAFE_MODE_EXITED -> Triple(Color(0xFFF1F5F9), DiagnosticsPalette.muted, "SAFE_OFF")
        DiagnosticType.SILENCE_DROPPED -> Triple(DiagnosticsPalette.amberSoft, DiagnosticsPalette.amber, "SILENCE")
        DiagnosticType.MIC_RELEASED -> Triple(Color(0xFFF1F5F9), DiagnosticsPalette.muted, "MIC")
        DiagnosticType.RETRY_OFFERED -> Triple(DiagnosticsPalette.amberSoft, DiagnosticsPalette.amber, "RETRY")
        DiagnosticType.RETRY_STARTED -> Triple(Color(0xFFDBEAFE), Color(0xFF1D4ED8), "RETRY")
        DiagnosticType.RETRY_SUCCESS -> Triple(DiagnosticsPalette.greenSoft, DiagnosticsPalette.green, "RETRIED")
        DiagnosticType.RETRY_FAILED -> Triple(DiagnosticsPalette.redSoft, DiagnosticsPalette.red, "RETRY_ERR")
        DiagnosticType.RETRY_DISCARDED -> Triple(Color(0xFFF1F5F9), DiagnosticsPalette.muted, "DISCARD")
    }

    val messageColor = if (
        entry.type == DiagnosticType.ERROR ||
        entry.type == DiagnosticType.POLISH_FAILED ||
        entry.type == DiagnosticType.CRASH ||
        entry.type == DiagnosticType.RETRY_FAILED
    ) {
        DiagnosticsPalette.red
    } else {
        DiagnosticsPalette.ink
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .premiumSurface()
            .padding(14.dp)
    ) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = timeFormat.format(Date(entry.timestamp)),
                style = MaterialTheme.typography.labelLarge,
                color = DiagnosticsPalette.muted,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium
            )
            DiagnosticsCountBadge(
                text = entry.source.name,
                container = sourceBg,
                content = sourceFg
            )
            DiagnosticsCountBadge(
                text = typeText,
                container = typeBg,
                content = typeFg
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = entry.message,
            style = MaterialTheme.typography.bodyLarge,
            color = messageColor,
            fontWeight = if (entry.type == DiagnosticType.ERROR) FontWeight.Bold else FontWeight.Normal
        )
    }
}
