package com.princeniithompson.rapcha.ui.components.diagnostics

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.princeniithompson.rapcha.data.DiagnosticLogEntry
import com.princeniithompson.rapcha.data.DiagnosticSource
import com.princeniithompson.rapcha.data.DiagnosticType
import java.text.SimpleDateFormat
import java.util.Date

/**
 * History Tab: Displays multi-day collapsible accordion logs with event categorization badges.
 * Formatted with clean light surfaces and high-contrast typography.
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
                    modifier = Modifier.size(48.dp),
                    tint = Color(0xFF94A3B8)
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "No diagnostic history recorded yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF0F172A)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Events from both the app and the floating bubble will accumulate here day-by-day and persist across reboots.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF64748B),
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
            .testTag("history_tab_container")
    ) {
        groupedEntries.forEach { (dayKey, entriesForDay) ->
            val isExpanded = expandedDays[dayKey] ?: false
            val firstDate = entriesForDay.firstOrNull()?.timestamp ?: System.currentTimeMillis()
            val dayLabel = displayDayFormat.format(Date(firstDate))

            // Day Header Accordion Card
            item(key = "header_$dayKey") {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clickable { expandedDays[dayKey] = !isExpanded }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF006874))
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = dayLabel,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF0F172A)
                            )
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "${entriesForDay.size} events",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Medium,
                                color = Color(0xFF64748B)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Icon(
                                imageVector = if (isExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                                contentDescription = if (isExpanded) "Collapse" else "Expand",
                                modifier = Modifier.size(20.dp),
                                tint = Color(0xFF64748B)
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
fun DiagnosticEntryRow(entry: DiagnosticLogEntry, timeFormat: SimpleDateFormat) {
    val (sourceBg, sourceFg) = if (entry.source == DiagnosticSource.BUBBLE) {
        Pair(Color(0xFFF3E8FF), Color(0xFF7E22CE)) // Purple
    } else {
        Pair(Color(0xFFCCFBF1), Color(0xFF0F766E)) // Teal
    }

    val (typeBg, typeFg, typeText) = when (entry.type) {
        DiagnosticType.SESSION_START -> Triple(Color(0xFFDBEAFE), Color(0xFF1D4ED8), "START")
        DiagnosticType.SESSION_END -> Triple(Color(0xFFF1F5F9), Color(0xFF475569), "END")
        DiagnosticType.TRANSCRIPT_FINAL -> Triple(Color(0xFFD1FAE5), Color(0xFF047857), "TEXT")
        DiagnosticType.POLISH_CALLED -> Triple(Color(0xFFF3E8FF), Color(0xFF7E22CE), "POLISH")
        DiagnosticType.POLISH_SUCCESS -> Triple(Color(0xFFDCFCE7), Color(0xFF15803D), "POLISHED")
        DiagnosticType.POLISH_FAILED -> Triple(Color(0xFFFEE2E2), Color(0xFFB91C1C), "POLISH_ERR")
        DiagnosticType.ERROR -> Triple(Color(0xFFFEE2E2), Color(0xFFB91C1C), "ERROR")
        DiagnosticType.WARNING -> Triple(Color(0xFFFEF3C7), Color(0xFFB45309), "WARN")
        DiagnosticType.NOISY_ENVIRONMENT -> Triple(Color(0xFFFFEDD5), Color(0xFFC2410C), "NOISY")
        DiagnosticType.SESSION_RECONNECT -> Triple(Color(0xFFFEF9C3), Color(0xFFA16207), "RECONNECT")
        DiagnosticType.SAFE_MODE_TRIGGERED -> Triple(Color(0xFFFEF3C7), Color(0xFFB45309), "SAFE_MODE")
        DiagnosticType.AUDIO_STYLE_CHANGED -> Triple(Color(0xFFE0E7FF), Color(0xFF4338CA), "AUDIO_STYLE")
        DiagnosticType.VOICE_ID_EVENT -> Triple(Color(0xFFEDE9FE), Color(0xFF6D28D9), "VOICE_ID")
        DiagnosticType.CRASH -> Triple(Color(0xFFFEE2E2), Color(0xFF991B1B), "CRASH")
        DiagnosticType.PROCESS_EXIT -> Triple(Color(0xFFFFEDD5), Color(0xFF9A3412), "EXIT")
        DiagnosticType.INTERIM_STALL -> Triple(Color(0xFFFEF3C7), Color(0xFFB45309), "STALL")
        DiagnosticType.INTERIM_RESUMED -> Triple(Color(0xFFD1FAE5), Color(0xFF047857), "RESUMED")
        DiagnosticType.SESSION_HEARTBEAT -> Triple(Color(0xFFF1F5F9), Color(0xFF475569), "BEAT")
        DiagnosticType.SPLIT -> Triple(Color(0xFFDBEAFE), Color(0xFF1D4ED8), "SPLIT")
        DiagnosticType.PIECE_FINAL -> Triple(Color(0xFFD1FAE5), Color(0xFF047857), "PIECE")
        DiagnosticType.DEDUP -> Triple(Color(0xFFE0E7FF), Color(0xFF4338CA), "DEDUP")
        DiagnosticType.SOCKET_OPEN -> Triple(Color(0xFFCCFBF1), Color(0xFF0F766E), "OPEN")
        DiagnosticType.SOCKET_CLOSE -> Triple(Color(0xFFF1F5F9), Color(0xFF475569), "CLOSE")
        DiagnosticType.CONNECTION_AGE -> Triple(Color(0xFFFEF3C7), Color(0xFFB45309), "AGE")
        DiagnosticType.KEYBOARD_SHOWN -> Triple(Color(0xFFDBEAFE), Color(0xFF1D4ED8), "KEY")
        DiagnosticType.KEYBOARD_HIDDEN -> Triple(Color(0xFFF1F5F9), Color(0xFF475569), "KEY_OFF")
        DiagnosticType.BUBBLE_SHOWN -> Triple(Color(0xFFD1FAE5), Color(0xFF047857), "BUBBLE")
        DiagnosticType.BUBBLE_HIDDEN -> Triple(Color(0xFFF1F5F9), Color(0xFF475569), "BUBBLE_OFF")
        DiagnosticType.BUBBLE_TAP -> Triple(Color(0xFFE0E7FF), Color(0xFF4338CA), "TAP")
        DiagnosticType.MAIN_THREAD_STALL -> Triple(Color(0xFFFEE2E2), Color(0xFFB91C1C), "STALL_UI")
        DiagnosticType.A11Y_EVENT -> Triple(Color(0xFFFEF3C7), Color(0xFFB45309), "A11Y")
        DiagnosticType.TEXT_INSERTED -> Triple(Color(0xFFDCFCE7), Color(0xFF15803D), "INSERT")
        DiagnosticType.SAFE_MODE_ENGAGED -> Triple(Color(0xFFFEF3C7), Color(0xFFB45309), "SAFE_ON")
        DiagnosticType.SAFE_MODE_EXITED -> Triple(Color(0xFFF1F5F9), Color(0xFF475569), "SAFE_OFF")
        DiagnosticType.SILENCE_DROPPED -> Triple(Color(0xFFFEF3C7), Color(0xFFB45309), "SILENCE")
        DiagnosticType.MIC_RELEASED -> Triple(Color(0xFFF1F5F9), Color(0xFF475569), "MIC")
        DiagnosticType.VOCAB_POLICY -> Triple(Color(0xFFE0E7FF), Color(0xFF4338CA), "VOCAB")
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, Color(0xFFF1F5F9)),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Timestamp
            Text(
                text = timeFormat.format(Date(entry.timestamp)),
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF64748B),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace
            )

            Spacer(modifier = Modifier.width(8.dp))

            // Source Badge (APP vs BUBBLE)
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(sourceBg)
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    text = entry.source.name,
                    color = sourceFg,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.width(6.dp))

            // Event Type Badge
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(typeBg)
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    text = typeText,
                    color = typeFg,
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
                color = if (entry.type == DiagnosticType.ERROR || entry.type == DiagnosticType.POLISH_FAILED || entry.type == DiagnosticType.CRASH) Color(0xFFDC2626)
                       else Color(0xFF0F172A),
                fontWeight = if (entry.type == DiagnosticType.ERROR) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier.weight(1f)
            )
        }
    }
}
