package com.example.ui.screens.onboarding

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Notes
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.MaterialTheme
import com.example.R
import com.example.ui.theme.EditorialSerifFontFamily
import com.example.ui.theme.OnboardingBorderStroke
import com.example.ui.theme.OnboardingCreamBackground
import com.example.ui.theme.OnboardingInkBlack

/**
 * Screen 8: Matching Screenshot_20261010-024753.
 * Illustrates that VoxStream floats seamlessly over WhatsApp, Instagram, Pinterest, TikTok, and 100,000+ apps.
 */
@Composable
fun OnboardingAppsShowcaseScreen(
    onNext: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(OnboardingCreamBackground)
            .padding(horizontal = 24.dp)
            .testTag("onboarding_apps_showcase_screen")
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
                text = "The Lifesaver Bubble works in\nover 100,000 apps!",
                fontFamily = EditorialSerifFontFamily,
                fontSize = 32.sp,
                lineHeight = 40.sp,
                fontWeight = FontWeight.Normal,
                color = OnboardingInkBlack,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(20.dp))

            // Center Card: Mock phone app with docked Lifesaver Bubble
            Card(
                shape = RoundedCornerShape(26.dp),
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFF5C2D0C) // Rich warm amber-brown matching Screenshot_20261010-024753
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 4.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(18.dp),
                    contentAlignment = Alignment.Center
                ) {
                    // App Badges Bar at the top of the card
                    Row(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        AppBadge("WhatsApp", Color(0xFF25D366), Icons.Default.Chat)
                        AppBadge("Instagram", Color(0xFFE1306C), Icons.Default.Share)
                        AppBadge("Pinterest", Color(0xFFBD081C), Icons.Default.Lightbulb)
                        AppBadge("Gmail", Color(0xFFEA4335), Icons.Default.Email)
                        AppBadge("Notion", Color(0xFF000000), Icons.Default.Notes)
                    }

                    // Mock phone surface inside the card
                    Card(
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = Color(0xFF1E1E1E)
                        ),
                        modifier = Modifier
                            .fillMaxWidth(0.85f)
                            .height(260.dp)
                            .align(Alignment.BottomCenter)
                    ) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(14.dp)
                            ) {
                                Text(
                                    text = "💡 Product Ideas & Notes",
                                    color = Color.White,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                                Text(
                                    text = "• Voice-first productivity\n• Speak naturally, inserts instantly\n• Works over any text box",
                                    color = Color(0xFFCCCCCC),
                                    fontSize = 12.sp,
                                    lineHeight = 18.sp
                                )
                            }

                            // The Lifesaver Bubble docked on the right side with orange focus ring
                            Box(
                                modifier = Modifier
                                    .align(Alignment.CenterEnd)
                                    .padding(end = 10.dp)
                                    .size(62.dp)
                                    .border(3.dp, Color(0xFFF59E0B), CircleShape)
                                    .padding(4.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFFE8DEF8)),
                                contentAlignment = Alignment.Center
                            ) {
                                Image(
                                    painter = painterResource(id = R.drawable.ic_lifebuoy_ring),
                                    contentDescription = "Docked Lifesaver",
                                    modifier = Modifier.size(38.dp)
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Bottom Action Button
            Button(
                onClick = onNext,
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.5.dp, OnboardingBorderStroke),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .testTag("btn_apps_next")
            ) {
                Text(
                    text = "Next",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
    }
}

@Composable
private fun AppBadge(
    name: String,
    badgeColor: Color,
    icon: androidx.compose.ui.graphics.vector.ImageVector
) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(badgeColor),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = name,
            tint = Color.White,
            modifier = Modifier.size(18.dp)
        )
    }
}
