package com.example.service.floating

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.example.ui.components.FloatingDictationPopup
import com.example.ui.components.overlay.FloatingCollapsedBubble
import com.example.ui.components.overlay.FloatingSafeModeShieldBadge
import com.example.ui.components.overlay.FloatingScreenScanOverlay
import com.example.ui.theme.MyApplicationTheme

/**
 * Encapsulates the Compose UI rendering tree for the floating overlay window.
 * Shows either the scan animation, safe-mode badge, expanded dictation popup, or collapsed bubble.
 */
@Composable
fun FloatingOverlayContent(
    transcript: String,
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
        Box(modifier = Modifier.fillMaxSize()) {
            if (isScanningAnimation) {
                FloatingScreenScanOverlay(
                    isScanning = true,
                    appName = scanningAppName,
                    originX = scanOriginX,
                    originY = scanOriginY
                )
            } else if (isCurrentAppSensitive) {
                FloatingSafeModeShieldBadge(
                    isSnappedToRight = isSnappedToRight,
                    onClick = onRingClick,
                    onDragStart = onDragStart,
                    onDrag = onDrag,
                    onDragEnd = onDragEnd
                )
            } else if (isExpanded) {
                FloatingDictationPopup(
                    transcriptText = transcript,
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
