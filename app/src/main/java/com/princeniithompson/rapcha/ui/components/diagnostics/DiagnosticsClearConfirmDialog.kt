package com.princeniithompson.rapcha.ui.components.diagnostics

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Confirmation dialog before clearing all multi-day diagnostic events and field notes.
 */
@Composable
fun DiagnosticsClearConfirmDialog(
    onDismissRequest: () -> Unit,
    onConfirm: () -> Unit
) {
    val colors = diagnosticsColors()
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = {
            Text(
                text = "Clear all diagnostics?",
                fontWeight = FontWeight.Bold,
                color = colors.textPrimary
            )
        },
        text = {
            Text(
                text = "This deletes everything logged so far (including multi-day event history and user notes) and cannot be undone.",
                style = MaterialTheme.typography.bodyLarge,
                color = colors.textSecondary
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.statusError,
                    contentColor = colors.background
                ),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("confirm_clear_diagnostics_button")
            ) {
                Text("Clear", fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismissRequest,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("cancel_clear_diagnostics_button")
            ) {
                Text("Cancel", color = colors.textSecondary, fontWeight = FontWeight.SemiBold)
            }
        },
        containerColor = colors.surfaceRaised,
        shape = RoundedCornerShape(20.dp)
    )
}
