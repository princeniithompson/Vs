package com.princeniithompson.rapcha.ui.components

import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.princeniithompson.rapcha.service.FloatingBubbleManager

@OptIn(ExperimentalMaterial3Api::class)
@Suppress("UNUSED_PARAMETER")
@Composable
fun GlowStylesBottomSheet(
    sheetState: SheetState,
    selectedStyleId: String,
    selectedFinishingStyleId: String = "",
    onSelectStyle: (String) -> Unit,
    onSelectFinishingStyle: (String) -> Unit = {},
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val chrome by FloatingBubbleManager.bubbleChrome.collectAsState()
    val tone by FloatingBubbleManager.selectedColorTone.collectAsState()
    val recordingNow by FloatingBubbleManager.isRecording.collectAsState()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(RecordingGlowColors.PAGE),
        shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp),
        dragHandle = null,
        tonalElevation = 0.dp
    ) {
        RecordingGlowScreen(
            selectedStyleId = selectedStyleId,
            activeChrome = chrome,
            tone = tone,
            idle = !recordingNow,
            onSelectStyle = { styleId ->
                onSelectStyle(styleId)
                FloatingBubbleManager.setGlowStyle(context, styleId)
            },
            onChooseChrome = { style ->
                FloatingBubbleManager.setBubbleChrome(context, style)
            },
            onChooseTone = { mode ->
                FloatingBubbleManager.setColorTone(context, mode)
            },
            onDismiss = onDismiss,
            modifier = Modifier.fillMaxWidth().fillMaxHeight()
        )
    }
}
