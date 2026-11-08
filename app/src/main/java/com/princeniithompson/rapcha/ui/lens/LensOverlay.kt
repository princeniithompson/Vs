package com.princeniithompson.rapcha.ui.lens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.princeniithompson.rapcha.service.lens.LensAction
import com.princeniithompson.rapcha.service.lens.LensPhase
import com.princeniithompson.rapcha.service.lens.LensUiState
import com.princeniithompson.rapcha.service.lens.TonePreset

@Composable
fun LensOverlay(
    state: LensUiState,
    onDismiss: () -> Unit,
    onAction: (LensAction) -> Unit,
    onOption: (LensAction, String) -> Unit,
    onCopy: () -> Unit,
    onRegenerate: () -> Unit,
    onDown: () -> Unit,
    onInsert: () -> Unit
) {
    if (state.phase == LensPhase.HIDDEN) return
    Box(modifier = Modifier.fillMaxSize()) {
        if (state.phase != LensPhase.DRAGGING) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(onClick = onDismiss)
            )
        }
        val density = LocalDensity.current
        val highlight = state.highlight
        if (highlight != null) {
            Box(
                modifier = Modifier
                    .offset { IntOffset(highlight.left, highlight.top) }
                    .size(
                        width = with(density) { (highlight.right - highlight.left).coerceAtLeast(1).toDp() },
                        height = with(density) { (highlight.bottom - highlight.top).coerceAtLeast(1).toDp() }
                    )
                    .background(Color(0x332979FF))
                    .border(2.dp, Color(0xFF2979FF), RoundedCornerShape(4.dp))
            )
        }
        if (state.phase == LensPhase.DRAGGING) {
            Box(
                modifier = Modifier
                    .offset { IntOffset((state.centerX - 36f).toInt(), (state.centerY - 36f).toInt()) }
                    .size(72.dp)
                    .clip(CircleShape)
                    .border(3.dp, Color(0xFF2979FF), CircleShape)
                    .background(Color(0x332979FF))
            )
        }
        if (state.phase == LensPhase.ACTIONS || state.phase == LensPhase.GENERATING ||
            state.phase == LensPhase.RESULT || state.phase == LensPhase.ERROR
        ) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 12.dp, vertical = 28.dp)
                    .clickable(onClick = {})
            ) {
                if (state.phase != LensPhase.ACTIONS) {
                    ResultCard(state, onDismiss, onCopy, onRegenerate, onDown, onInsert)
                    Spacer(modifier = Modifier.height(8.dp))
                }
                if (state.action == LensAction.TRANSLATE) {
                    ChipRow(
                        items = listOf("🇬🇧 English", "🇪🇸 Spanish", "🇫🇷 French", "🇩🇪 German"),
                        selected = state.option
                    ) { label ->
                        val language = label.substringAfter(' ')
                        onOption(LensAction.TRANSLATE, language)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }
                if (state.action == LensAction.REPLY) {
                    ChipRow(listOf("Short", "Medium", "Long"), state.option) {
                        onOption(LensAction.REPLY, it)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }
                if (state.action == LensAction.TONE) {
                    ChipRow(
                        state.tones.map { "${it.emoji} ${it.name}".trim() },
                        state.option
                    ) { label ->
                        val name = label.substringAfter(' ').ifBlank { label }
                        onOption(LensAction.TONE, name)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }
                ActionBar(state.action, onAction)
            }
        }
    }
}

@Composable
private fun ActionBar(selected: LensAction?, onAction: (LensAction) -> Unit) {
    val items = listOf(
        LensAction.GRAMMAR to "Grammar",
        LensAction.TRANSLATE to "Translate",
        LensAction.REPLY to "Reply",
        LensAction.TONE to "Tone",
        LensAction.ASK to "Ask",
        LensAction.POLISH to "Polish"
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(Color(0xF21C1C1E))
            .horizontalScroll(rememberScrollState())
            .padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        items.forEach { (action, label) ->
            val on = selected == action
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (on) Color(0xFF0A84FF) else Color(0xFF2C2C2E))
                    .clickable { onAction(action) }
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Text(label, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun ChipRow(items: List<String>, selected: String, onPick: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(Color(0xF21C1C1E))
            .horizontalScroll(rememberScrollState())
            .padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        items.forEach { item ->
            val name = item.substringAfter(' ').ifBlank { item }
            val on = selected.equals(name, ignoreCase = true) || selected.equals(item, ignoreCase = true)
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (on) Color(0xFF3A3A3C) else Color(0xFF2C2C2E))
                    .clickable { onPick(item) }
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                Text(item, color = Color.White, fontSize = 15.sp)
            }
        }
    }
}

@Composable
private fun ResultCard(
    state: LensUiState,
    onDismiss: () -> Unit,
    onCopy: () -> Unit,
    onRegenerate: () -> Unit,
    onDown: () -> Unit,
    onInsert: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(Color(0xFF1C1C1E))
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("R", color = Color(0xFF4DA3FF), fontWeight = FontWeight.Bold, fontSize = 22.sp)
            Spacer(modifier = Modifier.weight(1f))
            Icon(
                Icons.Filled.Close,
                contentDescription = "Close",
                tint = Color.White,
                modifier = Modifier
                    .size(22.dp)
                    .clickable(onClick = onDismiss)
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .border(
                    1.5.dp,
                    Brush.linearGradient(listOf(Color(0xFF2DE2E6), Color(0xFF2979FF))),
                    RoundedCornerShape(16.dp)
                )
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            when (state.phase) {
                LensPhase.GENERATING -> {
                    Text("✦", color = Color(0xFF2DE2E6), fontSize = 22.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Generating", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                    Text("Please wait, your text is coming", color = Color(0xFF9AA0A6), fontSize = 16.sp)
                }
                LensPhase.ERROR -> {
                    Text(state.error.ifBlank { "Lens could not finish." }, color = Color(0xFFFF8A80), fontSize = 16.sp)
                }
                else -> {
                    if (state.resultTitle.isNotBlank()) {
                        Text(state.resultTitle, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                        Spacer(modifier = Modifier.height(6.dp))
                    }
                    Text(state.resultText, color = Color(0xFFE8EAED), fontSize = 16.sp)
                }
            }
        }
        if (state.phase == LensPhase.RESULT || state.phase == LensPhase.ERROR) {
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Icon(Icons.Filled.Refresh, "Regenerate", tint = Color(0xFF4DA3FF), modifier = Modifier.clickable(onClick = onRegenerate))
                Icon(Icons.Filled.ContentCopy, "Copy", tint = Color(0xFF4DA3FF), modifier = Modifier.clickable(onClick = onCopy))
                Icon(Icons.Filled.ThumbDown, "Not helpful", tint = Color(0xFF4DA3FF), modifier = Modifier.clickable(onClick = onDown))
                if (state.editable && state.phase == LensPhase.RESULT) {
                    Text(
                        "Insert",
                        color = Color(0xFF4DA3FF),
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clickable(onClick = onInsert)
                    )
                }
            }
        }
    }
}
