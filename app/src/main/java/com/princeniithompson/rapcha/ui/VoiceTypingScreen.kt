package com.princeniithompson.rapcha.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.princeniithompson.rapcha.data.ConnectionState
import com.princeniithompson.rapcha.data.CustomVocabularyRepository
import com.princeniithompson.rapcha.ui.components.AudioWaveformVisualizer
import com.princeniithompson.rapcha.ui.components.DiagnosticsBottomSheet
import com.princeniithompson.rapcha.ui.components.GlowAnimationCatalogue
import com.princeniithompson.rapcha.ui.components.GlowStylesBottomSheet
import com.princeniithompson.rapcha.ui.components.PulsatingRecordButton
import com.princeniithompson.rapcha.service.FloatingBubbleManager
import com.princeniithompson.rapcha.ui.screens.DictionaryScreen
import com.princeniithompson.rapcha.ui.screens.HomeScreen
import com.princeniithompson.rapcha.ui.screens.onboarding.AccessibilityDisclosureSheet
import com.princeniithompson.rapcha.ui.screens.onboarding.EmbeddedGoogleAuthBrowserContainer
import com.princeniithompson.rapcha.ui.screens.onboarding.provisioning.AiStudioAutomationEngine
import com.princeniithompson.rapcha.ui.screens.onboarding.provisioning.ProvisioningStatus
import kotlinx.coroutines.launch

