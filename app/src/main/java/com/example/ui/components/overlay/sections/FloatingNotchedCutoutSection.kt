package com.example.ui.components.overlay.sections

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.ui.components.overlay.FloatingDockedLifebuoy
import com.example.ui.components.overlay.NotchedContainerShape

object FloatingNotchedDimensions {
    val CornerRadius = 24.dp
    val NotchRadius = 25.5.dp
    val NotchCenterOffsetX = 28.dp
    val NotchCenterOffsetY = 2.dp
    val FilletRadius = 8.dp
}

@Composable
fun rememberNotchedContainerShape(
    cornerRadius: Dp = FloatingNotchedDimensions.CornerRadius,
    notchRadius: Dp = FloatingNotchedDimensions.NotchRadius,
    notchCenterOffsetX: Dp = FloatingNotchedDimensions.NotchCenterOffsetX,
    notchCenterOffsetY: Dp = FloatingNotchedDimensions.NotchCenterOffsetY,
    filletRadius: Dp = FloatingNotchedDimensions.FilletRadius
): NotchedContainerShape {
    return remember(cornerRadius, notchRadius, notchCenterOffsetX, notchCenterOffsetY, filletRadius) {
        NotchedContainerShape(
            cornerRadius = cornerRadius,
            notchRadius = notchRadius,
            notchCenterOffsetX = notchCenterOffsetX,
            notchCenterOffsetY = notchCenterOffsetY,
            filletRadius = filletRadius
        )
    }
}

/**
 * Hosts the unclipped docked lifebuoy ring nested perfectly inside the top-right cutout notch.
 */
@Composable
fun FloatingNotchedCutoutSection(
    onLifebuoyClick: () -> Unit,
    onDragStart: () -> Unit = {},
    onDrag: (Float, Float) -> Unit = { _, _ -> },
    onDragEnd: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    FloatingDockedLifebuoy(
        onLifebuoyClick = onLifebuoyClick,
        onDragStart = onDragStart,
        onDrag = onDrag,
        onDragEnd = onDragEnd,
        modifier = modifier
    )
}
