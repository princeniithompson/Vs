package com.princeniithompson.rapcha.ui.components.diagnostics

import android.content.Context
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalOverscrollConfiguration
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import com.princeniithompson.rapcha.data.DiagnosticLogEntry

/**
 * Neutral diagnostics surfaces. Color is reserved for status: ok, warning, danger.
 * Light and dark use the same roles.
 */
data class DiagnosticsColors(
    val background: Color,
    val surface: Color,
    val surfaceRaised: Color,
    val border: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val accent: Color,
    val onAccent: Color,
    val statusOk: Color,
    val statusWarn: Color,
    val statusError: Color
) {
    val canvas: Color get() = background
    val ink: Color get() = textPrimary
    val secondary: Color get() = textSecondary
    val line: Color get() = border
    val ok: Color get() = statusOk
    val warning: Color get() = statusWarn
    val danger: Color get() = statusError
}

private val LightDiagnosticsColors = DiagnosticsColors(
    background = Color(0xFFF4F5F7),
    surface = Color(0xFFFFFFFF),
    surfaceRaised = Color(0xFFFFFFFF),
    border = Color(0xFFD5D8DE),
    textPrimary = Color(0xFF1A1C1F),
    textSecondary = Color(0xFF4E5560),
    accent = Color(0xFF0E6B52),
    onAccent = Color(0xFFFFFFFF),
    statusOk = Color(0xFF146C36),
    statusWarn = Color(0xFF8A5A00),
    statusError = Color(0xFFB42318)
)

private val DarkDiagnosticsColors = DiagnosticsColors(
    background = Color(0xFF0E0F12),
    surface = Color(0xFF1A1C20),
    surfaceRaised = Color(0xFF26292E),
    border = Color(0xFF3A3F46),
    textPrimary = Color(0xFFF4F5F7),
    textSecondary = Color(0xFFC5CAD3),
    accent = Color(0xFF3DDC97),
    onAccent = Color(0xFF062015),
    statusOk = Color(0xFF7DDEA0),
    statusWarn = Color(0xFFF5C16C),
    statusError = Color(0xFFFF8A80)
)

val LocalDiagnosticsColors = staticCompositionLocalOf { LightDiagnosticsColors }

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ProvideDiagnosticsColors(content: @Composable () -> Unit) {
    val systemDark = isSystemInDarkTheme()
    val schemeDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val colors = if (systemDark || schemeDark) DarkDiagnosticsColors else LightDiagnosticsColors
    CompositionLocalProvider(LocalDiagnosticsColors provides colors) {
        @Suppress("DEPRECATION")
        CompositionLocalProvider(LocalOverscrollConfiguration provides null) {
            content()
        }
    }
}

@Composable
fun diagnosticsColors(): DiagnosticsColors = LocalDiagnosticsColors.current

/** Theme colors for composables in this package. */
val colors: DiagnosticsColors
    @Composable get() = diagnosticsColors()

/** Empty counts read as "None". A positive count is the number alone. */
fun diagnosticsCountText(count: Int): String = if (count <= 0) "None" else count.toString()

/** Day groups are a partition of [entries], so their sizes add up to the list size. */
fun groupedHistoryCount(entries: List<DiagnosticLogEntry>): Int {
    return DiagnosticsEventTracker.buildLogHistory(entries).values.sumOf { it.size }
}

private object ConsumeOverscrollConnection : NestedScrollConnection {
    override fun onPostScroll(
        consumed: Offset,
        available: Offset,
        source: NestedScrollSource
    ): Offset = available

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity = available
}

/** Leftover scroll and fling stay in the list. They are not handed to the sheet. */
fun Modifier.diagnosticsNestedScrollGuard(): Modifier = this.nestedScroll(ConsumeOverscrollConnection)

private val ToolShape = RoundedCornerShape(8.dp)

@Composable
fun Modifier.premiumSurface(ring: Boolean = true): Modifier {
    val colors = diagnosticsColors()
    return this
        .clip(ToolShape)
        .background(colors.surface)
        .border(1.dp, colors.border, ToolShape)
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
    val colors = diagnosticsColors()
    IconButton(
        onClick = onClick,
        modifier = modifier
            .size(48.dp)
            .clip(ToolShape)
            .background(colors.surface)
            .border(1.dp, colors.border, ToolShape)
            .testTag(testTag)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = colors.ink,
            modifier = Modifier.size(22.dp)
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
    val colors = diagnosticsColors()
    Column(
        modifier = modifier
            .widthIn(min = 48.dp)
            .heightIn(min = 48.dp)
            .clip(ToolShape)
            .clickable(onClick = onClick)
            .testTag(testTag)
            .padding(horizontal = 4.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = colors.ink,
            modifier = Modifier.size(22.dp)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = colors.secondary,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
fun DiagnosticsActionMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    actions: List<DiagnosticsMenuAction>
) {
    val colors = diagnosticsColors()
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        containerColor = colors.surfaceRaised,
        shape = RoundedCornerShape(8.dp)
    ) {
        actions.forEach { action ->
            DropdownMenuItem(
                text = {
                    Text(
                        text = action.label,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        color = if (action.destructive) colors.danger else colors.ink
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
                            tint = if (action.destructive) colors.danger else colors.ink
                        )
                    }
                },
                modifier = if (action.testTag != null) Modifier.testTag(action.testTag) else Modifier
            )
        }
    }
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
        style = MaterialTheme.typography.bodyMedium,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        color = content,
        modifier = modifier
    )
}

@Composable
fun DiagnosticsSectionRow(
    title: String,
    detail: String,
    value: String,
    icon: ImageVector,
    valueColor: Color,
    onClick: () -> Unit,
    testTag: String
) {
    val colors = diagnosticsColors()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clip(ToolShape)
            .background(colors.surface)
            .border(1.dp, colors.border, ToolShape)
            .clickable(onClick = onClick)
            .testTag(testTag)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = colors.ink,
            modifier = Modifier.size(22.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = colors.ink
            )
            Text(
                text = detail,
                style = MaterialTheme.typography.bodyLarge,
                color = colors.secondary
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
            color = valueColor,
            textAlign = TextAlign.End
        )
    }
}

@Composable
fun DiagnosticsDragHandle(
    onDragDelta: (Float) -> Unit,
    onDragStopped: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = diagnosticsColors()
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .testTag("diagnostics_drag_handle")
            .diagnosticsDrag(onDragDelta, onDragStopped),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .width(32.dp)
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(colors.border)
        )
    }
}
