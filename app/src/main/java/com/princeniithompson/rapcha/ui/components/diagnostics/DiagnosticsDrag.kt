package com.princeniithompson.rapcha.ui.components.diagnostics

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed

/**
 * Vertical drag used only by the diagnostics sheet handle.
 * [onDelta] receives pixel deltas. [onStop] receives the release velocity.
 */
fun Modifier.diagnosticsDrag(
    onDelta: (Float) -> Unit,
    onStop: (Float) -> Unit
): Modifier = composed {
    draggable(
        orientation = Orientation.Vertical,
        state = rememberDraggableState { delta -> onDelta(delta) },
        onDragStopped = { velocity -> onStop(velocity) }
    )
}