enum class MainScreenTab {
    HOME,
    DICTIONARY,
    STYLE
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceTypingScreen(
    viewModel: VoiceTypingViewModel,
    modifier: Modifier = Modifier,
    dictionaryDraft: String? = null,
    onDictionaryDraftConsumed: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val isRecording by viewModel.isRecording.collectAsState()
    val isSmartMode by viewModel.isSmartMode.collectAsState()
    val liveTwoStepPolish by viewModel.liveTwoStepPolish.collectAsState()
    val polishOverride by viewModel.polishOverride.collectAsState()
    val threeStepPolish by viewModel.threeStepPolish.collectAsState()
    val isBubbleEnabled by viewModel.isBubbleEnabled.collectAsState()
    val isSmartSafeModeEnabled by viewModel.isSmartSafeModeEnabled.collectAsState()
    val isAccessibilityConnected by viewModel.isAccessibilityConnected.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val finalizedTranscript by viewModel.finalizedTranscript.collectAsState()
    val interimTranscript by viewModel.interimTranscript.collectAsState()
    val amplitude by viewModel.audioAmplitude.collectAsState()
    val stats by viewModel.stats.collectAsState()
    val logs by viewModel.logs.collectAsState()
    val diagnosticEntries by viewModel.diagnosticEntries.collectAsState()
    val notes by viewModel.notes.collectAsState()
    val selectedModel by viewModel.selectedModel.collectAsState()
    val customApiKey by viewModel.customApiKey.collectAsState()
    val provisionedApiKey by viewModel.provisionedApiKey.collectAsState()
    val signedInGoogleEmail by viewModel.signedInGoogleEmail.collectAsState()
    val activeApiKey = remember(customApiKey, provisionedApiKey) {
        customApiKey.ifBlank { provisionedApiKey }.trim()
    }
    val apiKeyConfigured = remember(activeApiKey) {
        !com.princeniithompson.rapcha.core.ApiConfig.isPlaceholder(activeApiKey)
    }
    val selectedGlowStyleId by viewModel.selectedGlowStyleId.collectAsState()

    val automationEngine = remember { AiStudioAutomationEngine(context) }
    val provisioningState by automationEngine.state.collectAsState()
    val activeWebView by automationEngine.activeWebView.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner, automationEngine) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    viewModel.refreshPermissions()
                    viewModel.tryAutoInjectFromClipboard()
                    automationEngine.onHostResumed()
                }
                Lifecycle.Event.ON_PAUSE -> {
                    automationEngine.onHostPaused()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            automationEngine.tearDownWebView()
        }
    }

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    var selectedTab by remember {
        mutableStateOf(
            if (!dictionaryDraft.isNullOrBlank()) MainScreenTab.DICTIONARY else MainScreenTab.HOME
        )
    }
    LaunchedEffect(dictionaryDraft) {
        if (!dictionaryDraft.isNullOrBlank()) {
            selectedTab = MainScreenTab.DICTIONARY
        }
    }
    var showDiagnostics by remember { mutableStateOf(false) }
    var showAccessibilityDisclosure by remember { mutableStateOf(false) }
    val diagnosticsSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showGlowStylesSheet by remember { mutableStateOf(false) }
    val glowStylesSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    LaunchedEffect(showDiagnostics, drawerState.isOpen) {
        if (showDiagnostics || drawerState.isOpen) {
            viewModel.tryAutoInjectFromClipboard()
        }
    }

    androidx.activity.compose.BackHandler(enabled = selectedTab != MainScreenTab.HOME) {
        selectedTab = MainScreenTab.HOME
    }

    // RECORD_AUDIO Permission Launcher
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            viewModel.startRecording()
        } else {
            scope.launch {
                snackbarHostState.showSnackbar(
                    "Audio recording permission is required for voice typing."
                )
            }
        }
    }

    val onToggleRecord = {
        if (isRecording) {
            viewModel.stopRecording()
        } else {
            val permissionCheck = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            )
            if (permissionCheck == PackageManager.PERMISSION_GRANTED) {
                viewModel.startRecording()
            } else {
                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    val fullTranscript = buildString {
        append(finalizedTranscript)
        if (interimTranscript.isNotEmpty()) {
            if (isNotEmpty()) append(" ")
            append(interimTranscript)
        }
    }.trim()

    val wordCount = remember(fullTranscript) {
        if (fullTranscript.isBlank()) 0
        else fullTranscript.split("\\s+".toRegex()).count { it.isNotBlank() }
    }

    val isOnboardingCompleted by viewModel.isOnboardingCompleted.collectAsState()
    var showOnboardingManual by remember { mutableStateOf(false) }

    if (!isOnboardingCompleted || showOnboardingManual) {
        com.princeniithompson.rapcha.ui.screens.onboarding.OnboardingScreen(
            viewModel = viewModel,
            onFinishOnboarding = {
                showOnboardingManual = false
            },
            sharedAutomationEngine = automationEngine
        )
        return
    }

    Box(modifier = modifier.fillMaxSize()) {
        if (activeWebView != null && (
                provisioningState.showBrowserToUser ||
                    provisioningState.status == ProvisioningStatus.PROVISIONING_BACKGROUND ||
                    provisioningState.status == ProvisioningStatus.SIGNED_IN_HANDOFF ||
                    provisioningState.status == ProvisioningStatus.SIGNING_IN ||
                    provisioningState.status == ProvisioningStatus.NEEDS_AUTH_RECONFIRM
                )
        ) {
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
        }

        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                ModalDrawerSheet(
                    modifier = Modifier.verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = "Settings",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp)
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    Spacer(modifier = Modifier.height(8.dp))

                    // Accessible Gemini API Key Card (auto-populated when provisioned, editable anytime)
                    SettingsApiKeyCard(
                        apiKey = activeApiKey,
                        apiKeyConfigured = apiKeyConfigured,
                        signedInEmail = signedInGoogleEmail,
                        isProvisioning = provisioningState.status == ProvisioningStatus.PROVISIONING_BACKGROUND,
                        onSaveApiKey = { newKey ->
                            viewModel.setCustomApiKey(newKey)
                            Toast.makeText(
                                context,
                                if (newKey.isBlank()) "API key cleared" else "Gemini API key saved",
                                Toast.LENGTH_SHORT
                            ).show()
                        },
                        onAutoGetWithGoogle = {
                            scope.launch {
                                drawerState.close()
                                automationEngine.createOrReuseWebView()
                                automationEngine.startGoogleSignIn()
                            }
                        }
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                // Welcome Tour / Onboarding Replay Row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .clickable {
                            scope.launch {
                                drawerState.close()
                                showOnboardingManual = true
                            }
                        }
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = "Welcome Tour",
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(14.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Welcome Tour",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Replay the 3-step setup guide & voice test",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos,
                        contentDescription = "Open tour",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Custom Vocabulary / Dictionary Row
                val vocabularyList by CustomVocabularyRepository.vocabulary.collectAsState()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .clickable {
                            scope.launch {
                                drawerState.close()
                                selectedTab = MainScreenTab.DICTIONARY
                            }
                        }
                        .background(
                            if (selectedTab == MainScreenTab.DICTIONARY)
                                MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f)
                            else
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                        )
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.secondary),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Description,
                            contentDescription = "Custom Vocabulary",
                            tint = MaterialTheme.colorScheme.onSecondary,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(14.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "Custom Vocabulary",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.secondary.copy(alpha = 0.18f))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = "${vocabularyList.size}",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.secondary
                                )
                            }
                        }
                        Text(
                            text = "Add custom words, names & email addresses",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos,
                        contentDescription = "Open dictionary",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Glow Animation Styles Picker Row (Opens 21 styles bottom sheet)
                val activeGlowStyle = GlowAnimationCatalogue.getById(selectedGlowStyleId)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .clickable {
                            scope.launch {
                                drawerState.close()
                                showGlowStylesSheet = true
                            }
                        }
                        .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f))
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.AutoAwesome,
                            contentDescription = "Glow Animation Styles",
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(14.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "Glow Animation Styles",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = "${GlowAnimationCatalogue.styles.size}",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Active: ${activeGlowStyle.name}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "Tap to preview & pick from 21 animations",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos,
                        contentDescription = "Open glow styles",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 12.dp)
                ) {
                    Text(
                        text = "Polish route",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Auto uses two-step Live in AI apps and Smart everywhere else. Force Live or Force Smart applies on the next dictation, not while you are speaking.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    listOf(
                        com.princeniithompson.rapcha.service.floating.Stage2PolishBackend.AUTO to "Auto",
                        com.princeniithompson.rapcha.service.floating.Stage2PolishBackend.LIVE to "Force two-step Live",
                        com.princeniithompson.rapcha.service.floating.Stage2PolishBackend.NON_LIVE to "Force Smart"
                    ).forEach { (mode, label) ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = polishOverride == mode,
                                onClick = { viewModel.setPolishOverride(mode) },
                                enabled = !isRecording
                            )
                            Text(text = label, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Three-step polish",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            val context = androidx.compose.ui.platform.LocalContext.current
                            val quotaStatus = remember(threeStepPolish, isRecording) {
                                com.princeniithompson.rapcha.service.floating.PolishQuotaMonitor.status(context)
                            }
                            Text(
                                text = when {
                                    threeStepPolish && !quotaStatus.available ->
                                        "Cooldown active (${quotaStatus.reason.label} · ${quotaStatus.rpmUsed}/${quotaStatus.rpmLimit} RPM, ${quotaStatus.rpdUsed}/${quotaStatus.rpdLimit} RPD): using standard Gemini 3.5 Smart fallback until limits reset."
                                    threeStepPolish ->
                                        "On for AI apps: Smart cleanup on gemini-3.5-transcribe-live, then gemini-3.5-flash-lite prompt polish (${quotaStatus.rpmUsed}/${quotaStatus.rpmLimit} RPM · ${quotaStatus.rpdUsed}/${quotaStatus.rpdLimit} RPD). Auto-pauses if limits are reached."
                                    else ->
                                        "Off: AI Live stays verbatim before Stage-2 polish. Turn it on again for the next section."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        Switch(
                            checked = threeStepPolish,
                            onCheckedChange = { viewModel.setThreeStepPolish(it) },
                            enabled = !isRecording,
                            modifier = Modifier.testTag("three_step_polish_switch")
                        )
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Smart Mode",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = if (isRecording || liveTwoStepPolish) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                                    else MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = if (liveTwoStepPolish) {
                                "Forced off while Live two-step polish is on, so Stage 1 stays verbatim. Your Smart setting is kept for when you switch back."
                            } else {
                                "On: cleans up filler words and adds punctuation. Off: raw verbatim transcript, fastest."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isRecording || liveTwoStepPolish) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Switch(
                        checked = isSmartMode && !liveTwoStepPolish,
                        onCheckedChange = { viewModel.setSmartMode(it) },
                        enabled = !isRecording && !liveTwoStepPolish,
                        modifier = Modifier.testTag("smart_mode_switch")
                    )
                }

                val isSaveRecordingsEnabled by viewModel.isSaveRecordingsEnabled.collectAsState()

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Save audio recordings",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Saves 16 kHz WAV audio files locally on phone for review, playback, and export.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Switch(
                        checked = isSaveRecordingsEnabled,
                        onCheckedChange = { viewModel.setSaveRecordingsEnabled(it) },
                        modifier = Modifier.testTag("save_recordings_switch")
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))

                // Audio Capture (Whisperflow Engine) Section
                Text(
                    text = "Audio Capture (Whisperflow Engine)",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                )

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
                    ),
                    border = BorderStroke(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Audio Source",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Hardware MIC (AudioSource.MIC = 1)",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Stream Format",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "16 kHz Mono · 16-bit PCM",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Client DSP / Filters",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Disabled (Pure raw packets)",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Noise Rejection",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Gemini Live Server-Side VAD",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))

                // Everywhere Mode Section (Floating Bubble 🛟)
                Text(
                    text = "Everywhere Voice Bubble 🛟",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Floating Voice Ring",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Floats over TikTok, WhatsApp, etc. Tap to dictate, long-touch to drag anywhere.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Switch(
                        checked = isBubbleEnabled,
                        onCheckedChange = { enabled ->
                            if (enabled && !viewModel.canDrawOverlays()) {
                                val intent = Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:${context.packageName}")
                                )
                                context.startActivity(intent)
                                Toast.makeText(
                                    context,
                                    "Please grant 'Draw over other apps' permission",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                            viewModel.setBubbleEnabled(enabled)
                        },
                        modifier = Modifier.testTag("floating_bubble_switch")
                    )
                }

                // Smart Safe Mode Row (Banking, Crypto, Passwords)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "Smart Safe Mode 🛡️",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Auto-blocks dictation and locks the bubble into a shield in banking, crypto wallets, and password apps to keep your sensitive info safe.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Switch(
                        checked = isSmartSafeModeEnabled,
                        onCheckedChange = { viewModel.setSmartSafeModeEnabled(it) },
                        modifier = Modifier.testTag("smart_safe_mode_switch")
                    )
                }

                // Overlay Permission Status Button
                val hasOverlayPermission = viewModel.canDrawOverlays()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Draw Over Other Apps",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = if (hasOverlayPermission) "✓ Permission granted" else "Required for floating bubble",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (hasOverlayPermission) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                        )
                    }
                    if (!hasOverlayPermission) {
                        FilledTonalButton(
                            onClick = {
                                val intent = Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:${context.packageName}")
                                )
                                context.startActivity(intent)
                            }
                        ) {
                            Text("Grant", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }

                // Accessibility Service Status Button
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Keyboard & Field Detector",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = if (isAccessibilityConnected) "✓ Service active" else "Required to detect the focused field and type",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isAccessibilityConnected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                        )
                    }
                    if (!isAccessibilityConnected) {
                        FilledTonalButton(
                            onClick = { showAccessibilityDisclosure = true }
                        ) {
                            Text("Turn On", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(20.dp))
            }
        },
        modifier = modifier.fillMaxSize()
    ) {
        Scaffold(
            bottomBar = {
                WisprFlowBottomNav(
                    selectedTab = selectedTab,
                    onTabSelected = { selectedTab = it },
                    onOpenStyle = { selectedTab = MainScreenTab.STYLE },
                    onOpenSnippets = { showDiagnostics = true }
                )
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
            modifier = modifier.fillMaxSize()
        ) { innerPadding ->
            when (selectedTab) {
                MainScreenTab.HOME -> {
                    HomeScreen(
                        viewModel = viewModel,
                        onOpenMenu = {
                            scope.launch {
                                if (drawerState.isClosed) drawerState.open() else drawerState.close()
                            }
                        },
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    )
                }
                MainScreenTab.DICTIONARY -> {
                    DictionaryScreen(
                        onOpenMenu = {
                            scope.launch {
                                if (drawerState.isClosed) drawerState.open() else drawerState.close()
                            }
                        },
                        draftTerm = dictionaryDraft,
                        onDraftConsumed = onDictionaryDraftConsumed,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    )
                }
                MainScreenTab.STYLE -> {
                    val chrome by FloatingBubbleManager.bubbleChrome.collectAsState()
                    val tone by FloatingBubbleManager.selectedColorTone.collectAsState()
                    val recordingNow by FloatingBubbleManager.isRecording.collectAsState()
                    com.princeniithompson.rapcha.ui.components.RecordingGlowScreen(
                        selectedStyleId = selectedGlowStyleId,
                        activeChrome = chrome,
                        tone = tone,
                        idle = !recordingNow,
                        onSelectStyle = { styleId ->
                            viewModel.setGlowStyle(styleId)
                        },
                        onChooseChrome = { style ->
                            FloatingBubbleManager.setBubbleChrome(context, style)
                        },
                        onChooseTone = { mode ->
                            FloatingBubbleManager.setColorTone(context, mode)
                        },
                        onDismiss = {
                            selectedTab = MainScreenTab.HOME
                        },
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    )
                }
            }
        }
    }
    }

    // Diagnostics Sheet
    if (showDiagnostics) {
        val recordings by viewModel.recordings.collectAsState()
        DiagnosticsBottomSheet(
            sheetState = diagnosticsSheetState,
            stats = stats,
            connectionState = connectionState,
            liveLogs = logs,
            diagnosticEntries = diagnosticEntries,
            notes = notes,
            recordings = recordings,
            isDictating = isRecording,
            selectedModel = selectedModel,
            apiKeyConfigured = apiKeyConfigured,
            customApiKey = activeApiKey,
            isSmartMode = isSmartMode,
            onCustomApiKeyChange = { viewModel.setCustomApiKey(it) },
            onAddNote = { viewModel.addNote(it) },
            onClearAllDiagnostics = { viewModel.clearAllDiagnostics() },
            onDeleteRecording = { viewModel.deleteRecording(it) },
            onClearAllRecordings = { viewModel.clearAllRecordings() },
            onDismiss = { showDiagnostics = false }
        )
    }

    if (showAccessibilityDisclosure) {
        AccessibilityDisclosureSheet(
            onAgree = {
                showAccessibilityDisclosure = false
                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                Toast.makeText(
                    context,
                    "Find 'Rapcha Voice Typing Assistant' and turn it ON",
                    Toast.LENGTH_LONG
                ).show()
            },
            onDecline = { showAccessibilityDisclosure = false }
        )
    }

    val selectedFinishingStyleId by viewModel.selectedFinishingStyleId.collectAsState()

    if (showGlowStylesSheet) {
        GlowStylesBottomSheet(
            sheetState = glowStylesSheetState,
            selectedStyleId = selectedGlowStyleId,
            selectedFinishingStyleId = selectedFinishingStyleId,
            onSelectStyle = { styleId ->
                viewModel.setGlowStyle(styleId)
            },
            onSelectFinishingStyle = { styleId ->
                viewModel.setFinishingStyle(styleId)
            },
            onDismiss = { showGlowStylesSheet = false }
        )
    }
}

