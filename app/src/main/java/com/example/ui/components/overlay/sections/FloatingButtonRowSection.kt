package com.example.ui.components.overlay.sections

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.example.ui.components.overlay.AuroraColorPalette
import com.example.ui.components.overlay.FloatingActionRow

/**
 * Action buttons section (Cancel, Polish, Complete) with state-guarded completion logic.
 */
@Composable
fun FloatingButtonRowSection(
    state: FloatingDictationPopupState,
    palette: AuroraColorPalette,
    onCancelClick: () -> Unit,
    onPolishClick: () -> Unit,
    onCompleteClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    FloatingActionRow(
        isFinalizing = state.isFinalizing,
        isPolishing = state.isPolishing,
        palette = palette,
        onCancelClick = onCancelClick,
        onPolishClick = onPolishClick,
        onCompleteClick = {
            if (!state.isFinalizing && !state.isPolishing) {
                state.markLocalComplete()
                onCompleteClick()
            }
        },
        modifier = modifier
    )
}
