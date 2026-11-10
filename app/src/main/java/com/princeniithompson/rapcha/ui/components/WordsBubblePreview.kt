package com.princeniithompson.rapcha.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.princeniithompson.rapcha.R
import com.princeniithompson.rapcha.ui.components.overlay.PillActionButton
import com.princeniithompson.rapcha.ui.components.overlay.isColorDark
import com.princeniithompson.rapcha.ui.components.overlay.rememberDynamicAuroraPalette
import com.princeniithompson.rapcha.ui.components.overlay.sections.FloatingAuroraGlowSection
import com.princeniithompson.rapcha.ui.components.overlay.sections.FloatingDictationPopupState
import com.princeniithompson.rapcha.ui.components.overlay.sections.rememberNotchedContainerShape

/**
 * Settings preview of the original bubble. It is not the live recording surface.
 * Fixed height, no transcript, labels are only Cancel and Polish.
 */
object WordsBubblePreviewSpec {
    const val HEIGHT_DP = 168
    val labels = listOf("Cancel", "Polish")
}

@Composable
fun WordsBubblePreview(
    glowStyleId: String,
    modifier: Modifier = Modifier
) {
    val palette = rememberDynamicAuroraPalette()
    val scrollState = rememberScrollState()
    val state = remember(scrollState) {
        FloatingDictationPopupState(
            glowPulse = 1f,
            phase1 = 0f,
            phase2 = 0f,
            animatedAmplitude = 0f,
            idleBreathing = 0.12f,
            cursorAlpha = 0f,
            morphProgress = 0f,
            sunsetEntranceProgress = 1f,
            waitingLoopProgress = 0f,
            isFinalizing = false,
            isPolishing = false,
            scrollState = scrollState,
            onLocalCompleteTriggered = {}
        )
    }
    val shape = rememberNotchedContainerShape()
    val onPolish = if (isColorDark(palette.primaryVibrant)) Color.White else Color(0xFF042F2E)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(WordsBubblePreviewSpec.HEIGHT_DP.dp)
            .testTag("words-bubble-preview")
    ) {
        FloatingAuroraGlowSection(
            notchedShape = shape,
            palette = palette,
            glowStyleId = glowStyleId,
            state = state,
            isRecording = false,
            modifier = Modifier
                .fillMaxWidth()
                .height(WordsBubblePreviewSpec.HEIGHT_DP.dp)
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(36.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    PillActionButton(
                        onClick = {},
                        backgroundColor = Color(0x381E1E24),
                        borderColor = Color(0x26FF5252),
                        modifier = Modifier.weight(1f)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = "Cancel",
                                tint = Color(0xFFFF5252),
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(5.dp))
                            Text(
                                text = "Cancel",
                                color = Color(0xFFFF7B7B),
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    PillActionButton(
                        onClick = {},
                        backgroundColor = palette.primaryVibrant,
                        borderColor = Color.Transparent,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = "Polish",
                            color = onPolish,
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(end = 4.dp)
                .size(48.dp)
                .clip(CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = painterResource(id = R.drawable.ic_lifebuoy_ring),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(46.dp)
            )
        }
    }
}
