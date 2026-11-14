package com.princeniithompson.rapcha.service.floating

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.princeniithompson.rapcha.service.A11yRepairKind
import com.princeniithompson.rapcha.service.AppContextResolver
import com.princeniithompson.rapcha.service.FloatingBubbleManager
import com.princeniithompson.rapcha.ui.components.FloatingDictationPopup
import com.princeniithompson.rapcha.ui.components.overlay.CompactRecordingBar
import com.princeniithompson.rapcha.ui.components.overlay.FloatingCollapsedBubble
import com.princeniithompson.rapcha.ui.components.overlay.FloatingScreenScanOverlay
import com.princeniithompson.rapcha.ui.components.overlay.rememberDynamicAuroraPalette
import com.princeniithompson.rapcha.ui.components.overlay.toDangerPalette
import com.princeniithompson.rapcha.ui.theme.MyApplicationTheme

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
    elapsedSeconds: Int = 0,
    isCurrentAppSensitive: Boolean,
    bubbleVisible: Boolean = true,
    repairKind: A11yRepairKind = A11yRepairKind.NONE,
    onRepairClick: () -> Unit = {},
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
        val chrome by FloatingBubbleManager.bubbleChrome.collectAsState()
        val lowPower by RenderBudget.lowPower.collectAsState()
        val rootModifier = when {
            isScanningAnimation -> Modifier.fillMaxSize()
            isExpanded && chrome != BubbleChromeStyle.COMPACT -> Modifier.fillMaxWidth().wrapContentHeight()
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
                if (chrome == BubbleChromeStyle.COMPACT) {
                    val locked by FloatingBubbleManager.lockedPolishSection.collectAsState()
                    val currentAppContext by FloatingBubbleManager.currentResolvedAppContext.collectAsState()
                    val currentPkg by FloatingBubbleManager.currentForegroundPackage.collectAsState()
                    val context = LocalContext.current
                    // v24 Lite: no app name lookup (no app detection); Lite is fixed per process.
                    val detectedAppName = if (com.princeniithompson.rapcha.core.LiteMode.enabled) "Listening" else remember(locked?.appName, currentAppContext?.name, currentPkg) {
                        val lockedName = locked?.appName
                        val resolvedContextName = currentAppContext?.name

                        when {
                            !lockedName.isNullOrBlank() && lockedName != "App" -> lockedName
                            !resolvedContextName.isNullOrBlank() && resolvedContextName != "App" -> resolvedContextName
                            !currentPkg.isNullOrBlank() -> {
                                val pkgResolved = AppContextResolver.resolveAppName(context, currentPkg)
                                when {
                                    pkgResolved.isNotBlank() && pkgResolved != "App" -> pkgResolved
                                    !lockedName.isNullOrBlank() -> lockedName
                                    !resolvedContextName.isNullOrBlank() -> resolvedContextName
                                    else -> pkgResolved.ifBlank { "WhatsApp" }
                                }
                            }
                            !lockedName.isNullOrBlank() -> lockedName
                            !resolvedContextName.isNullOrBlank() -> resolvedContextName
                            else -> "WhatsApp"
                        }
                    }
                    val problem by FloatingBubbleManager.isNetworkProblem.collectAsState()
                    val base = rememberDynamicAuroraPalette()
                    val palette = if (problem) base.toDangerPalette() else base
                    val status = when {
                        polishing || pendingFinalizing -> "Finishing"
                        problem -> "Offline"
                        else -> null
                    }
                    CompactRecordingBar(
                        elapsedSeconds = elapsedSeconds,
                        appName = detectedAppName,
                        glowStyleId = selectedGlowStyleId,
                        palette = palette,
                        audioAmplitude = audioAmplitude,
                        processing = polishing || pendingFinalizing,
                        statusLabel = status,
                        bubbleVisible = bubbleVisible,
                        lowPower = lowPower,
                        onCancel = onCancelClick,
                        onComplete = onCompleteClick,
                        onDragStart = onDragStart,
                        onDrag = onDrag,
                        onDragEnd = onDragEnd
                    )
                } else {
                    FloatingDictationPopup(
                        transcriptText = transcript,
                        isRecording = recording,
                        isPendingFinalizing = pendingFinalizing,
                        isPolishing = polishing,
                        audioAmplitude = audioAmplitude,
                        glowStyleId = selectedGlowStyleId,
                        finishingStyleId = selectedFinishingStyleId,
                        bubbleVisible = bubbleVisible,
                        lowPower = lowPower,
                        onCancelClick = onCancelClick,
                        onPolishClick = onPolishClick,
                        onCompleteClick = onCompleteClick,
                        onLifebuoyClick = onRingClick,
                        onDragStart = onDragStart,
                        onDrag = onDrag,
                        onDragEnd = onDragEnd
                    )
                }
            } else {
                FloatingCollapsedBubble(
                    isRecording = recording,
                    isShrunk = isShrunk,
                    isSnappedToRight = isSnappedToRight,
                    isScanMode = isScanMode,
                    isContextLoaded = isContextLoaded,
                    bubbleVisible = bubbleVisible,
                    lowPower = lowPower,
                    repairKind = repairKind,
                    onRepairClick = onRepairClick,
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
