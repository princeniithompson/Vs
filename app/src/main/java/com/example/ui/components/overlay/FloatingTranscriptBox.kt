package com.example.ui.components.overlay

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Left-aligned text input area with auto-scrolling, high contrast shadows,
 * placeholder state ("Type your message..."), and blinking cursor.
 */
@Composable
fun FloatingTranscriptBox(
    transcriptText: String,
    cursorAlpha: Float,
    palette: AuroraColorPalette,
    scrollState: ScrollState,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 38.dp, max = 110.dp)
            .padding(end = 40.dp)
            .verticalScroll(scrollState)
    ) {
        if (transcriptText.isBlank()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .width(2.dp)
                        .height(18.dp)
                        .background(palette.primaryVibrant.copy(alpha = cursorAlpha))
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Type your message...",
                    style = TextStyle(
                        color = Color(0xB3CBD5E1),
                        fontSize = 15.sp,
                        fontFamily = FontFamily.SansSerif,
                        fontWeight = FontWeight.Normal,
                        shadow = Shadow(
                            color = Color(0xCC000000),
                            blurRadius = 4f
                        )
                    ),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top
            ) {
                Text(
                    text = transcriptText,
                    style = TextStyle(
                        color = Color(0xFFFFFFFF),
                        fontSize = 15.sp,
                        fontFamily = FontFamily.SansSerif,
                        fontWeight = FontWeight.Normal,
                        lineHeight = 21.sp,
                        shadow = Shadow(
                            color = Color(0xE6000000),
                            offset = Offset(0f, 1f),
                            blurRadius = 6f
                        )
                    ),
                    modifier = Modifier.weight(1f, fill = false)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Box(
                    modifier = Modifier
                        .width(2.dp)
                        .height(18.dp)
                        .background(palette.primaryVibrant.copy(alpha = cursorAlpha))
                )
            }
        }
    }
}
