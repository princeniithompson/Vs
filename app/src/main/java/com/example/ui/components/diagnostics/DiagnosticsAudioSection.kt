package com.example.ui.components.diagnostics

import android.media.MediaPlayer
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.AudioRecording
import com.example.data.AudioRecordingRepository
import com.example.data.DiagnosticSource
import kotlinx.coroutines.delay
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Audio Page: Focused purely on audio files, waveforms, playback controls, and export.
 * Formatted with clean white surfaces matching the app theme.
 */
@Composable
fun DiagnosticsAudioSection(
    recordings: List<AudioRecording>,
    isDictating: Boolean,
    onDeleteRecording: (AudioRecording) -> Unit,
    onClearAllRecordings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var showClearConfirmation by remember { mutableStateOf(false) }

    var playingFile by remember { mutableStateOf<File?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var currentPositionMs by remember { mutableIntStateOf(0) }
    var totalDurationMs by remember { mutableIntStateOf(0) }

    val mediaPlayer = remember { MediaPlayer() }

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

    Column(modifier = modifier.fillMaxSize()) {
        // Privacy Note Banner
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFFF0FDFA)),
            border = BorderStroke(1.dp, Color(0xFFCCFBF1)),
            shape = RoundedCornerShape(14.dp)
        ) {
            Row(
                modifier = Modifier.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Filled.Lock,
                    contentDescription = null,
                    tint = Color(0xFF0F766E),
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "Privacy Note: Audio recordings stay strictly on your phone. Nothing is uploaded or shared unless you explicitly tap Share or Downloads.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF134E4A),
                    lineHeight = 16.sp
                )
            }
        }

        // Top Toolbar: Storage count + Clear All button
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            val totalBytes = recordings.sumOf { it.sizeBytes }
            val formattedTotalSize = formatFileSize(totalBytes)
            Text(
                text = "${recordings.size} audio recordings ($formattedTotalSize)",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFF475569)
            )

            if (recordings.isNotEmpty()) {
                TextButton(
                    onClick = { showClearConfirmation = true },
                    colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFDC2626))
                ) {
                    Icon(
                        imageVector = Icons.Filled.DeleteSweep,
                        contentDescription = "Clear all recordings",
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Clear All", fontWeight = FontWeight.SemiBold)
                }
            }
        }

        if (recordings.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Filled.GraphicEq,
                        contentDescription = null,
                        tint = Color(0xFF94A3B8),
                        modifier = Modifier.size(56.dp)
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        text = "No saved audio recordings yet",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF0F172A)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Voice recordings from the app or floating bubble will appear here.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF64748B)
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(recordings, key = { it.fileName }) { recording ->
                    val isThisPlaying = playingFile == recording.file && isPlaying

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isThisPlaying) Color(0xFFF0FDFA) else Color.White
                        ),
                        border = BorderStroke(
                            width = if (isThisPlaying) 1.5.dp else 1.dp,
                            color = if (isThisPlaying) Color(0xFF006874) else Color(0xFFE2E8F0)
                        )
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            // Row 1: Source badge, Timestamp, Duration & File Size
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    val isBubble = recording.source == DiagnosticSource.BUBBLE
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(if (isBubble) Color(0xFFF3E8FF) else Color(0xFFCCFBF1))
                                            .padding(horizontal = 8.dp, vertical = 3.dp)
                                    ) {
                                        Text(
                                            text = recording.source.name,
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = if (isBubble) Color(0xFF7E22CE) else Color(0xFF0F766E)
                                        )
                                    }

                                    Spacer(modifier = Modifier.width(10.dp))

                                    Text(
                                        text = dateTimeFormat.format(Date(recording.timestamp)),
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Medium,
                                        color = Color(0xFF0F172A)
                                    )
                                }

                                Text(
                                    text = "${formatDurationMs(recording.durationMs)} · ${formatFileSize(recording.sizeBytes)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color(0xFF64748B)
                                )
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            // Mini Audio Waveform & Player Bar
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color(0xFFF8FAFC))
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                IconButton(
                                    onClick = { playAudio(recording.file) },
                                    enabled = !isDictating,
                                    modifier = Modifier
                                        .size(40.dp)
                                        .background(Color(0xFF006874), CircleShape)
                                ) {
                                    Icon(
                                        imageVector = if (isThisPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                        contentDescription = if (isThisPlaying) "Pause" else "Play",
                                        tint = Color.White,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }

                                Column(modifier = Modifier.weight(1f).padding(horizontal = 10.dp)) {
                                    val currentMs = if (playingFile == recording.file) currentPositionMs else 0
                                    val maxMs = if (playingFile == recording.file && totalDurationMs > 0) totalDurationMs else recording.durationMs.toInt().coerceAtLeast(1)

                                    Slider(
                                        value = currentMs.toFloat().coerceIn(0f, maxMs.toFloat()),
                                        onValueChange = { targetPos ->
                                            if (playingFile == recording.file) {
                                                seekTo(targetPos.toInt())
                                            }
                                        },
                                        valueRange = 0f..maxMs.toFloat(),
                                        colors = SliderDefaults.colors(
                                            thumbColor = Color(0xFF006874),
                                            activeTrackColor = Color(0xFF006874),
                                            inactiveTrackColor = Color(0xFFCBD5E1)
                                        ),
                                        modifier = Modifier.height(24.dp)
                                    )

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = formatDurationMs(currentMs.toLong()),
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = Color(0xFF64748B)
                                        )
                                        Text(
                                            text = formatDurationMs(maxMs.toLong()),
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = Color(0xFF64748B)
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            // Action buttons: Share, Save to Downloads, Delete
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedButton(
                                    onClick = {
                                        AudioRecordingRepository.shareRecording(context, recording)
                                    },
                                    modifier = Modifier.height(36.dp),
                                    shape = RoundedCornerShape(10.dp),
                                    border = BorderStroke(1.dp, Color(0xFFCBD5E1)),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                                ) {
                                    Icon(Icons.Filled.Share, contentDescription = "Share", tint = Color(0xFF006874), modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Share", fontSize = 12.sp, color = Color(0xFF006874), fontWeight = FontWeight.SemiBold)
                                }

                                Spacer(modifier = Modifier.width(8.dp))

                                OutlinedButton(
                                    onClick = {
                                        val success = AudioRecordingRepository.saveToDownloads(context, recording)
                                        if (success) {
                                            Toast.makeText(context, "Saved to Downloads folder", Toast.LENGTH_SHORT).show()
                                        } else {
                                            Toast.makeText(context, "Failed to save to Downloads", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    modifier = Modifier.height(36.dp),
                                    shape = RoundedCornerShape(10.dp),
                                    border = BorderStroke(1.dp, Color(0xFFCBD5E1)),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                                ) {
                                    Icon(Icons.Filled.Download, contentDescription = "Save to Downloads", tint = Color(0xFF006874), modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Downloads", fontSize = 12.sp, color = Color(0xFF006874), fontWeight = FontWeight.SemiBold)
                                }

                                Spacer(modifier = Modifier.width(8.dp))

                                IconButton(
                                    onClick = { onDeleteRecording(recording) },
                                    modifier = Modifier
                                        .size(36.dp)
                                        .background(Color(0xFFFEE2E2), RoundedCornerShape(8.dp))
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Delete,
                                        contentDescription = "Delete recording",
                                        tint = Color(0xFFDC2626),
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Confirmation Dialog for Clear All
    if (showClearConfirmation) {
        AlertDialog(
            onDismissRequest = { showClearConfirmation = false },
            title = {
                Text(
                    text = "Delete all saved audio?",
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF0F172A)
                )
            },
            text = {
                Text(
                    text = "This will permanently delete all local audio files from your device. This cannot be undone.",
                    color = Color(0xFF475569)
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearConfirmation = false
                        onClearAllRecordings()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFDC2626))
                ) {
                    Text("Delete All", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirmation = false }) {
                    Text("Cancel", color = Color(0xFF64748B))
                }
            },
            containerColor = Color.White,
            shape = RoundedCornerShape(16.dp)
        )
    }
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
