package com.princeniithompson.rapcha.ui.screens.onboarding

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.SentimentSatisfied
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.princeniithompson.rapcha.R
import com.princeniithompson.rapcha.service.FloatingBubbleManager
import com.princeniithompson.rapcha.service.floating.FloatingPolishClient
import com.princeniithompson.rapcha.ui.VoiceTypingViewModel
import com.princeniithompson.rapcha.ui.components.FloatingDictationPopup
import com.princeniithompson.rapcha.ui.theme.OnboardingBorderStroke
import com.princeniithompson.rapcha.ui.theme.OnboardingCreamBackground
import com.princeniithompson.rapcha.ui.theme.OnboardingInkBlack
import com.princeniithompson.rapcha.ui.theme.OnboardingMutedText
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Screens 3, 4, 5, 6, 7: Interactive sandbox using the user's native keyboard (Gboard, Samsung, etc.):
 * 1. "Tap the text box above"
 * 2. User taps text box -> real native software keyboard opens, shaking Lifesaver Bubble appears right above keyboard
 * 3. Tapping bubble requests real Android RECORD_AUDIO permission and expands into the EXACT floating notched container
 *    used across the application (with aurora glow, live transcript, Clean/Optimize AI modes, Cancel, Polish, and Complete buttons)
 * 4. Transcribed / polished text populates the native chat input field -> "Nice, that worked!" with Continue button
 */
