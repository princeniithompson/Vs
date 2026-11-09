package com.princeniithompson.rapcha.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.princeniithompson.rapcha.data.HistoryRepository
import com.princeniithompson.rapcha.data.LyraRating
import com.princeniithompson.rapcha.data.LyraReviewPolicy
import com.princeniithompson.rapcha.data.LyraReviewTag
import com.princeniithompson.rapcha.data.VoiceSessionRecord

@Composable
fun LyraHistoryStages(record: VoiceSessionRecord) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
        if (!record.rawNote.isNullOrBlank()) {
            Text(
                text = record.rawNote,
                fontSize = 12.sp,
                color = Color(0xFF92400E),
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }
        record.stages.forEach { stage ->
            Text(
                text = stage.label,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFF334155)
            )
            Text(
                text = stage.text,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                color = Color(0xFF1A202C),
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }
        if (!record.reviewable) return@Column
        val badge = when (record.rating) {
            LyraRating.UP -> "Up"
            LyraRating.DOWN -> "Down"
            LyraRating.UNRATED -> "Unrated"
        }
        Text(
            text = badge,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = if (record.rating == LyraRating.UNRATED) Color(0xFFB45309) else Color(0xFF1D4ED8),
            modifier = Modifier.padding(bottom = 6.dp)
        )
        var rating by remember(record.sessionId, record.rating) { mutableStateOf(record.rating) }
        var tags by remember(record.sessionId, record.tags) { mutableStateOf(record.tags.toSet()) }
        var comment by remember(record.sessionId, record.comment) { mutableStateOf(record.comment) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { rating = LyraRating.UP }) { Text("Up") }
            TextButton(onClick = { rating = LyraRating.DOWN }) { Text("Down") }
        }
        if (rating == LyraRating.DOWN) {
            Text(
                text = LyraReviewPolicy.DOWN_PROMPT,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = Color(0xFF1A202C),
                modifier = Modifier.padding(bottom = 4.dp)
            )
            LyraReviewTag.entries.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEach { tag ->
                        FilterChip(
                            selected = tag in tags,
                            onClick = {
                                tags = if (tag in tags) tags - tag else tags + tag
                            },
                            label = { Text(tag.label, fontSize = 12.sp) }
                        )
                    }
                }
            }
        }
        if (rating == LyraRating.UP || rating == LyraRating.DOWN) {
            OutlinedTextField(
                value = comment,
                onValueChange = { comment = it },
                modifier = Modifier.fillMaxWidth(),
                label = {
                    Text(if (rating == LyraRating.DOWN) LyraReviewPolicy.DOWN_PROMPT else "Optional comment")
                },
                shape = RoundedCornerShape(12.dp)
            )
            TextButton(onClick = {
                HistoryRepository.updateLyraReview(record.sessionId, rating, tags.toList(), comment)
            }) {
                Text("Save rating")
            }
        }
    }
}
