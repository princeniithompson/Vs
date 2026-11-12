package com.princeniithompson.rapcha.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.princeniithompson.rapcha.service.floating.DynamicTonePolicy

private val barHeights = listOf(10, 18, 26, 14, 30, 22, 12, 28, 16, 24, 10)

@Composable
private fun StaticWaveBar(height: Int) {
    WaveBar(height * 0.7f)
}

@Composable
private fun AnimatedWaveBar(index: Int, height: Int) {
    val motion = rememberInfiniteTransition(label = "bar$index")
    val phase by motion.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(durationMillis = 1000, delayMillis = index * 90, easing = LinearEasing),
            RepeatMode.Reverse
        ),
        label = "h"
    )
    WaveBar(height * phase)
}

@Composable
private fun WaveBar(heightDp: Float) {
    Box(
        modifier = Modifier
            .width(3.dp)
            .height(heightDp.dp)
            .clip(RoundedCornerShape(2.dp))
            .drawBehind { drawRect(Color.White) }
    )
}

@Composable
fun MiniBubblePreview(
    glowStyleId: String,
    tone: String,
    animate: Boolean,
    modifier: Modifier = Modifier
) {
    val glow = RecordingGlowList.byId(glowStyleId)
    val deep = tone == DynamicTonePolicy.DEEP
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(46.dp)
                .shadow(8.dp, CircleShape, ambientColor = Color(0x33000000), spotColor = Color(0x33000000))
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(20.dp)
            )
        }
        Box(
            modifier = Modifier
                .width(150.dp)
                .height(46.dp)
                .shadow(8.dp, RoundedCornerShape(23.dp))
                .clip(RoundedCornerShape(23.dp))
                .drawBehind { drawRecordingGlow(glow, deep) },
            contentAlignment = Alignment.Center
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(3.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                barHeights.forEachIndexed { index, height ->
                    if (animate) AnimatedWaveBar(index, height) else StaticWaveBar(height)
                }
            }
        }
        Box(
            modifier = Modifier
                .size(46.dp)
                .shadow(8.dp, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawLifebuoy(size.minDimension)
            }
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}
