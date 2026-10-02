package com.example.ui.components.diagnostics

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Top toolbar, export actions, and weekly smart vocabulary simulation trigger for diagnostics sheet.
 * Styled with clean white cards and spacious buttons that never squish or wrap vertically.
 */
@Composable
fun DiagnosticsToolbarSection(
    eventCount: Int,
    noteCount: Int,
    isAnalyzingSmartVocab: Boolean,
    onSimulateWeeklySmartVocab: () -> Unit,
    onExportTxt: () -> Unit,
    onRequestClear: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    Column(modifier = modifier.fillMaxWidth()) {
        // Header Bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Diagnostics & System",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF0F172A)
                )
                Text(
                    text = "Unified Multi-Day Persistent Engine",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF64748B)
                )
            }
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .size(40.dp)
                    .background(Color(0xFFF1F5F9), RoundedCornerShape(10.dp))
                    .testTag("diagnostics_close_button")
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "Close diagnostics",
                    tint = Color(0xFF334155),
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // System Actions Card (Summary pill + Copy/Export/.txt/Clear buttons)
        Card(
            colors = CardDefaults.cardColors(containerColor = Color.White),
            border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "$eventCount events · $noteCount notes",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color(0xFF475569),
                        fontWeight = FontWeight.SemiBold
                    )

                    TextButton(
                        onClick = onRequestClear,
                        colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFDC2626)),
                        modifier = Modifier.testTag("clear_logs_button")
                    ) {
                        Icon(Icons.Filled.Delete, contentDescription = "Clear diagnostics", modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Clear All", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            DiagnosticsExportUtils.copyToClipboard(context)
                        },
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, Color(0xFFCBD5E1)),
                        modifier = Modifier
                            .weight(1f)
                            .height(40.dp)
                            .testTag("copy_logs_button")
                    ) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = "Copy diagnostics", tint = Color(0xFF006874), modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Copy Log", fontSize = 12.sp, color = Color(0xFF006874), fontWeight = FontWeight.SemiBold)
                    }

                    OutlinedButton(
                        onClick = onExportTxt,
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, Color(0xFFCBD5E1)),
                        modifier = Modifier
                            .weight(1f)
                            .height(40.dp)
                            .testTag("download_logs_button")
                    ) {
                        Icon(Icons.Filled.Download, contentDescription = "Export txt", tint = Color(0xFF6B4EA2), modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Export .txt", fontSize = 12.sp, color = Color(0xFF6B4EA2), fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Weekly Smart Vocabulary Simulation Card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("simulate_weekly_vocab_card"),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFFF0FDFA)),
            border = BorderStroke(1.dp, Color(0xFFCCFBF1))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Filled.AutoAwesome,
                            contentDescription = null,
                            tint = Color(0xFF0F766E),
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Weekly Smart Vocabulary",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF134E4A)
                        )
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Analyze last 7 days of real transcripts with context-aware Gemini AI",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF115E59)
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = onSimulateWeeklySmartVocab,
                    enabled = !isAnalyzingSmartVocab,
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF006874)),
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
    }
}
