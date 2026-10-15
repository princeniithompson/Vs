package com.example.ui.screens.onboarding

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.ui.theme.EditorialSerifFontFamily
import com.example.ui.theme.OnboardingBorderStroke
import com.example.ui.theme.OnboardingCreamBackground
import com.example.ui.theme.OnboardingInkBlack
import com.example.ui.theme.OnboardingMutedText

/**
 * Screens 12 & 13: Notification prompt ("Don't speak into the void") and final completion ("You're all set!").
 */
@Composable
fun OnboardingNotificationScreen(
    onFinish: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showAllSet by remember { mutableStateOf(false) }

    val notificationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) {
        showAllSet = true
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(OnboardingCreamBackground)
            .padding(horizontal = 24.dp)
            .testTag("onboarding_notification_screen")
    ) {
        if (!showAllSet) {
            // Notification Screen: "Don't speak into the void" (Screenshot_20261010-025245)
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(modifier = Modifier.height(28.dp))

                Text(
                    text = "Don't speak into the void",
                    fontFamily = EditorialSerifFontFamily,
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Normal,
                    color = OnboardingInkBlack,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "Get notified instantly if anything interrupts your speech",
                    fontSize = 17.sp,
                    lineHeight = 24.sp,
                    color = OnboardingMutedText,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )

                // Illustration in Center
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Card(
                            shape = CircleShape,
                            colors = CardDefaults.cardColors(containerColor = Color(0xFFFDE8E8)),
                            modifier = Modifier.size(160.dp)
                        ) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.Notifications,
                                    contentDescription = "Notifications",
                                    tint = Color(0xFFE53935),
                                    modifier = Modifier.size(72.dp)
                                )
                            }
                        }
                    }
                }

                // Buttons
                Button(
                    onClick = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            showAllSet = true
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
                        .testTag("btn_allow_notifications")
                ) {
                    Text("Allow notifications", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                }

                Spacer(modifier = Modifier.height(12.dp))

                Button(
                    onClick = { showAllSet = true },
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.5.dp, OnboardingBorderStroke),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White,
                        contentColor = OnboardingInkBlack
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp)
                        .testTag("btn_skip_notifications")
                ) {
                    Text("Skip", fontSize = 17.sp, fontWeight = FontWeight.Medium, color = OnboardingInkBlack)
                }
            }
        } else {
            // Final Screen: "You're all set!" (Matching 08:21 in video)
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(modifier = Modifier.height(32.dp))

                Text(
                    text = "You're all set!",
                    fontFamily = EditorialSerifFontFamily,
                    fontSize = 34.sp,
                    color = OnboardingInkBlack,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(14.dp))

                Text(
                    text = "Here's what to expect...",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium,
                    color = OnboardingMutedText,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(24.dp))

                Card(
                    shape = RoundedCornerShape(22.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFF3EDF7)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = "1. You won't see the Lifesaver Bubble right away.",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = OnboardingInkBlack
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "2. It will appear automatically when your text box is active in any app.",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = OnboardingInkBlack
                        )
                        Spacer(modifier = Modifier.height(28.dp))
                        Box(
                            modifier = Modifier.fillMaxWidth(),
                            contentAlignment = Alignment.Center
                        ) {
                            Image(
                                painter = painterResource(id = R.drawable.ic_lifebuoy_ring),
                                contentDescription = "Ready Lifesaver",
                                modifier = Modifier.size(76.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                Button(
                    onClick = onFinish,
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.5.dp, OnboardingBorderStroke),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp)
                        .testTag("btn_onboarding_finish")
                ) {
                    Text("Finish", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
        }
    }
}
