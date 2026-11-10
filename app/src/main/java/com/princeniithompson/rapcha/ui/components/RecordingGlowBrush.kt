package com.princeniithompson.rapcha.ui.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

fun DrawScope.drawRecordingGlow(glow: RecordingGlow, deep: Boolean) {
    drawRect(Color(RecordingGlowColors.BUBBLE))
    drawRect(glowBrush(glow, deep, size))
    drawRect(
        brush = Brush.verticalGradient(
            0f to Color(RecordingGlowColors.BUBBLE),
            0.30f to Color(RecordingGlowColors.BUBBLE),
            0.55f to Color.Transparent
        )
    )
}

fun glowBrush(glow: RecordingGlow, deep: Boolean, size: Size): Brush {
    val stops = glow.stops(deep).map { (stop, color) -> stop to Color(color) }.toTypedArray()
    return when (glow.kind) {
        GlowBrushKind.VERTICAL -> Brush.verticalGradient(colorStops = stops)
        GlowBrushKind.RADIAL -> Brush.radialGradient(
            colorStops = stops,
            center = Offset(size.width / 2f, size.height),
            radius = hypot(size.width.toDouble(), size.height.toDouble()).toFloat()
        )
        GlowBrushKind.LINEAR -> {
            val rad = Math.toRadians(glow.angleDegrees.toDouble())
            val len = hypot(size.width.toDouble(), size.height.toDouble()).toFloat() / 2f
            val cx = size.width / 2f
            val cy = size.height / 2f
            Brush.linearGradient(
                colorStops = stops,
                start = Offset(cx - cos(rad).toFloat() * len, cy - sin(rad).toFloat() * len),
                end = Offset(cx + cos(rad).toFloat() * len, cy + sin(rad).toFloat() * len)
            )
        }
    }
}

fun DrawScope.drawLifebuoy(diameter: Float) {
    val sweep = 360f / 16f
    for (index in 0 until 16) {
        drawArc(
            color = if (index % 2 == 0) Color(0xFFE8281C) else Color.White,
            startAngle = index * sweep - 90f,
            sweepAngle = sweep,
            useCenter = true,
            size = Size(diameter, diameter)
        )
    }
    drawCircle(
        color = Color(0xFF1A1F1A),
        radius = diameter * 0.28f,
        center = Offset(diameter / 2f, diameter / 2f)
    )
}
