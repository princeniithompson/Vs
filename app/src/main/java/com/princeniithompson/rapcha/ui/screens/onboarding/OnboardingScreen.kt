package com.princeniithompson.rapcha.ui.screens.onboarding

import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.princeniithompson.rapcha.ui.VoiceTypingViewModel
import com.princeniithompson.rapcha.ui.screens.onboarding.provisioning.AiStudioAutomationEngine
import com.princeniithompson.rapcha.ui.screens.onboarding.provisioning.ProvisioningStatus
import com.princeniithompson.rapcha.ui.theme.OnboardingCreamBackground

/**
 * Main Onboarding Coordinator:
 * 1. WELCOME_HERO: Crash-free animated "Rapcha / 4x faster than typing" screen.
 * 2. GOOGLE_SIGN_IN: Clean Google-only sign-in gate ("Continue with Google").
 * 3. Embedded Google Account picker -> GOOGLE_AUTH_HANDOFF ("Log in as <email> / Open Rapcha").
 * 4. Existing Rapcha editorial onboarding tour while AiStudioAutomationEngine silently
 *    provisions and encrypts the user's personal Gemini API key behind the scenes (alpha = 0f).
 */
@Composable
fun OnboardingScreen(
    viewModel: VoiceTypingViewModel,
    onFinishOnboarding: () -> Unit,
    modifier: Modifier = Modifier,
    sharedAutomationEngine: AiStudioAutomationEngine? = null
) {
    val context = LocalContext.current
    var stage by remember { mutableStateOf(OnboardingStage.WELCOME_HERO) }

    val ownsEngine = sharedAutomationEngine == null
    val automationEngine = remember(sharedAutomationEngine) {
        sharedAutomationEngine ?: AiStudioAutomationEngine(context)
    }
    val provisioningState by automationEngine.state.collectAsState()
    val activeWebView by automationEngine.activeWebView.collectAsState()

    val lifecycleOwner = LocalLifecycleOwner.current
    val isAccessibilityConnected by viewModel.isAccessibilityConnected.collectAsState()
    val hasOverlayPermission by viewModel.hasOverlayPermission.collectAsState()
    val savedEmail by viewModel.signedInGoogleEmail.collectAsState()

    DisposableEffect(lifecycleOwner, ownsEngine) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    viewModel.refreshPermissions()
                    viewModel.tryAutoInjectFromClipboard()
                    if (ownsEngine) {
                        automationEngine.onHostResumed()
                    }
                }
                Lifecycle.Event.ON_PAUSE -> {
                    if (ownsEngine) {
                        automationEngine.onHostPaused()
                    }
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            if (ownsEngine) {
                automationEngine.tearDownWebView()
            }
        }
    }

    // Move automatically from Sign-In browser to "Log in as <email> / Open Rapcha" once Google redirects to AI Studio
    LaunchedEffect(provisioningState.status) {
        if (provisioningState.status == ProvisioningStatus.SIGNED_IN_HANDOFF &&
            stage == OnboardingStage.GOOGLE_SIGN_IN
        ) {
            stage = OnboardingStage.GOOGLE_AUTH_HANDOFF
        }
    }

    BackHandler(
        enabled = provisioningState.showBrowserToUser || stage != OnboardingStage.WELCOME_HERO
    ) {
        when {
            provisioningState.showBrowserToUser -> {
                val wv = activeWebView
                if (wv != null && wv.canGoBack()) {
                    wv.goBack()
                } else {
                    automationEngine.dismissVisibleBrowser()
                }
            }
            stage == OnboardingStage.GOOGLE_SIGN_IN -> stage = OnboardingStage.WELCOME_HERO
            stage == OnboardingStage.GOOGLE_AUTH_HANDOFF -> stage = OnboardingStage.GOOGLE_SIGN_IN
            stage == OnboardingStage.INTRO -> stage = OnboardingStage.WELCOME_HERO
            stage == OnboardingStage.HOW_IT_WORKS -> stage = OnboardingStage.INTRO
            stage == OnboardingStage.SANDBOX -> stage = OnboardingStage.HOW_IT_WORKS
            stage == OnboardingStage.APPS_SHOWCASE -> stage = OnboardingStage.SANDBOX
            stage == OnboardingStage.OVERLAY_PERMISSION -> stage = OnboardingStage.APPS_SHOWCASE
            stage == OnboardingStage.ACCESSIBILITY_GUIDE -> stage = OnboardingStage.OVERLAY_PERMISSION
            stage == OnboardingStage.NOTIFICATION_PERMISSION ||
            stage == OnboardingStage.ALL_SET -> stage = OnboardingStage.ACCESSIBILITY_GUIDE
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .testTag("screen_onboarding")
    ) {
        // Live full-viewport WebView kept laid out underneath foreground onboarding (alpha = 0f when hidden)
        // so Angular SPAs and getBoundingClientRect() compute accurate coordinates without stealing touches.
        EmbeddedGoogleAuthBrowserContainer(
            webView = activeWebView,
            visible = provisioningState.showBrowserToUser,
            pageTitle = provisioningState.pageTitle,
            currentUrl = provisioningState.currentUrl,
            isLoading = provisioningState.isPageLoading,
            progress = provisioningState.loadProgress,
            onClose = { automationEngine.dismissVisibleBrowser() },
            onRefresh = { automationEngine.reloadCurrentPage() }
        )

        // Foreground Active Onboarding Screen (opaque layer above hidden WebView)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .zIndex(1f)
        ) {
            when (stage) {
                OnboardingStage.WELCOME_HERO -> {
                    OnboardingHeroWelcomeScreen(
                        onGetStarted = {
                            stage = if (viewModel.hasProvisionedPersonalKey()) {
                                OnboardingStage.INTRO
                            } else {
                                OnboardingStage.GOOGLE_SIGN_IN
                            }
                        }
                    )
                }

                OnboardingStage.GOOGLE_SIGN_IN -> {
                    OnboardingGoogleSignInScreen(
                        onContinueWithGoogle = {
                            automationEngine.createOrReuseWebView()
                            automationEngine.startGoogleSignIn()
                        }
                    )
                }

                OnboardingStage.GOOGLE_AUTH_HANDOFF -> {
                    OnboardingGoogleHandoffScreen(
                        email = provisioningState.detectedEmail.ifBlank { savedEmail },
                        onOpenRapcha = {
                            automationEngine.onUserTappedOpenRapcha()
                            stage = OnboardingStage.INTRO
                        },
                        onUseAnotherAccount = {
                            automationEngine.createOrReuseWebView()
                            automationEngine.switchGoogleAccount()
                            stage = OnboardingStage.GOOGLE_SIGN_IN
                        }
                    )
                }

                OnboardingStage.INTRO,
                OnboardingStage.HOW_IT_WORKS,
                OnboardingStage.SANDBOX,
                OnboardingStage.APPS_SHOWCASE,
                OnboardingStage.OVERLAY_PERMISSION,
                OnboardingStage.ACCESSIBILITY_GUIDE,
                OnboardingStage.NOTIFICATION_PERMISSION,
                OnboardingStage.ALL_SET -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(OnboardingCreamBackground)
                            .safeDrawingPadding(),
                        contentAlignment = Alignment.TopCenter
                    ) {
                        Column(
                            modifier = Modifier
                                .widthIn(max = 620.dp)
                                .fillMaxSize()
                        ) {
                            OnboardingTopBar(
                                currentSegment = stage.progressIndex,
                                totalSegments = 7
                            )

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

                                else -> Unit
                            }
                        }
                    }
                }
            }
        }
    }
}
