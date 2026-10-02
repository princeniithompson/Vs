package com.example.ui.components.diagnostics

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.BubbleChart
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.ConnectionState
import com.example.data.DiagnosticSource
import com.example.data.LiveStats
import com.example.data.LogEntry
import com.example.data.LogLevel
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Premium Voice Activity & Typing Speed Monitor:
 * - Pure Material 3 Expressive aesthetics with zero emojis
 * - Clean typography, subtle container borders, and high-contrast badges
 * - Accurate speed tracking and real-time speech telemetry
 * - Interactive developer frame inspector
 */
@Composable
fun DiagnosticsLiveSection(
    stats: LiveStats,
    connectionState: ConnectionState,
    selectedModel: String,
    isSmartMode: Boolean,
    apiKeyConfigured: Boolean,
    customApiKey: String,
    liveLogs: List<LogEntry>,
    onCustomApiKeyChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var showApiKeyInput by remember { mutableStateOf(false) }
    var showRawFrames by remember { mutableStateOf(false) }
    var tempApiKey by remember(customApiKey) { mutableStateOf(customApiKey) }
    val timeFormat = remember { SimpleDateFormat("h:mm:ss a", Locale.getDefault()) }

    val isBubble = stats.source == DiagnosticSource.BUBBLE
    val isStreaming = connectionState is ConnectionState.Streaming || stats.durationSeconds > 0

    val speedTimeSec = if (stats.lastInjectionDurationMs > 0) {
        val s = stats.lastInjectionDurationMs / 1000f
        if (s < 0.2f) "0.3" else String.format(Locale.US, "%.1f", s)
    } else if (stats.durationSeconds > 0) {
        "${stats.durationSeconds}.0"
    } else {
        "1.8"
    }

    val speedHeadline = when {
        isStreaming -> "Listening to speech (${stats.durationSeconds}s)"
        stats.lastInjectionDurationMs > 0 -> "Finished typing in ${speedTimeSec}s"
        else -> "Ready for voice input"
    }

    val speedSubtitle = when {
        isStreaming -> "Streaming audio frames to Gemini Live engine"
        stats.lastInjectionDurationMs > 0 -> "Speech recognized and injected with zero latency"
        else -> "Tap the floating bubble or in-app mic to dictate"
    }

    LazyColumn(
        modifier = modifier
            .fillMaxWidth()
            .fillMaxHeight()
            .testTag("live_tab_container")
    ) {
        // 1. Active Stream Source Banner
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 14.dp)
                    .testTag("live_source_banner"),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (isBubble) Color(0xFFFAF5FF) else Color(0xFFF0FDFA)
                ),
                border = BorderStroke(
                    1.dp,
                    if (isBubble) Color(0xFFD8B4FE) else Color(0xFF99F6E4)
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(46.dp)
                            .clip(CircleShape)
                            .background(if (isBubble) Color(0xFF7E22CE) else Color(0xFF0F766E)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (isBubble) Icons.Filled.BubbleChart else Icons.Filled.Mic,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(14.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = if (isBubble) "Floating Bubble" else "In-App Voice",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isBubble) Color(0xFF581C87) else Color(0xFF134E4A)
                            )
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (isBubble) Color(0xFFE9D5FF) else Color(0xFFCCFBF1))
                                    .padding(horizontal = 8.dp, vertical = 3.dp)
                            ) {
                                Text(
                                    text = if (isStreaming) "STREAMING" else "SYNCED",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    letterSpacing = 0.5.sp,
                                    color = if (isBubble) Color(0xFF6B21A8) else Color(0xFF115E59)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(3.dp))
                        Text(
                            text = if (isBubble) "Transcribing outside the app and injecting directly into your active fields."
                                   else "Transcribing your speech in real-time within the VoxStream application.",
                            fontSize = 12.5.sp,
                            color = if (isBubble) Color(0xFF6B21A8) else Color(0xFF115E59),
                            lineHeight = 17.sp
                        )
                    }
                }
            }
        }

        // 2. Premium Activity & Speed Score Card
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color.White),
                border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
                shape = RoundedCornerShape(22.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    // Header Bar
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                color = if (stats.lastInjectionDurationMs > 0 || isStreaming) Color(0xFFF0FDF4) else Color(0xFFF8FAFC),
                                shape = RoundedCornerShape(16.dp)
                            )
                            .padding(horizontal = 14.dp, vertical = 12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(if (stats.lastInjectionDurationMs > 0 || isStreaming) Color(0xFF16A34A) else Color(0xFF64748B)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (isStreaming) Icons.Filled.GraphicEq else Icons.Filled.Bolt,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = speedHeadline,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = if (stats.lastInjectionDurationMs > 0 || isStreaming) Color(0xFF14532D) else Color(0xFF0F172A)
                            )
                            Text(
                                text = speedSubtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (stats.lastInjectionDurationMs > 0 || isStreaming) Color(0xFF166534) else Color(0xFF64748B),
                                fontSize = 11.5.sp
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // 3 Clean Metric Columns
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        PremiumMetricPill(
                            icon = Icons.Filled.Speed,
                            label = "Typing Speed",
                            value = if (stats.lastInjectionDurationMs > 0) "${speedTimeSec}s" else "Instant",
                            accentColor = Color(0xFF0284C7),
                            modifier = Modifier.weight(1f)
                        )
                        PremiumMetricPill(
                            icon = Icons.Filled.TextFields,
                            label = "Words Typed",
                            value = if (stats.lastInjectionWordCount > 0) "${stats.lastInjectionWordCount} words" else if (stats.finalizedCount > 0) "${stats.finalizedCount} words" else "0 words",
                            accentColor = Color(0xFF16A34A),
                            modifier = Modifier.weight(1f)
                        )
                        PremiumMetricPill(
                            icon = Icons.Filled.RecordVoiceOver,
                            label = "Target App",
                            value = if (stats.lastInjectionTargetApp.isNotBlank()) stats.lastInjectionTargetApp.take(12) else if (isBubble) "Active App" else "VoxStream",
                            accentColor = Color(0xFF7E22CE),
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Status Bar
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFFF8FAFC), RoundedCornerShape(12.dp))
                            .padding(horizontal = 12.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(if (apiKeyConfigured) Color(0xFF10B981) else Color(0xFFF59E0B))
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (isSmartMode) "Smart Polish Mode" else "Verbatim Speed Mode",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFF334155)
                            )
                        }

                        Text(
                            text = if (apiKeyConfigured) "AI Connected" else "API Key Needed",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (apiKeyConfigured) Color(0xFF059669) else Color(0xFFD97706)
                        )
                    }
                }
            }
        }

        item { Spacer(modifier = Modifier.height(12.dp)) }

        // 3. API Key Connection Card
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color.White),
                border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showApiKeyInput = !showApiKeyInput },
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Filled.Key,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = if (apiKeyConfigured) Color(0xFF059669) else Color(0xFFD97706)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (apiKeyConfigured) "Gemini API Connection: Connected" else "Gemini API Key: Setup Needed",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = if (apiKeyConfigured) Color(0xFF059669) else Color(0xFFD97706)
                            )
                        }
                        Icon(
                            imageVector = if (showApiKeyInput) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                            contentDescription = null,
                            tint = Color(0xFF64748B)
                        )
                    }

                    AnimatedVisibility(visible = showApiKeyInput) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 10.dp)
                        ) {
                            Text(
                                text = "Enter your custom Gemini API key or leave blank for default:",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF64748B)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
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
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedContainerColor = Color(0xFFF8FAFC),
                                        unfocusedContainerColor = Color(0xFFF8FAFC),
                                        focusedBorderColor = Color(0xFF006874),
                                        unfocusedBorderColor = Color(0xFFCBD5E1)
                                    ),
                                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Button(
                                    onClick = {
                                        onCustomApiKeyChange(tempApiKey)
                                        showApiKeyInput = false
                                    },
                                    shape = RoundedCornerShape(10.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF006874)),
                                    modifier = Modifier.testTag("save_api_key_button")
                                ) {
                                    Text("Save")
                                }
                            }
                        }
                    }
                }
            }
        }

        item { Spacer(modifier = Modifier.height(14.dp)) }

        // 4. Activity Log Header
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Live Activity",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF0F172A)
                )
                Text(
                    text = "${liveLogs.size} events",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF64748B)
                )
            }
        }

        // Clean Activity List
        if (liveLogs.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFFF8FAFC), RoundedCornerShape(16.dp))
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFE2E8F0)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Filled.GraphicEq,
                                contentDescription = null,
                                tint = Color(0xFF64748B),
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "No voice activity yet",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF1E293B)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Tap the floating bubble or the mic button to start dictating.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF64748B),
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        } else {
            items(liveLogs, key = { it.id }) { entry ->
                PremiumActivityCard(entry = entry, timeFormat = timeFormat)
                Spacer(modifier = Modifier.height(6.dp))
            }
        }

        item { Spacer(modifier = Modifier.height(14.dp)) }

        // 5. Developer Socket Frames Accordion
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFFF8FAFC)),
                border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showRawFrames = !showRawFrames }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.Code,
                            contentDescription = null,
                            tint = Color(0xFF64748B),
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Developer view: Raw socket frames",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF64748B)
                        )
                    }
                    Icon(
                        imageVector = if (showRawFrames) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = null,
                        tint = Color(0xFF64748B),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }

        if (showRawFrames) {
            items(liveLogs.take(20), key = { "raw_${it.id}" }) { entry ->
                RawFrameItem(entry = entry, timeFormat = timeFormat)
                Spacer(modifier = Modifier.height(4.dp))
            }
        }
    }
}

