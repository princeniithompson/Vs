package com.princeniithompson.rapcha.ui.components.diagnostics

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.princeniithompson.rapcha.data.ConnectionState

/**
 * Hub header: title, health chips, and the large Copy / Export / Clear / Options tiles.
 */
@Composable
fun DiagnosticsToolbarSection(
    eventCount: Int,
    noteCount: Int,
    connectionState: ConnectionState,
    onExportTxt: () -> Unit,
    onRequestClear: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var showOptions by remember { mutableStateOf(false) }
    val menuActions = standardDiagnosticsMenu(
        context = context,
        onExportTxt = onExportTxt,
        onRequestClear = onRequestClear
    )
    val liveChip = liveChipStyle(connectionState)

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                Text(
                    text = "Diagnostics",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = DiagnosticsPalette.ink
                )
                Text(
                    text = "System health & logs",
                    style = MaterialTheme.typography.bodyLarge,
                    color = DiagnosticsPalette.muted
                )
            }
            DiagnosticsSquareIconButton(
                icon = Icons.Filled.Close,
                contentDescription = "Close diagnostics",
                onClick = onDismiss,
                testTag = "diagnostics_close_button"
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            DiagnosticsStatusChip(
                text = liveChip.label,
                container = liveChip.container,
                content = liveChip.content,
                borderColor = liveChip.border
            )
            DiagnosticsStatusChip(
                text = "$eventCount events",
                container = DiagnosticsPalette.blueSoft,
                content = DiagnosticsPalette.blue
            )
            DiagnosticsStatusChip(
                text = "$noteCount notes",
                container = DiagnosticsPalette.purpleSoft,
                content = DiagnosticsPalette.purple
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .premiumSurface(ring = false)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                DiagnosticsActionTile(
                    label = "Copy",
                    icon = Icons.Filled.ContentCopy,
                    background = DiagnosticsPalette.greenSoft,
                    tint = DiagnosticsPalette.green,
                    onClick = { DiagnosticsExportUtils.copyToClipboard(context) },
                    testTag = "copy_logs_button",
                    modifier = Modifier.weight(1f)
                )
                DiagnosticsActionTile(
                    label = "Export",
                    icon = Icons.Filled.Download,
                    background = DiagnosticsPalette.blueSoft,
                    tint = DiagnosticsPalette.blue,
                    onClick = onExportTxt,
                    testTag = "download_logs_button",
                    modifier = Modifier.weight(1f)
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                DiagnosticsActionTile(
                    label = "Clear",
                    icon = Icons.Filled.Delete,
                    background = DiagnosticsPalette.redSoft,
                    tint = DiagnosticsPalette.red,
                    onClick = onRequestClear,
                    testTag = "clear_logs_button",
                    modifier = Modifier.weight(1f)
                )
                Box(modifier = Modifier.weight(1f)) {
                    DiagnosticsActionTile(
                        label = "Options",
                        icon = Icons.Filled.MoreHoriz,
                        background = DiagnosticsPalette.purpleSoft,
                        tint = DiagnosticsPalette.purple,
                        onClick = { showOptions = true },
                        testTag = "diagnostics_options_button",
                        modifier = Modifier.fillMaxWidth()
                    )
                    DiagnosticsActionMenu(
                        expanded = showOptions,
                        onDismissRequest = { showOptions = false },
                        actions = menuActions
                    )
                }
            }
        }
    }
}

private data class LiveChipStyle(
    val label: String,
    val container: Color,
    val content: Color,
    val border: Color?
)

private fun liveChipStyle(state: ConnectionState): LiveChipStyle {
    return when (state) {
        is ConnectionState.Error -> LiveChipStyle(
            label = "Live · Error",
            container = DiagnosticsPalette.redSoft,
            content = DiagnosticsPalette.red,
            border = DiagnosticsPalette.red
        )
        is ConnectionState.Connecting -> LiveChipStyle(
            label = "Live · Connecting",
            container = DiagnosticsPalette.amberSoft,
            content = DiagnosticsPalette.amber,
            border = DiagnosticsPalette.amber
        )
        is ConnectionState.ConnectedWaitingSetup -> LiveChipStyle(
            label = "Live · Setup",
            container = DiagnosticsPalette.amberSoft,
            content = DiagnosticsPalette.amber,
            border = DiagnosticsPalette.amber
        )
        is ConnectionState.Stopping -> LiveChipStyle(
            label = "Live · Stopping",
            container = DiagnosticsPalette.amberSoft,
            content = DiagnosticsPalette.amber,
            border = DiagnosticsPalette.amber
        )
        is ConnectionState.Streaming,
        is ConnectionState.Idle -> LiveChipStyle(
            label = "Live · OK",
            container = DiagnosticsPalette.greenChip,
            content = DiagnosticsPalette.green,
            border = DiagnosticsPalette.greenRing
        )
    }
}
