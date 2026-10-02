package com.example.ui.components.overlay.sections

import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.example.ui.components.overlay.AuroraColorPalette
import com.example.ui.components.overlay.FloatingTranscriptBox

/**
 * Text formatting and presentation utilities for the floating dictation transcript.
 */
object FloatingTranscriptFormatter {

    /**
     * Sanitizes and normalizes the raw transcript stream for clean display:
     * - Preserves single spaces and newlines while collapsing excess consecutive spaces
     */
    fun formatTranscriptForDisplay(rawText: String): String {
        if (rawText.isEmpty()) return ""
        return rawText.replace(Regex("[ \\t]{2,}"), " ")
    }

    /**
     * Computes the word count for user statistics or UI metrics.
     */
    fun countWords(text: String): Int {
        if (text.isBlank()) return 0
        return text.trim().split(Regex("\\s+")).size
    }

    /**
     * Computes total character count excluding trailing whitespace.
     */
    fun countCharacters(text: String): Int {
        return text.trimEnd().length
    }
}

/**
 * Renders the transcript text input area with auto-scrolling, high contrast shadows,
 * placeholder state ("Type your message..."), and blinking cursor.
 */
@Composable
fun FloatingTranscriptSection(
    transcriptText: String,
    cursorAlpha: Float,
    palette: AuroraColorPalette,
    scrollState: ScrollState,
    modifier: Modifier = Modifier
) {
    val formattedText = FloatingTranscriptFormatter.formatTranscriptForDisplay(transcriptText)

    FloatingTranscriptBox(
        transcriptText = formattedText,
        cursorAlpha = cursorAlpha,
        palette = palette,
        scrollState = scrollState,
        modifier = modifier
    )
}
