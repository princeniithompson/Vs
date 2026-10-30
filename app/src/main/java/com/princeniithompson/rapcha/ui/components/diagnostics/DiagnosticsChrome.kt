package com.princeniithompson.rapcha.ui.components.diagnostics

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Shared visual system for the diagnostics hub and every sub-page.
 * Soft slate canvas, shadowed white surfaces, and a teal ring on explore cards.
 */
object DiagnosticsPalette {
    val slate = Color(0xFFE7EBF1)
    val ink = Color(0xFF1C2430)
    val muted = Color(0xFF6E7787)
    val tealRing = Color(0xFFB7D5C8)
    val teal = Color(0xFF127A68)
    val playFill = Color(0xFF2B323A)
    val playRing = Color(0xFF1A9A72)
    val green = Color(0xFF1E8F4A)
    val greenSoft = Color(0xFFE7F6EE)
    val greenRing = Color(0xFF8FCB9B)
    val greenChip = Color(0xFFF4FBF6)
    val blue = Color(0xFF2F6FE4)
    val blueSoft = Color(0xFFE6F0FF)
    val blueIcon = Color(0xFF2F6BFF)
    val red = Color(0xFFE04B4B)
    val redSoft = Color(0xFFFDECEC)
    val purple = Color(0xFF7A4ED0)
    val purpleSoft = Color(0xFFF3EAFB)
    val purpleIcon = Color(0xFF6B46C1)
    val amber = Color(0xFFB8860B)
    val amberSoft = Color(0xFFFFF4D8)
    val amberIcon = Color(0xFFE0A01A)
    val voiceIcon = Color(0xFF0E8A78)
    val audioIcon = Color(0xFFE3534A)
    val pill = Color(0xFFF2F3F5)
    val track = Color(0xFFD5D8DE)
}

object DiagnosticsShapes {
    val card = RoundedCornerShape(22.dp)
    val tile = RoundedCornerShape(18.dp)
    val control = RoundedCornerShape(16.dp)
    val pill = RoundedCornerShape(50)
}

fun Modifier.premiumSurface(
    shape: Shape = DiagnosticsShapes.card,
    ring: Boolean = true
): Modifier {
    val ringMod = if (ring) {
        Modifier.border(1.25.dp, DiagnosticsPalette.tealRing, shape)
    } else {
        Modifier
    }
    return this
        .shadow(
            elevation = 8.dp,
            shape = shape,
            clip = false,
            ambientColor = Color(0x1A101828),
            spotColor = Color(0x24101828)
        )
        .clip(shape)
        .background(Color.White)
        .then(ringMod)
}

data class DiagnosticsMenuAction(
    val label: String,
    val onClick: () -> Unit,
    val destructive: Boolean = false,
    val testTag: String? = null,
    val icon: ImageVector? = null
)

fun standardDiagnosticsMenu(
    context: Context,
    onExportTxt: () -> Unit,
    onRequestClear: () -> Unit,
    extra: List<DiagnosticsMenuAction> = emptyList()
): List<DiagnosticsMenuAction> {
    return listOf(
        DiagnosticsMenuAction(
            label = "Copy diagnostics",
            onClick = { DiagnosticsExportUtils.copyToClipboard(context) },
            testTag = "menu_copy_logs",
            icon = Icons.Filled.ContentCopy
        ),
        DiagnosticsMenuAction(
            label = "Export .txt",
            onClick = onExportTxt,
            testTag = "menu_export_logs",
            icon = Icons.Filled.Download
        ),
        DiagnosticsMenuAction(
            label = "Clear all diagnostics",
            onClick = onRequestClear,
            destructive = true,
            testTag = "menu_clear_logs",
            icon = Icons.Filled.Delete
        )
    ) + extra
}

@Composable
fun DiagnosticsSquareIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    testTag: String,
    modifier: Modifier = Modifier
) {
    IconButton(
        onClick = onClick,
        modifier = modifier
            .size(52.dp)
            .shadow(
                elevation = 8.dp,
                shape = DiagnosticsShapes.control,
                ambientColor = Color(0x1A101828),
                spotColor = Color(0x2A101828)
            )
            .clip(DiagnosticsShapes.control)
            .background(Color.White)
            .testTag(testTag)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = DiagnosticsPalette.ink,
            modifier = Modifier.size(26.dp)
        )
    }
}

@Composable
fun DiagnosticsLabeledIconButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    testTag: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .widthIn(min = 64.dp)
            .heightIn(min = 64.dp)
            .clip(DiagnosticsShapes.control)
            .clickable(onClick = onClick)
            .testTag(testTag)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = DiagnosticsPalette.ink,
            modifier = Modifier.size(26.dp)
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium,
            color = DiagnosticsPalette.muted,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun DiagnosticsActionMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    actions: List<DiagnosticsMenuAction>
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest
    ) {
        actions.forEach { action ->
            DropdownMenuItem(
                text = {
                    Text(
                        text = action.label,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        color = if (action.destructive) DiagnosticsPalette.red else DiagnosticsPalette.ink
                    )
                },
                onClick = {
                    onDismissRequest()
                    action.onClick()
                },
                leadingIcon = {
                    val icon = action.icon
                    if (icon != null) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = if (action.destructive) DiagnosticsPalette.red else DiagnosticsPalette.ink
                        )
                    }
                },
                modifier = if (action.testTag != null) Modifier.testTag(action.testTag) else Modifier
            )
        }
    }
}

@Composable
fun DiagnosticsStatusChip(
    text: String,
    container: Color,
    content: Color,
    borderColor: Color? = null,
    modifier: Modifier = Modifier
) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = content,
        modifier = modifier
            .then(
                if (borderColor != null) {
                    Modifier.border(1.25.dp, borderColor, DiagnosticsShapes.pill)
                } else {
                    Modifier
                }
            )
            .background(container, DiagnosticsShapes.pill)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    )
}

@Composable
fun DiagnosticsCountBadge(
    text: String,
    container: Color,
    content: Color,
    modifier: Modifier = Modifier
) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        color = content,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .clip(DiagnosticsShapes.pill)
            .background(container)
            .padding(horizontal = 10.dp, vertical = 6.dp)
    )
}

@Composable
fun DiagnosticsActionTile(
    label: String,
    icon: ImageVector,
    background: Color,
    tint: Color,
    onClick: () -> Unit,
    testTag: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .heightIn(min = 92.dp)
            .clip(DiagnosticsShapes.tile)
            .background(background)
            .clickable(onClick = onClick)
            .testTag(testTag)
            .padding(vertical = 16.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(28.dp)
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = DiagnosticsPalette.ink,
            textAlign = TextAlign.Center,
            maxLines = 1
        )
    }
}
