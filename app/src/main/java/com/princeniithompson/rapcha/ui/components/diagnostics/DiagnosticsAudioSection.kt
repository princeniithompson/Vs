package com.princeniithompson.rapcha.ui.components.diagnostics

import android.media.MediaPlayer
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.princeniithompson.rapcha.data.AudioRecording
import com.princeniithompson.rapcha.data.AudioRecordingRepository
import com.princeniithompson.rapcha.data.DiagnosticSource
import kotlinx.coroutines.delay
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Audio page: local WAV files, playback, share, download, and delete.
 * Playback stays on-device. Share and download are explicit user actions.
 */
@Composable
fun DiagnosticsAudioSection(
    recordings: List<AudioRecording>,
    isDictating: Boolean,
    onDeleteRecording: (AudioRecording) -> Unit,
    onClearAllRecordings: () -> Unit,
    requestClearAll: Boolean = false,
    onClearAllRequestHandled: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var showClearConfirmation by remember { mutableStateOf(false) }
    val expanded = remember { mutableStateMapOf<String, Boolean>() }

    var playingFile by remember { mutableStateOf<File?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var currentPositionMs by remember { mutableIntStateOf(0) }
    var totalDurationMs by remember { mutableIntStateOf(0) }

    val mediaPlayer = remember { MediaPlayer() }

    LaunchedEffect(requestClearAll) {
        if (requestClearAll) {
            showClearConfirmation = true
            onClearAllRequestHandled()
        }
    }

    LaunchedEffect(playingFile, isPlaying) {
        while (isPlaying && playingFile != null) {
            try {
                if (mediaPlayer.isPlaying) {
                    currentPositionMs = mediaPlayer.currentPosition
                    totalDurationMs = mediaPlayer.duration
                }
            } catch (_: Exception) {}
            delay(150)
        }
    }

    LaunchedEffect(isDictating) {
        if (isDictating && isPlaying) {
            try {
                mediaPlayer.stop()
                mediaPlayer.reset()
            } catch (_: Exception) {}
            isPlaying = false
            playingFile = null
            currentPositionMs = 0
            totalDurationMs = 0
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            try {
                mediaPlayer.release()
            } catch (_: Exception) {}
        }
    }

    fun playAudio(file: File) {
        if (isDictating) {
            Toast.makeText(context, "Playback disabled during active dictation", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            if (playingFile == file && isPlaying) {
                mediaPlayer.pause()
                isPlaying = false
                return
            }

            if (playingFile == file && !isPlaying) {
                mediaPlayer.start()
                isPlaying = true
                return
            }

            mediaPlayer.reset()
            mediaPlayer.setDataSource(file.absolutePath)
            mediaPlayer.prepare()
            mediaPlayer.start()

            playingFile = file
            isPlaying = true
            currentPositionMs = 0
            totalDurationMs = mediaPlayer.duration

            mediaPlayer.setOnCompletionListener {
                isPlaying = false
                playingFile = null
                currentPositionMs = 0
            }
        } catch (e: Exception) {
            Log.e("AudioTab", "Error playing recording", e)
            Toast.makeText(context, "Playback error: ${e.message}", Toast.LENGTH_SHORT).show()
            isPlaying = false
            playingFile = null
        }
    }

    fun seekTo(positionMs: Int) {
        try {
            mediaPlayer.seekTo(positionMs)
            currentPositionMs = positionMs
        } catch (e: Exception) {
            Log.e("AudioTab", "Error seeking", e)
        }
    }

    val dateTimeFormat = remember { SimpleDateFormat("MMM dd, yyyy · HH:mm:ss", Locale.getDefault()) }
    val totalBytes = recordings.sumOf { it.sizeBytes }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(bottom = 28.dp)
    ) {
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .premiumSurface()
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Filled.Lock,
                    contentDescription = null,
                    tint = colors.ink,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "Audio stays on this phone. Nothing is uploaded unless you tap Share or Download.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.ink
                )
            }
        }

        item {
            Text(
                text = if (recordings.isEmpty()) {
                    "No saved recordings"
                } else {
                    "${recordings.size} recordings · ${formatFileSize(totalBytes)}"
                },
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = colors.secondary
            )
        }

        if (recordings.isEmpty()) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Filled.GraphicEq,
                        contentDescription = null,
                        tint = colors.secondary,
                        modifier = Modifier.size(56.dp)
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        text = "No saved audio recordings yet",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = colors.ink,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Voice recordings from the app or floating bubble will appear here.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = colors.secondary,
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            itemsIndexed(recordings, key = { _, recording -> recording.fileName }) { index, recording ->
                val isThisPlaying = playingFile == recording.file && isPlaying
                val isExpanded = expanded[recording.fileName] != false
                val currentMs = if (playingFile == recording.file) currentPositionMs else 0
                val maxMs = if (playingFile == recording.file && totalDurationMs > 0) {
                    totalDurationMs
                } else {
                    recording.durationMs.toInt().coerceAtLeast(1)
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .premiumSurface(ring = false)
                        .padding(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = recordingTitle(recording, recordings),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = colors.ink,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = recordingMeta(recording, dateTimeFormat),
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.secondary
                            )
                        }
                        IconButton(
                            onClick = { expanded[recording.fileName] = !isExpanded },
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(
                                imageVector = if (isExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                                contentDescription = if (isExpanded) "Collapse recording" else "Expand recording",
                                tint = colors.secondary
                            )
                        }
                    }

                    if (isExpanded) {
                        Spacer(modifier = Modifier.height(16.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            PremiumPlayButton(
                                isPlaying = isThisPlaying,
                                enabledVisual = !isDictating,
                                onClick = { playAudio(recording.file) }
                            )
                            Spacer(modifier = Modifier.width(14.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.GraphicEq,
                                        contentDescription = null,
                                        tint = colors.secondary,
                                        modifier = Modifier.size(22.dp)
                                    )
                                    Slider(
                                        value = currentMs.toFloat().coerceIn(0f, maxMs.toFloat()),
                                        onValueChange = { targetPos ->
                                            if (playingFile == recording.file) {
                                                seekTo(targetPos.toInt())
                                            }
                                        },
                                        valueRange = 0f..maxMs.toFloat(),
                                        colors = SliderDefaults.colors(
                                            thumbColor = colors.ink,
                                            activeTrackColor = colors.ink,
                                            inactiveTrackColor = colors.line
                                        ),
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(48.dp)
                                    )
                                    Icon(
                                        imageVector = Icons.Filled.GraphicEq,
                                        contentDescription = null,
                                        tint = colors.secondary,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = String.format(Locale.US, "#%03d", index + 1),
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.Medium,
                                        color = colors.secondary
                                    )
                                    DiagnosticsCountBadge(
                                        text = formatDurationMs(maxMs.toLong()),
                                        container = colors.canvas,
                                        content = colors.ink
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            AudioActionPill(
                                icon = Icons.Filled.Share,
                                label = "Share",
                                onClick = { AudioRecordingRepository.shareRecording(context, recording) },
                                modifier = Modifier.weight(1f)
                            )
                            AudioActionPill(
                                icon = Icons.Filled.Download,
                                label = "Download",
                                onClick = {
                                    val success = AudioRecordingRepository.saveToDownloads(context, recording)
                                    if (success) {
                                        Toast.makeText(context, "Saved to Downloads folder", Toast.LENGTH_SHORT).show()
                                    } else {
                                        Toast.makeText(context, "Failed to save to Downloads", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }

                        TextButton(
                            onClick = { onDeleteRecording(recording) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp),
                            colors = ButtonDefaults.textButtonColors(contentColor = colors.danger)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Delete,
                                contentDescription = null,
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Delete",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
        }
    }

    if (showClearConfirmation) {
        AlertDialog(
            onDismissRequest = { showClearConfirmation = false },
            title = {
                Text(
                    text = "Delete all saved audio?",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = colors.ink
                )
            },
            text = {
                Text(
                    text = "This will permanently delete all local audio files from your device. This cannot be undone.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.secondary
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearConfirmation = false
                        onClearAllRecordings()
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                    colors = ButtonDefaults.textButtonColors(contentColor = colors.danger)
                ) {
                    Text(
                        text = "Delete All",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showClearConfirmation = false },
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text(
                        text = "Cancel",
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.secondary,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            },
            containerColor = Color.White,
            shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
        )
    }
}

@Composable
private fun PremiumPlayButton(
    isPlaying: Boolean,
    enabledVisual: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(56.dp)
            .alpha(if (enabledVisual) 1f else 0.45f)
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .border(4.dp, colors.line, CircleShape)
            .padding(7.dp)
            .clip(CircleShape)
            .background(colors.ink),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
            contentDescription = if (isPlaying) "Pause" else "Play",
            tint = Color.White,
            modifier = Modifier.size(34.dp)
        )
    }
}

@Composable
private fun AudioActionPill(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .heightIn(min = 52.dp)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
            .background(colors.canvas)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = colors.ink,
            modifier = Modifier.size(22.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = colors.ink
        )
    }
}

private fun recordingTitle(recording: AudioRecording, recordings: List<AudioRecording>): String {
    val snippet = recording.transcriptSnippet?.trim().orEmpty()
    if (snippet.isNotEmpty()) return snippet.take(48)
    return when (recording.source) {
        DiagnosticSource.APP -> {
            val number = recordings.filter { it.source == DiagnosticSource.APP }
                .indexOfFirst { it.fileName == recording.fileName } + 1
            "Voice Memo $number"
        }
        DiagnosticSource.BUBBLE -> {
            val bubbles = recordings.filter { it.source == DiagnosticSource.BUBBLE }
            if (bubbles.size <= 1) {
                "System Log Audio"
            } else {
                val number = bubbles.indexOfFirst { it.fileName == recording.fileName } + 1
                "System Log Audio $number"
            }
        }
    }
}

private fun recordingMeta(recording: AudioRecording, format: SimpleDateFormat): String {
    val source = if (recording.source == DiagnosticSource.BUBBLE) "Bubble" else "In-app"
    return "$source · ${recording.pipelineTag} · ${format.format(Date(recording.timestamp))} · ${formatFileSize(recording.sizeBytes)}"
}

private fun formatDurationMs(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.US, "%02d:%02d", minutes, seconds)
}

private fun formatFileSize(bytes: Long): String {
    return when {
        bytes >= 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes.toDouble() / (1024 * 1024))
        bytes >= 1024 -> String.format(Locale.US, "%d KB", bytes / 1024)
        else -> "$bytes B"
    }
}
