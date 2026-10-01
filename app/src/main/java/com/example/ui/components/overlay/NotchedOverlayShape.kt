package com.example.ui.components.overlay

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

class NotchedContainerShape(
    private val cornerRadius: Dp = 24.dp,
    private val notchRadius: Dp = 25.5.dp,
    private val notchCenterOffsetX: Dp = 28.dp,
    private val notchCenterOffsetY: Dp = 2.dp,
    private val filletRadius: Dp = 8.dp
) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density
    ): Outline {
        val path = createNotchedPath(
            size = size,
            density = density,
            cornerRadius = cornerRadius,
            notchRadius = notchRadius,
            notchCenterOffsetX = notchCenterOffsetX,
            notchCenterOffsetY = notchCenterOffsetY,
            filletRadius = filletRadius
        )
        return Outline.Generic(path)
    }
}

typealias NotchedPopupShape = NotchedContainerShape

fun createNotchedPath(
    size: Size,
    density: Density,
    cornerRadius: Dp = 24.dp,
    notchRadius: Dp = 25.5.dp,
    notchCenterOffsetX: Dp = 28.dp,
    notchCenterOffsetY: Dp = 2.dp,
    filletRadius: Dp = 8.dp
): Path {
    val path = Path()
    with(density) {
        val r = cornerRadius.toPx()
        val nr = notchRadius.toPx()
        val frLeft = filletRadius.toPx()
        val frRight = 14.dp.toPx()
        val w = size.width
        val h = size.height
        val cx = w - notchCenterOffsetX.toPx()
        val cy = notchCenterOffsetY.toPx()

        val sumRLeft = nr + frLeft
        val dyLeft = frLeft - cy
        val dxLeft = kotlin.math.sqrt((sumRLeft * sumRLeft - dyLeft * dyLeft).coerceAtLeast(0f))
        val fxLeft = cx - dxLeft
        val alphaRadLeft = kotlin.math.atan2(dyLeft.toDouble(), dxLeft.toDouble())
        val alphaDegLeft = Math.toDegrees(alphaRadLeft).toFloat()

        val offsetRight = (w - cx).coerceAtLeast(0f)
        val sumRRight = nr + frRight
        val dxRight = (offsetRight - frRight).coerceAtLeast(0f)
        val dyRight = kotlin.math.sqrt((sumRRight * sumRRight - dxRight * dxRight).coerceAtLeast(0f))
        val fyRight = cy + dyRight
        val fxRight = w - frRight

        val betaRadRight = kotlin.math.atan2(dyRight.toDouble(), dxRight.toDouble())
        val betaDegRight = Math.toDegrees(betaRadRight).toFloat()

        path.moveTo(r, 0f)

        val leftFilletStartX = fxLeft.coerceIn(r, cx)
        path.lineTo(leftFilletStartX, 0f)

        path.arcTo(
            rect = Rect(fxLeft - frLeft, 0f, fxLeft + frLeft, 2 * frLeft),
            startAngleDegrees = 270f,
            sweepAngleDegrees = 90f - alphaDegLeft,
            forceMoveTo = false
        )

        path.arcTo(
            rect = Rect(cx - nr, cy - nr, cx + nr, cy + nr),
            startAngleDegrees = 180f - alphaDegLeft,
            sweepAngleDegrees = -(180f - alphaDegLeft - betaDegRight),
            forceMoveTo = false
        )

        path.arcTo(
            rect = Rect(fxRight - frRight, fyRight - frRight, fxRight + frRight, fyRight + frRight),
            startAngleDegrees = 180f + betaDegRight,
            sweepAngleDegrees = 180f - betaDegRight,
            forceMoveTo = false
        )

        val rightEdgeStartY = fyRight.coerceIn(r, h - r)
        path.lineTo(w, rightEdgeStartY)
        path.lineTo(w, h - r)

        path.arcTo(
            rect = Rect(w - 2 * r, h - 2 * r, w, h),
            startAngleDegrees = 0f,
            sweepAngleDegrees = 90f,
            forceMoveTo = false
        )

        path.lineTo(r, h)

        path.arcTo(
            rect = Rect(0f, h - 2 * r, 2 * r, h),
            startAngleDegrees = 90f,
            sweepAngleDegrees = 90f,
            forceMoveTo = false
        )

        path.lineTo(0f, r)

        path.arcTo(
            rect = Rect(0f, 0f, 2 * r, 2 * r),
            startAngleDegrees = 180f,
            sweepAngleDegrees = 90f,
            forceMoveTo = false
        )

        path.close()
    }
    return path
}
