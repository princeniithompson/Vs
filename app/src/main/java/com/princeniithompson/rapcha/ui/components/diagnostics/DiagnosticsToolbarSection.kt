package com.princeniithompson.rapcha.ui.components.diagnostics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.princeniithompson.rapcha.data.ConnectionState

/**
 * Title plus a slim Copy / Export / Options row. Clear stays a text action.
 */
@Composable
fun DiagnosticsToolbarSection(
    connectionState: ConnectionState,
    onExportTxt: () -> Unit,
    onRequestClear: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val colors = diagnosticsColors()
    var showOptions by remember { mutableStateOf(false) }
    val menuActions = standardDiagnosticsMenu(
        context = context,
        onExportTxt = onExportTxt,
        onRequestClear = onRequestClear
    )
    val (statusLabel, statusColor) = liveStatus(connectionState, colors)

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                Text(
                    text = "Diagnostics",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = colors.ink
                )
                Text(
                    text = "On-device logs",
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.secondary
                )
            }
            DiagnosticsSquareIconButton(
                icon = Icons.Filled.Close,
                contentDescription = "Close diagnostics",
                onClick = onDismiss,
                testTag = "diagnostics_close_button"
            )
        }

        Text(
            text = statusLabel,
            style = MaterialTheme.typography.bodyLarge,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
            color = statusColor,
            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            ToolTextButton(
                label = "Copy",
                onClick = { DiagnosticsExportUtils.copyToClipboard(context) },
                testTag = "copy_logs_button",
                color = colors.ink
            )
            ToolTextButton(
                label = "Export",
                onClick = onExportTxt,
                testTag = "download_logs_button",
                color = colors.ink
            )
            Box {
                ToolTextButton(
                    label = "Options",
                    onClick = { showOptions = true },
                    testTag = "diagnostics_options_button",
                    color = colors.ink
                )
                DiagnosticsActionMenu(
                    expanded = showOptions,
                    onDismissRequest = { showOptions = false },
                    actions = menuActions
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            ToolTextButton(
                label = "Clear",
                onClick = onRequestClear,
                testTag = "clear_logs_button",
                color = colors.danger
            )
        }
    }
}

@Composable
private fun ToolTextButton(
    label: String,
    onClick: () -> Unit,
    testTag: String,
    color: androidx.compose.ui.graphics.Color
) {
    TextButton(
        onClick = onClick,
        modifier = Modifier
            .heightIn(min = 48.dp)
            .testTag(testTag)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium,
            color = color
        )
    }
}

private fun liveStatus(
    state: ConnectionState,
    colors: DiagnosticsColors
): Pair<String, androidx.compose.ui.graphics.Color> {
    return when (state) {
        is ConnectionState.Error -> "Live error" to colors.danger
        is ConnectionState.Connecting,
        is ConnectionState.ConnectedWaitingSetup,
        is ConnectionState.Stopping -> "Live wait" to colors.warning
        is ConnectionState.Streaming,
        is ConnectionState.Idle -> "Live OK" to colors.ok
    }
}
