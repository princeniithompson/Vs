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
    val colors = diagnosticsColors()
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
                    tint = colors.secondary
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "No diagnostic history recorded yet.",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = colors.ink,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Events from both the app and the floating bubble will accumulate here day-by-day and persist across reboots.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.secondary,
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
                            color = colors.ink
                        )
                        Text(
                            text = diagnosticsCountText(entriesForDay.size),
                            style = MaterialTheme.typography.bodyLarge,
                            fontFamily = FontFamily.Monospace,
                            color = colors.secondary
                        )
                    }
                    Icon(
                        imageVector = if (isExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = if (isExpanded) "Collapse" else "Expand",
                        modifier = Modifier.size(28.dp),
                        tint = colors.secondary
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

@Composable
fun DiagnosticEntryRow(entry: DiagnosticLogEntry, timeFormat: SimpleDateFormat) {
    val colors = diagnosticsColors()
    val problem = entry.type == DiagnosticType.ERROR ||
        entry.type == DiagnosticType.POLISH_FAILED ||
        entry.type == DiagnosticType.CRASH ||
        entry.type == DiagnosticType.WARNING ||
        entry.type == DiagnosticType.MAIN_THREAD_STALL
    val messageColor = when {
        entry.type == DiagnosticType.WARNING || entry.type == DiagnosticType.INTERIM_STALL -> colors.warning
        problem -> colors.danger
        else -> colors.ink
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .premiumSurface()
            .padding(12.dp)
    ) {
        Text(
            text = "${timeFormat.format(Date(entry.timestamp))}  ${entry.source.name}  ${entry.type.name}",
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            color = colors.secondary
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = entry.message,
            style = MaterialTheme.typography.bodyLarge,
            color = messageColor,
            fontWeight = if (problem) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}
