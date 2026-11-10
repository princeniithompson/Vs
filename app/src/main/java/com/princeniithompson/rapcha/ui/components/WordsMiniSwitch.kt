package com.princeniithompson.rapcha.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.princeniithompson.rapcha.service.floating.BubbleChromeStyle

/**
 * One raised Words/Mini control. The checkered half is the saved bubble.
 * It does not move the preview page.
 */
@Composable
fun WordsMiniSwitch(
    active: BubbleChromeStyle,
    onChoose: (BubbleChromeStyle) -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(24.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .shadow(2.dp, shape)
            .clip(shape)
            .background(Color(0xFFC5E1A5))
            .border(2.dp, Color.White, shape)
            .padding(3.dp)
    ) {
        SwitchHalf(
            label = "Words",
            selected = active == BubbleChromeStyle.ORIGINAL,
            onClick = { onChoose(BubbleChromeStyle.ORIGINAL) },
            modifier = Modifier.weight(1f)
        )
        SwitchHalf(
            label = "Mini",
            selected = active == BubbleChromeStyle.COMPACT,
            onClick = { onChoose(BubbleChromeStyle.COMPACT) },
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun SwitchHalf(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(20.dp)
    Box(
        modifier = modifier
            .fillMaxHeight()
            .then(if (selected) Modifier.shadow(6.dp, shape) else Modifier)
            .clip(shape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (selected) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val cell = 8.dp.toPx()
                val light = Color(0xFF9CCC65)
                val dark = Color(0xFF7CB342)
                var row = 0
                var y = 0f
                while (y < size.height) {
                    var col = 0
                    var x = 0f
                    while (x < size.width) {
                        drawRect(
                            color = if ((row + col) % 2 == 0) light else dark,
                            topLeft = Offset(x, y),
                            size = androidx.compose.ui.geometry.Size(cell, cell)
                        )
                        x += cell
                        col++
                    }
                    y += cell
                    row++
                }
                drawRect(
                    color = Color(0xFFDCEDC8),
                    size = androidx.compose.ui.geometry.Size(size.width, 3.dp.toPx())
                )
            }
        } else {
            Box(modifier = Modifier.fillMaxSize().background(Color(0xFFAED581)))
        }
        Text(
            text = label,
            color = if (selected) Color(0xFF1B3A1A) else Color(0xFF2E4A28),
            fontWeight = FontWeight.SemiBold,
            fontSize = 15.sp
        )
    }
}