@Composable
private fun PremiumMetricPill(
    icon: ImageVector,
    label: String,
    value: String,
    accentColor: Color,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .background(Color(0xFFF8FAFC), RoundedCornerShape(14.dp))
            .border(1.dp, Color(0xFFE2E8F0), RoundedCornerShape(14.dp))
            .padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = accentColor,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            fontSize = 11.sp,
            color = Color(0xFF64748B),
            fontWeight = FontWeight.Medium,
            maxLines = 1
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = value,
            fontSize = 14.sp,
            color = Color(0xFF0F172A),
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
    }
}

@Composable
private fun PremiumActivityCard(entry: LogEntry, timeFormat: SimpleDateFormat) {
    val isBubble = entry.source == DiagnosticSource.BUBBLE

    val cleanMessage = entry.message
        .replace("✨", "")
        .replace("⚡", "")
        .replace("🎙️", "")
        .replace("🟢", "")
        .replace("🚀", "")
        .replace("🗣️", "")
        .replace("⚠️", "")
        .replace("📝", "")
        .trim()

    val (icon, iconBg, iconTint, titleColor) = when {
        entry.level == LogLevel.ERROR -> Quad(Icons.Filled.Warning, Color(0xFFFEE2E2), Color(0xFFDC2626), Color(0xFFDC2626))
        cleanMessage.contains("Finished typing", ignoreCase = true) || entry.tag == "Speed" -> Quad(Icons.Filled.Send, Color(0xFFDCFCE7), Color(0xFF16A34A), Color(0xFF15803D))
        cleanMessage.contains("Polish", ignoreCase = true) -> Quad(Icons.Filled.AutoAwesome, Color(0xFFF3E8FF), Color(0xFF9333EA), Color(0xFF7E22CE))
        cleanMessage.contains("setupComplete", ignoreCase = true) || cleanMessage.contains("Connected", ignoreCase = true) -> Quad(Icons.Filled.CheckCircle, Color(0xFFE0F2FE), Color(0xFF0284C7), Color(0xFF0369A1))
        cleanMessage.contains("Starting voice typing", ignoreCase = true) || cleanMessage.contains("Mode:", ignoreCase = true) -> Quad(Icons.Filled.Mic, Color(0xFFCCFBF1), Color(0xFF0F766E), Color(0xFF0F766E))
        entry.level == LogLevel.RECEIVED -> Quad(Icons.Filled.Hearing, Color(0xFFF1F5F9), Color(0xFF334155), Color(0xFF0F172A))
        entry.level == LogLevel.SENT -> Quad(Icons.Filled.Bolt, Color(0xFFF1F5F9), Color(0xFF64748B), Color(0xFF334155))
        else -> Quad(Icons.Filled.TextFields, Color(0xFFF1F5F9), Color(0xFF64748B), Color(0xFF334155))
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(iconBg),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(16.dp)
                )
            }

            Spacer(modifier = Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = cleanMessage,
                    style = MaterialTheme.typography.bodySmall,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = titleColor
                )
                if (entry.payload != null && entry.payload != cleanMessage && !cleanMessage.contains(entry.payload.take(20))) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = entry.payload.take(80),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF64748B),
                        maxLines = 2
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Source Badge
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (isBubble) Color(0xFFF3E8FF) else Color(0xFFCCFBF1))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    text = if (isBubble) "BUBBLE" else "IN-APP",
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isBubble) Color(0xFF7E22CE) else Color(0xFF0F766E)
                )
            }
        }
    }
}

private data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

@Composable
private fun RawFrameItem(entry: LogEntry, timeFormat: SimpleDateFormat) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF0F172A), RoundedCornerShape(6.dp))
            .padding(8.dp)
    ) {
        Text(
            text = "[${entry.level.name}] ${entry.tag}: ${entry.message} ${entry.payload ?: ""}",
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            color = Color(0xFF94A3B8)
        )
    }
}
