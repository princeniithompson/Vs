package com.example.ui

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
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Terminal
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.data.ConnectionState
import com.example.data.CustomVocabularyRepository
import com.example.ui.components.AudioWaveformVisualizer
import com.example.ui.components.DiagnosticsBottomSheet
import com.example.ui.components.GlowAnimationCatalogue
import com.example.ui.components.GlowStylesBottomSheet
import com.example.ui.components.PulsatingRecordButton
import com.example.ui.components.SmartVocabularyBottomSheet
import com.example.ui.screens.DictionaryScreen
import com.example.ui.screens.HomeScreen
import kotlinx.coroutines.launch

enum class MainScreenTab {
    HOME,
    DICTIONARY
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceTypingScreen(
    viewModel: VoiceTypingViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val isRecording by viewModel.isRecording.collectAsState()
    val isSmartMode by viewModel.isSmartMode.collectAsState()
    val isBubbleEnabled by viewModel.isBubbleEnabled.collectAsState()
    val isSmartSafeModeEnabled by viewModel.isSmartSafeModeEnabled.collectAsState()
    val isAccessibilityConnected by viewModel.isAccessibilityConnected.collectAsState()
    val isAecEnabled by viewModel.isAecEnabled.collectAsState()
    val isNoiseSuppressorEnabled by viewModel.isNoiseSuppressorEnabled.collectAsState()
    val isAecSupported = viewModel.isAecSupported
    val isNoiseSuppressorSupported = viewModel.isNoiseSuppressorSupported
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
    val selectedGlowStyleId by viewModel.selectedGlowStyleId.collectAsState()

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    var selectedTab by remember { mutableStateOf(MainScreenTab.HOME) }
    var showDiagnostics by remember { mutableStateOf(false) }
    val diagnosticsSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showGlowStylesSheet by remember { mutableStateOf(false) }
    val glowStylesSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val isAnalyzingSmartVocab by viewModel.isAnalyzingSmartVocab.collectAsState()
    val smartVocabMessage by viewModel.smartVocabMessage.collectAsState()
    val showSmartVocabSheet by viewModel.showSmartVocabSheet.collectAsState()
    val smartVocabSuggestions by viewModel.smartVocabSuggestions.collectAsState()
    val smartVocabSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

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
                            color = if (isRecording) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                                    else MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "On: cleans up filler words and adds punctuation. Off: raw verbatim transcript, fastest.",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isRecording) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Switch(
                        checked = isSmartMode,
                        onCheckedChange = { viewModel.setSmartMode(it) },
                        enabled = !isRecording,
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
                            text = if (isAccessibilityConnected) "✓ Service active" else "Required to auto-show bubble & paste text",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isAccessibilityConnected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                        )
                    }
                    if (!isAccessibilityConnected) {
                        FilledTonalButton(
                            onClick = {
                                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                context.startActivity(intent)
                                Toast.makeText(
                                    context,
                                    "Find 'VoxStream Voice Typing Assistant' and turn it ON",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        ) {
                            Text("Turn On", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))

                // Microphone Audio Enhancements Section
                Text(
                    text = "Microphone Filters 🎙️",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                )

                // Acoustic Echo Canceler Row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Acoustic Echo Canceler",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Filters device speaker audio (TTS, media) from looping into the mic.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = if (isAecSupported) "✓ Supported on this device" else "✗ Not supported by hardware",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Medium,
                            color = if (isAecSupported) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                        )
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Switch(
                        checked = isAecEnabled && isAecSupported,
                        onCheckedChange = { viewModel.setAecEnabled(it) },
                        enabled = isAecSupported && !isRecording,
                        modifier = Modifier.testTag("aec_switch")
                    )
                }

                // Noise Suppressor Row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Noise Suppressor",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Reduces continuous background noise like fans, AC, and room hum.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = if (isNoiseSuppressorSupported) "✓ Supported on this device" else "✗ Not supported by hardware",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Medium,
                            color = if (isNoiseSuppressorSupported) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                        )
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Switch(
                        checked = isNoiseSuppressorEnabled && isNoiseSuppressorSupported,
                        onCheckedChange = { viewModel.setNoiseSuppressorEnabled(it) },
                        enabled = isNoiseSuppressorSupported && !isRecording,
                        modifier = Modifier.testTag("noise_suppressor_switch")
                    )
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
                    onOpenStyle = { showGlowStylesSheet = true },
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
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    )
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
            apiKeyConfigured = viewModel.isApiKeyConfigured(),
            customApiKey = customApiKey,
            isSmartMode = isSmartMode,
            onCustomApiKeyChange = { viewModel.setCustomApiKey(it) },
            onAddNote = { viewModel.addNote(it) },
            onClearAllDiagnostics = { viewModel.clearAllDiagnostics() },
            onDeleteRecording = { viewModel.deleteRecording(it) },
            onClearAllRecordings = { viewModel.clearAllRecordings() },
            isAnalyzingSmartVocab = isAnalyzingSmartVocab,
            onSimulateWeeklySmartVocab = { viewModel.simulateWeeklySmartVocabulary() },
            onDismiss = { showDiagnostics = false }
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

    // Weekly Smart Vocabulary Bottom Sheet
    if (showSmartVocabSheet) {
        SmartVocabularyBottomSheet(
            sheetState = smartVocabSheetState,
            suggestions = smartVocabSuggestions,
            onAccept = { viewModel.acceptSmartVocabSuggestion(it) },
            onEdit = { suggestion, newTerm -> viewModel.editAndAcceptSmartVocabSuggestion(suggestion, newTerm) },
            onDismissItem = { viewModel.dismissSmartVocabSuggestion(it) },
            onAcceptAll = { viewModel.acceptAllSmartVocabSuggestions() },
            onDismissSheet = { viewModel.dismissSmartVocabSheet() }
        )
    }

    // Smart Vocabulary Informational Alert (Empty state or errors)
    if (smartVocabMessage != null) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { viewModel.clearSmartVocabMessage() },
            title = {
                Text(
                    text = "Weekly Smart Vocabulary",
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            },
            text = {
                Text(
                    text = smartVocabMessage ?: "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                Button(
                    onClick = { viewModel.clearSmartVocabMessage() },
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("OK")
                }
            },
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(20.dp)
        )
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
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("wispr_bottom_nav")
    ) {
        Column {
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                thickness = 0.8.dp
            )
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
                BottomNavItem(
                    icon = Icons.Filled.FormatSize,
                    label = "Style",
                    isSelected = false,
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
