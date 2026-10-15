package com.example.ui.screens.onboarding

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.ui.theme.EditorialSerifFontFamily
import com.example.ui.theme.OnboardingBorderStroke
import com.example.ui.theme.OnboardingCreamBackground
import com.example.ui.theme.OnboardingInkBlack
import com.example.ui.theme.OnboardingMutedText

/**
 * Screens 10 & 11: Matching Screenshot_20261010-024912 and Screenshot_20261010-025020.
 * Accessibility guide with step-by-step visual cards and the full transparency disclosure sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingAccessibilityGuideScreen(
    isAccessibilityConnected: Boolean,
    onNext: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var isConnected by remember {
        mutableStateOf(isAccessibilityConnected || com.example.service.FloatingBubbleManager.isAccessibilityServiceEnabled(context))
    }

    LaunchedEffect(isAccessibilityConnected) {
        if (isAccessibilityConnected) {
            isConnected = true
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val enabled = com.example.service.FloatingBubbleManager.isAccessibilityServiceEnabled(context) ||
                    com.example.service.VoxStreamAccessibilityService.instance != null
                if (enabled) {
                    isConnected = true
                    com.example.service.FloatingBubbleManager.setAccessibilityConnected(true)
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    var showDisclosureSheet by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(OnboardingCreamBackground)
            .padding(horizontal = 24.dp)
            .testTag("onboarding_accessibility_screen")
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(24.dp))

            // Headline
            Text(
                text = "Allow VoxStream to paste\nin your dictations",
                fontFamily = EditorialSerifFontFamily,
                fontSize = 32.sp,
                lineHeight = 40.sp,
                fontWeight = FontWeight.Normal,
                color = OnboardingInkBlack,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(20.dp))

            // Scrollable Instructions Cards
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Step 1
                Text(
                    text = "1. Locate the list of apps that has accessibility permissions",
                    fontSize = 15.sp,
                    color = OnboardingInkBlack,
                    fontWeight = FontWeight.Medium
                )
                Card(
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFF3EDF7)),
                    modifier = Modifier
                        .fillMaxWidth(0.7f)
                        .padding(start = 12.dp)
                ) {
                    Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Text("Downloaded apps", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = OnboardingInkBlack)
                    }
                }

                // Step 2
                Text(
                    text = "2. Locate this toggle and turn it on",
                    fontSize = 15.sp,
                    color = OnboardingInkBlack,
                    fontWeight = FontWeight.Medium
                )
                Card(
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFF3EDF7)),
                    modifier = Modifier
                        .fillMaxWidth(0.7f)
                        .padding(start = 12.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("‹ VoxStream\nOn", fontSize = 13.sp, color = OnboardingInkBlack, lineHeight = 18.sp)
                        Switch(
                            checked = true,
                            onCheckedChange = null,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = Color(0xFF388E3C)
                            )
                        )
                    }
                }

                // Step 3
                Text(
                    text = "3. Don't turn the shortcut on!",
                    fontSize = 15.sp,
                    color = OnboardingInkBlack,
                    fontWeight = FontWeight.Medium
                )
                Card(
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFF3EDF7)),
                    modifier = Modifier
                        .fillMaxWidth(0.7f)
                        .padding(start = 12.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("VoxStream shortcut", fontSize = 13.sp, color = OnboardingInkBlack)
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Don't turn on",
                            tint = Color(0xFFD32F2F),
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            // Bottom Action Button
            Button(
                onClick = {
                    if (isConnected) {
                        onNext()
                    } else {
                        showDisclosureSheet = true
                    }
                },
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.5.dp, OnboardingBorderStroke),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .testTag("btn_accessibility_open_settings")
            ) {
                Text(
                    text = if (isConnected) "Continue" else "Open settings",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
    }

    // Full Transparency & Safety Disclosure Sheet (Screenshot_20261010-025020)
    if (showDisclosureSheet) {
        ModalBottomSheet(
            onDismissRequest = { showDisclosureSheet = false },
            sheetState = sheetState,
            containerColor = Color.White
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 32.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = "Accessibility Permissions\nRequired",
                    fontFamily = EditorialSerifFontFamily,
                    fontSize = 30.sp,
                    lineHeight = 38.sp,
                    color = OnboardingInkBlack,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                DisclosureSection(
                    title = "We limit what we access",
                    body = "VoxStream uses information like the app or website you're in to format your dictations as emails or messages, and appears if a text field is active. We do not appear in sensitive fields, such as credit card forms or password fields."
                )

                DisclosureSection(
                    title = "We respect your privacy",
                    body = "Your data stays on your device. We do not sell or share dictation data for external training."
                )

                DisclosureSection(
                    title = "Why VoxStream needs this",
                    body = "VoxStream inserts your voice dictations directly into emails, messages, documents, and other apps. To insert text accurately, we need permission to access your screen."
                )

                DisclosureSection(
                    title = "What VoxStream accesses",
                    body = "• The text visible on your screen and the layout of text fields (to detect where to insert your voice)\n• The name and URL of apps and websites you're using (to understand context e.g. if you're in Gmail, we format as an email)\n• Limited text box contents from the active app to improve transcription\n• Information about the input field, to prevent VoxStream from appearing in sensitive applications such as banking apps and password fields"
                )

                DisclosureSection(
                    title = "How we protect your data",
                    body = "• VoxStream does NOT insert text into passwords or credit card fields\n• VoxStream does NOT appear in banking apps or password managers\n• You can control whether your data is used to improve models\n• You can turn this off anytime in Settings"
                )

                Spacer(modifier = Modifier.height(20.dp))

                Button(
                    onClick = {
                        showDisclosureSheet = false
                        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        context.startActivity(intent)
                    },
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.5.dp, OnboardingBorderStroke),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .testTag("btn_disclosure_agree")
                ) {
                    Text("Agree", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                }

                Spacer(modifier = Modifier.height(8.dp))

                Button(
                    onClick = { showDisclosureSheet = false },
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.5.dp, OnboardingBorderStroke),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White,
                        contentColor = OnboardingInkBlack
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                ) {
                    Text("Not now", fontSize = 17.sp, fontWeight = FontWeight.Medium, color = OnboardingInkBlack)
                }
            }
        }
    }
}

@Composable
private fun DisclosureSection(title: String, body: String) {
    Column(modifier = Modifier.padding(vertical = 10.dp)) {
        Text(
            text = title,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            color = OnboardingInkBlack
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = body,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            color = Color(0xFF333333)
        )
    }
}
