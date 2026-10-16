package com.princeniithompson.rapcha.service.floating

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.princeniithompson.rapcha.ui.components.FloatingDictationPopup
import com.princeniithompson.rapcha.websocket.InterimPreviewMonitor
import com.princeniithompson.rapcha.ui.components.overlay.FloatingCollapsedBubble
import com.princeniithompson.rapcha.ui.components.overlay.FloatingSafeModeShieldBadge
import com.princeniithompson.rapcha.ui.components.overlay.FloatingScreenScanOverlay
import com.princeniithompson.rapcha.ui.theme.MyApplicationTheme

/**
 * Encapsulates the Compose UI rendering tree for the floating overlay window.
 * Shows either the scan animation, safe-mode badge, expanded dictation popup, or collapsed bubble.
 */
@Composable
fun FloatingOverlayContent(
    transcript: String,
    previewPhase: InterimPreviewMonitor.Phase = InterimPreviewMonitor.Phase.LISTENING,
    polishingMessage: String? = null,
    recording: Boolean,
    pendingFinalizing: Boolean,
    polishing: Boolean,
    isExpanded: Boolean,
    isShrunk: Boolean,
    audioAmplitude: Float,
    isScanMode: Boolean,
    isScanningAnimation: Boolean,
    scanningAppName: String?,
    scanOriginX: Float,
    scanOriginY: Float,
    isSnappedToRight: Boolean,
    isContextLoaded: Boolean,
    selectedGlowStyleId: String,
    selectedFinishingStyleId: String,
    isCurrentAppSensitive: Boolean,
    onRingClick: () -> Unit,
    onCancelClick: () -> Unit,
    onPolishClick: () -> Unit,
    onCompleteClick: () -> Unit,
    onLongPressBubble: () -> Unit,
    onScanTriggered: () -> Unit,
    onDragStart: () -> Unit,
    onDrag: (dx: Float, dy: Float) -> Unit,
    onDragEnd: () -> Unit
) {
    MyApplicationTheme(darkTheme = true, dynamicColor = true) {
        val rootModifier = when {
            isScanningAnimation -> Modifier.fillMaxSize()
            isExpanded -> Modifier.fillMaxWidth().wrapContentHeight()
            else -> Modifier.wrapContentSize()
        }
        Box(modifier = rootModifier) {
            if (isScanningAnimation) {
                FloatingScreenScanOverlay(
                    isScanning = true,
                    appName = scanningAppName,
                    originX = scanOriginX,
                    originY = scanOriginY
                )
            } else if (isCurrentAppSensitive) {
                // Auto-Hide: Render empty box so no visual pixels or touch targets exist
                Box(modifier = Modifier)
            } else if (isExpanded) {
                FloatingDictationPopup(
                    transcriptText = transcript,
                    previewPhase = previewPhase,
                    polishingMessage = polishingMessage,
                    isRecording = recording,
                    isPendingFinalizing = pendingFinalizing,
                    isPolishing = polishing,
                    audioAmplitude = audioAmplitude,
                    glowStyleId = selectedGlowStyleId,
                    finishingStyleId = selectedFinishingStyleId,
                    onCancelClick = onCancelClick,
                    onPolishClick = onPolishClick,
                    onCompleteClick = onCompleteClick,
                    onLifebuoyClick = onRingClick,
                    onDragStart = onDragStart,
                    onDrag = onDrag,
                    onDragEnd = onDragEnd
                )
            } else {
                FloatingCollapsedBubble(
                    isRecording = recording,
                    isShrunk = isShrunk,
                    isSnappedToRight = isSnappedToRight,
                    isScanMode = isScanMode,
                    isContextLoaded = isContextLoaded,
                    onClick = onRingClick,
                    onLongPress = onLongPressBubble,
                    onScanTriggered = onScanTriggered,
                    onDragStart = onDragStart,
                    onDrag = onDrag,
                    onDragEnd = onDragEnd
                )
            }
        }
    }
}
