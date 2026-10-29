package com.princeniithompson.rapcha.ui.components.diagnostics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.princeniithompson.rapcha.data.DiagnosticNote
import java.text.SimpleDateFormat
import java.util.Date

/**
 * Notes page: timestamped field observations, with the same add-note action.
 */
@Composable
fun DiagnosticsNotesSection(
    notes: List<DiagnosticNote>,
    noteDateFormat: SimpleDateFormat,
    onAddNote: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var noteInput by remember { mutableStateOf("") }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .fillMaxHeight()
            .testTag("notes_tab_container")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .premiumSurface()
                .padding(16.dp)
        ) {
            Text(
                text = "Add a field note",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = colors.ink
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Jot down testing observations. Each note is timestamped and included in exports.",
                style = MaterialTheme.typography.bodyLarge,
                color = colors.secondary
            )
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = noteInput,
                onValueChange = { noteInput = it },
                placeholder = {
                    Text(
                        text = "Describe what happened...",
                        style = MaterialTheme.typography.bodyLarge,
                        color = colors.secondary
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("add_note_input"),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = colors.canvas,
                    unfocusedContainerColor = colors.canvas,
                    focusedBorderColor = colors.ink,
                    unfocusedBorderColor = colors.line,
                    focusedTextColor = colors.ink,
                    unfocusedTextColor = colors.ink
                ),
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.ink),
                maxLines = 4
            )
            Spacer(modifier = Modifier.height(12.dp))
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
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.ink,
                        contentColor = colors.canvas
                    ),
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("submit_add_note_button")
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(22.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Add Note",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        Text(
            text = "Chronological notes (${notes.size})",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = colors.ink
        )

        Spacer(modifier = Modifier.height(10.dp))

        if (notes.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .premiumSurface()
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No notes yet. Add one above to record a testing observation.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.secondary,
                    textAlign = TextAlign.Center
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(notes, key = { it.id }) { note ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .premiumSurface()
                            .padding(16.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Filled.EditNote,
                                contentDescription = null,
                                tint = colors.ink,
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = noteDateFormat.format(Date(note.timestamp)),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = colors.secondary
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = note.text,
                            style = MaterialTheme.typography.bodyLarge,
                            color = colors.ink
                        )
                    }
                }
            }
        }
    }
}