@Composable
fun OnboardingSandboxScreen(
    viewModel: VoiceTypingViewModel,
    onComplete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val view = LocalView.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }
    val coroutineScope = rememberCoroutineScope()

    var stepStage by remember { mutableIntStateOf(1) } // 1: Tap text box, 2: Tap bubble, 3: Container expanded (recording/polishing), 4: Result
    var typedText by remember { mutableStateOf("") }
    var isPolishingInProgress by remember { mutableStateOf(false) }

    val speechManager = remember { OnboardingSpeechManager(context) }
    val isSpeechListening by speechManager.isListening.collectAsState()
    val speechLiveTranscript by speechManager.liveTranscript.collectAsState()
    val speechAmplitude by speechManager.amplitude.collectAsState()

    // Shaking haptic animation offset for stage 2
    val shakeOffset = remember { Animatable(0f) }

    DisposableEffect(Unit) {
        onDispose {
            speechManager.stopListening()
        }
    }

    LaunchedEffect(stepStage) {
        when (stepStage) {
            2 -> {
                val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator
                launch {
                    while (isActive) {
                        try {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                vibrator?.vibrate(
                                    android.os.VibrationEffect.createWaveform(
                                        longArrayOf(0, 50, 40, 50),
                                        intArrayOf(0, 100, 0, 120),
                                        -1
                                    )
                                )
                            } else {
                                @Suppress("DEPRECATION")
                                vibrator?.vibrate(70L)
                            }
                            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        } catch (_: Exception) {}
                        delay(800L)
                    }
                }
                shakeOffset.animateTo(
                    targetValue = 0f,
                    animationSpec = infiniteRepeatable(
                        animation = keyframes {
                            durationMillis = 800
                            0f at 0 using LinearEasing
                            (-5f) at 100
                            5f at 200
                            (-4f) at 300
                            4f at 400
                            0f at 500
                            0f at 800
                        },
                        repeatMode = RepeatMode.Restart
                    )
                )
            }
            4 -> {
                shakeOffset.snapTo(0f)
                focusManager.clearFocus()
                keyboardController?.hide()
            }
            else -> {
                shakeOffset.snapTo(0f)
            }
        }
    }

    // Permission launcher for real audio
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            stepStage = 3
            speechManager.startListening()
        }
    }

    // Live sync real speech transcript stream into typed text
    LaunchedEffect(speechLiveTranscript) {
        if (stepStage == 3 && speechLiveTranscript.isNotBlank()) {
            typedText = speechLiveTranscript
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(OnboardingCreamBackground)
            .testTag("onboarding_sandbox_screen")
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .imePadding(),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Upper Section: Chat Input Box and Step Guidance
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Real Chat Input Box (triggers user's native keyboard)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp)
                        .clip(RoundedCornerShape(26.dp))
                        .background(Color(0xFFECE6F0))
                        .clickable {
                            focusRequester.requestFocus()
                            keyboardController?.show()
                            if (stepStage == 1) {
                                stepStage = 2
                            }
                        }
                        .testTag("sandbox_native_input")
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.SentimentSatisfied,
                            contentDescription = "Emoji",
                            tint = OnboardingMutedText,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Box(
                            modifier = Modifier.weight(1f),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            BasicTextField(
                                value = typedText,
                                onValueChange = {
                                    typedText = it
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .focusRequester(focusRequester)
                                    .onFocusChanged { focusState ->
                                        if (focusState.isFocused && stepStage == 1) {
                                            stepStage = 2
                                        }
                                    },
                                singleLine = true,
                                textStyle = TextStyle(
                                    fontSize = 16.sp,
                                    color = OnboardingInkBlack,
                                    fontFamily = FontFamily.Default
                                ),
                                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                decorationBox = { innerTextField ->
                                    if (typedText.isEmpty() && stepStage == 1) {
                                        Text(
                                            text = "Tap to type...",
                                            fontSize = 16.sp,
                                            color = OnboardingMutedText
                                        )
                                    }
                                    innerTextField()
                                }
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Send",
                            tint = OnboardingMutedText,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Prompt Text depending on Stage
                when (stepStage) {
                    1 -> {
                        Text(
                            text = buildAnnotatedString {
                                withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)) {
                                    append("Tap")
                                }
                                append(" the text box above")
                            },
                            fontSize = 17.sp,
                            color = OnboardingInkBlack
                        )
                    }
                    2 -> {
                        Text(
                            text = buildAnnotatedString {
                                withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)) {
                                    append("Tap")
                                }
                                append(" the lifesaver bubble")
                            },
                            fontSize = 17.sp,
                            color = OnboardingInkBlack
                        )
                    }
                    3 -> {
                        Text(
                            text = "Say something into the bubble!",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Medium,
                            color = OnboardingInkBlack
                        )
                    }
                    4 -> {
                        Text(
                            text = "Nice, that worked!",
                            fontSize = 19.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = OnboardingInkBlack
                        )
                        Spacer(modifier = Modifier.height(18.dp))
                        Button(
                            onClick = onComplete,
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(1.5.dp, OnboardingBorderStroke),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp)
                                .testTag("btn_sandbox_continue")
                        ) {
                            Text(
                                text = "Continue",
                                fontSize = 17.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                }
            }

            // Lower Section: Exact Floating Bubble / Notched Container docked right above native keyboard
            if (stepStage == 2 || stepStage == 3) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                ) {
                    when (stepStage) {
                        2 -> {
                            // Shaking Lifesaver Bubble docked above keyboard
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(end = 24.dp),
                                contentAlignment = Alignment.CenterEnd
                            ) {
                                Box(
                                    modifier = Modifier
                                        .offset(x = shakeOffset.value.dp)
                                        .size(50.dp)
                                        .clickable {
                                            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                            val hasPermission = ContextCompat.checkSelfPermission(
                                                context,
                                                Manifest.permission.RECORD_AUDIO
                                            ) == PackageManager.PERMISSION_GRANTED
                                            if (hasPermission) {
                                                stepStage = 3
                                                speechManager.startListening()
                                            } else {
                                                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                            }
                                        }
                                        .testTag("onboarding_docked_bubble"),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Image(
                                        painter = painterResource(id = R.drawable.ic_lifebuoy_ring),
                                        contentDescription = "Lifesaver Bubble",
                                        modifier = Modifier.size(46.dp)
                                    )
                                }
                            }
                        }

                        3 -> {
                            // The Exact App Floating Dictation Container docked right above the keyboard!
                            val liveText = if (typedText.isNotBlank()) typedText else speechLiveTranscript

                            FloatingDictationPopup(
                                transcriptText = liveText,
                                isRecording = isSpeechListening,
                                isPendingFinalizing = false,
                                isPolishing = isPolishingInProgress,
                                audioAmplitude = speechAmplitude,
                                onCancelClick = {
                                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                    speechManager.stopListening()
                                    stepStage = 2
                                },
                                onPolishClick = {
                                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                    speechManager.stopListening()
                                    val currentText = typedText.ifBlank { speechLiveTranscript }
                                    if (currentText.isBlank()) return@FloatingDictationPopup

                                    typedText = currentText
                                    coroutineScope.launch {
                                        isPolishingInProgress = true
                                        val apiKey = com.princeniithompson.rapcha.core.ApiConfig.resolveApiKey(context)
                                        val selectedMode = FloatingBubbleManager.selectedAiPolishMode.value

                                        if (!com.princeniithompson.rapcha.core.ApiConfig.isPlaceholder(apiKey)) {
                                            val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                                FloatingPolishClient.polishTranscript(
                                                    apiKey = apiKey,
                                                    rawTranscript = currentText,
                                                    category = com.princeniithompson.rapcha.service.AppCategory.AI,
                                                    appName = "Sandbox",
                                                    aiPolishMode = selectedMode
                                                )
                                            }
                                            val polished = result.text
                                            if (!polished.isNullOrBlank()) {
                                                typedText = polished
                                            }
                                        } else {
                                            // Real clean up without fake hardcoded sentences: format whitespace and capitalize
                                            kotlinx.coroutines.delay(600)
                                            val cleaned = currentText.trim().replaceFirstChar { 
                                                if (it.isLowerCase()) it.titlecase(java.util.Locale.ROOT) else it.toString() 
                                            }
                                            val withPunctuation = if (!cleaned.endsWith(".") && !cleaned.endsWith("?") && !cleaned.endsWith("!")) {
                                                "$cleaned."
                                            } else {
                                                cleaned
                                            }
                                            typedText = withPunctuation
                                        }
                                        isPolishingInProgress = false
                                    }
                                },
                                onCompleteClick = {
                                    view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                                    speechManager.stopListening()
                                    val finalRecordedText = typedText.ifBlank { speechLiveTranscript }
                                    if (finalRecordedText.isNotBlank()) {
                                        typedText = finalRecordedText
                                    }
                                    stepStage = 4
                                },
                                onLifebuoyClick = {
                                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                    if (isSpeechListening) {
                                        speechManager.stopListening()
                                    } else {
                                        speechManager.startListening()
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("onboarding_floating_container")
                            )
                        }
                    }
                }
            } else {
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}