@Composable
private fun SettingsApiKeyCard(
    apiKey: String,
    apiKeyConfigured: Boolean,
    signedInEmail: String,
    isProvisioning: Boolean,
    onSaveApiKey: (String) -> Unit,
    onAutoGetWithGoogle: () -> Unit
) {
    var draftKey by remember(apiKey) { mutableStateOf(apiKey) }
    var revealKey by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .testTag("settings_api_key_card"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (apiKeyConfigured) {
                MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.35f)
            } else {
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
            }
        ),
        border = BorderStroke(
            width = 1.dp,
            color = if (apiKeyConfigured) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
            } else {
                MaterialTheme.colorScheme.error.copy(alpha = 0.45f)
            }
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Key,
                        contentDescription = "Gemini API Key",
                        tint = if (apiKeyConfigured) Color(0xFF059669) else MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "Gemini API Key",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
                Text(
                    text = when {
                        apiKeyConfigured -> "Connected"
                        isProvisioning -> "Setting up..."
                        else -> "Required"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (apiKeyConfigured) Color(0xFF059669) else MaterialTheme.colorScheme.error
                )
            }

            if (signedInEmail.isNotBlank()) {
                Text(
                    text = "Google: $signedInEmail",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            } else if (!apiKeyConfigured) {
                Text(
                    text = "An API key is required for voice typing and polish.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            OutlinedTextField(
                value = draftKey,
                onValueChange = { draftKey = it },
                placeholder = { Text("AIzaSy... or AQ....") },
                singleLine = true,
                visualTransformation = if (revealKey) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { revealKey = !revealKey }) {
                        Icon(
                            imageVector = if (revealKey) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            contentDescription = if (revealKey) "Hide API key" else "Show API key"
                        )
                    }
                },
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("settings_api_key_input")
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = { onSaveApiKey(draftKey) },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("settings_save_api_key_button")
                ) {
                    Text("Save Key")
                }
                if (apiKeyConfigured) {
                    OutlinedButton(
                        onClick = {
                            draftKey = ""
                            onSaveApiKey("")
                        },
                        modifier = Modifier.testTag("settings_clear_api_key_button")
                    ) {
                        Text("Clear")
                    }
                } else {
                    FilledTonalButton(
                        onClick = onAutoGetWithGoogle,
                        modifier = Modifier.testTag("settings_auto_get_key_button")
                    ) {
                        Text("Get via Google")
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectionStatusChip(connectionState: ConnectionState) {
    val (label, bg, fg) = when (connectionState) {
        is ConnectionState.Streaming -> Triple("LIVE", Color(0xFF10B981).copy(alpha = 0.15f), Color(0xFF10B981))
        is ConnectionState.Connecting -> Triple("CONNECTING", Color(0xFFF59E0B).copy(alpha = 0.15f), Color(0xFFF59E0B))
        is ConnectionState.ConnectedWaitingSetup -> Triple("SETUP...", Color(0xFF3B82F6).copy(alpha = 0.15f), Color(0xFF3B82F6))
        is ConnectionState.Stopping -> Triple("STOPPING", Color(0xFF6B7280).copy(alpha = 0.15f), Color(0xFF6B7280))
        is ConnectionState.Error -> Triple("ERROR", MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.error)
        is ConnectionState.Idle -> Triple("IDLE", MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
    }

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .testTag("connection_status_chip")
    ) {
        Text(
            text = label,
            color = fg,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.5.sp
        )
    }
}

@Composable
private fun WisprFlowBottomNav(
    selectedTab: MainScreenTab,
    onTabSelected: (MainScreenTab) -> Unit,
    onOpenStyle: () -> Unit,
    onOpenSnippets: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.background,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("wispr_bottom_nav")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp, horizontal = 12.dp),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 1. Home Tab
            val isHome = selectedTab == MainScreenTab.HOME
            BottomNavItem(
                icon = Icons.Filled.Home,
                label = "Home",
                isSelected = isHome,
                onClick = { onTabSelected(MainScreenTab.HOME) },
                testTag = "nav_tab_home"
            )

            // 2. Dictionary Tab
            val isDictionary = selectedTab == MainScreenTab.DICTIONARY
            BottomNavItem(
                icon = Icons.Filled.Description,
                label = "Dictionary",
                isSelected = isDictionary,
                onClick = { onTabSelected(MainScreenTab.DICTIONARY) },
                testTag = "nav_tab_dictionary"
            )

            // 3. Style Tab
            val isStyle = selectedTab == MainScreenTab.STYLE
            BottomNavItem(
                icon = Icons.Filled.Palette,
                label = "Style",
                isSelected = isStyle,
                onClick = onOpenStyle,
                testTag = "nav_tab_style"
            )

            // 4. Snippets Tab
            BottomNavItem(
                icon = Icons.Filled.ContentCut,
                label = "Snippets",
                isSelected = false,
                onClick = onOpenSnippets,
                testTag = "nav_tab_snippets"
            )
        }
    }
}

@Composable
private fun BottomNavItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    testTag: String
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 4.dp)
            .testTag(testTag)
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(
                    if (isSelected) MaterialTheme.colorScheme.secondaryContainer
                    else Color.Transparent
                )
                .padding(horizontal = 16.dp, vertical = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (isSelected) MaterialTheme.colorScheme.onSecondaryContainer
                       else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp)
            )
        }
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = label,
            fontSize = 11.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
            color = if (isSelected) MaterialTheme.colorScheme.onSurface
                   else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
