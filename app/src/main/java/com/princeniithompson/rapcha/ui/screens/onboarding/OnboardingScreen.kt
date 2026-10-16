package com.princeniithompson.rapcha.ui.screens.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.princeniithompson.rapcha.ui.VoiceTypingViewModel
import com.princeniithompson.rapcha.ui.theme.OnboardingCreamBackground

/**
 * Main Onboarding Coordinator replicating Wispr Flow's exact workflow and aesthetic:
 * - Editorial Serif typography
 * - Soft cream background
 * - Soft pastel coral red hero card
 * - Interactive Sandbox with shaking Lifesaver and live microphone test
 * - Illustrated Overlay and Accessibility walkthroughs
 * - Notification prompt and "You're all set!" finish
 */
@Composable
fun OnboardingScreen(
    viewModel: VoiceTypingViewModel,
    onFinishOnboarding: () -> Unit,
    modifier: Modifier = Modifier
) {
    var stage by remember { mutableStateOf(OnboardingStage.INTRO) }

    val lifecycleOwner = LocalLifecycleOwner.current
    val isAccessibilityConnected by viewModel.isAccessibilityConnected.collectAsState()
    val hasOverlayPermission by viewModel.hasOverlayPermission.collectAsState()

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshPermissions()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(OnboardingCreamBackground)
            .safeDrawingPadding()
            .testTag("screen_onboarding")
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Top Segmented Progress Bar (Wispr Flow style)
            OnboardingTopBar(
                currentSegment = stage.progressIndex,
                totalSegments = 7
            )

            // Current Active Stage
            when (stage) {
                OnboardingStage.INTRO -> {
                    OnboardingIntroScreen(
                        onNext = { stage = OnboardingStage.HOW_IT_WORKS }
                    )
                }

                OnboardingStage.HOW_IT_WORKS -> {
                    OnboardingHowItWorksScreen(
                        onNext = { stage = OnboardingStage.SANDBOX }
                    )
                }

                OnboardingStage.SANDBOX -> {
                    OnboardingSandboxScreen(
                        viewModel = viewModel,
                        onComplete = { stage = OnboardingStage.APPS_SHOWCASE }
                    )
                }

                OnboardingStage.APPS_SHOWCASE -> {
                    OnboardingAppsShowcaseScreen(
                        onNext = { stage = OnboardingStage.OVERLAY_PERMISSION }
                    )
                }

                OnboardingStage.OVERLAY_PERMISSION -> {
                    OnboardingOverlayPermissionScreen(
                        canDrawOverlays = hasOverlayPermission,
                        onNext = { stage = OnboardingStage.ACCESSIBILITY_GUIDE }
                    )
                }

                OnboardingStage.ACCESSIBILITY_GUIDE -> {
                    OnboardingAccessibilityGuideScreen(
                        isAccessibilityConnected = isAccessibilityConnected,
                        onNext = { stage = OnboardingStage.NOTIFICATION_PERMISSION }
                    )
                }

                OnboardingStage.NOTIFICATION_PERMISSION,
                OnboardingStage.ALL_SET -> {
                    OnboardingNotificationScreen(
                        onFinish = {
                            viewModel.completeOnboarding()
                            if (hasOverlayPermission) {
                                viewModel.setBubbleEnabled(true)
                            }
                            onFinishOnboarding()
                        }
                    )
                }
            }
        }
    }
}
