package com.princeniithompson.rapcha.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.princeniithompson.rapcha.service.floating.DynamicTonePolicy
import com.princeniithompson.rapcha.ui.components.overlay.sections.rememberNotchedContainerShape

/**
 * Settings preview of the original bubble. Fixed outline, no transcript.
 * The only words are Cancel and Polish.
 */
object WordsBubblePreviewSpec {
    const val HEIGHT_DP = 168
    val labels = listOf("Cancel", "Polish")
}

@Composable
fun WordsBubblePreview(
    glowStyleId: String,
    tone: String = DynamicTonePolicy.LUMINOUS,
    modifier: Modifier = Modifier
) {
    val glow = RecordingGlowList.byId(glowStyleId)
    val deep = tone == DynamicTonePolicy.DEEP
    val shape = rememberNotchedContainerShape()
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(WordsBubblePreviewSpec.HEIGHT_DP.dp)
            .testTag("words-bubble-preview")
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 22.dp)
                .height((WordsBubblePreviewSpec.HEIGHT_DP - 22).dp)
                .clip(shape)
                .drawBehind { drawRecordingGlow(glow, deep) }
        ) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 12.dp, bottom = 10.dp)
                    .height(38.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                PreviewPill(
                    fill = Color(0x66000000),
                    border = Color(0x80FF8C78),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = null,
                        tint = Color(RecordingGlowColors.CANCEL_TEXT),
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Cancel",
                        color = Color(RecordingGlowColors.CANCEL_TEXT),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
                PreviewPill(
                    fill = Color(RecordingGlowColors.POLISH),
                    border = Color.Transparent,
                    modifier = Modifier.weight(1.4f)
                ) {
                    Icon(
                        imageVector = Icons.Filled.AutoAwesome,
                        contentDescription = null,
                        tint = Color(RecordingGlowColors.POLISH_TEXT),
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Polish",
                        color = Color(RecordingGlowColors.POLISH_TEXT),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
        Canvas(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 10.dp, end = 10.dp)
                .size(36.dp)
        ) {
            drawLifebuoy(size.minDimension)
        }
    }
}

@Composable
private fun PreviewPill(
    fill: Color,
    border: Color,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val shape = RoundedCornerShape(percent = 50)
    Row(
        modifier = modifier
            .fillMaxSize()
            .clip(shape)
            .drawBehind { drawRect(fill) }
            .then(if (border == Color.Transparent) Modifier else Modifier.border(1.dp, border, shape)),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        content()
    }
}
