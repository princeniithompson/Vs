package com.princeniithompson.rapcha.ui.screens.onboarding

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.princeniithompson.rapcha.R

/**
 * Post-login handoff screen matching the clean "Log in as <email> / Open Rapcha" step
 * before transitioning into Rapcha's existing onboarding tour while key setup finishes silently.
 */
@Composable
fun OnboardingGoogleHandoffScreen(
    email: String,
    onOpenRapcha: () -> Unit,
    onUseAnotherAccount: () -> Unit,
    modifier: Modifier = Modifier
) {
    val cleanEmail = email.trim()

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(Color.White)
            .safeDrawingPadding()
            .testTag("onboarding_google_handoff_screen")
    ) {
        val isTablet = maxWidth >= 600.dp
        val isCompactHeight = maxHeight < 640.dp
        val horizontalPadding = if (isTablet) 40.dp else 24.dp
        val logoSize = if (isTablet) 72.dp else 62.dp
        val headlineFontSize = if (isTablet) 26.sp else if (isCompactHeight) 20.sp else 22.sp
        val emailFontSize = if (isTablet) 24.sp else if (isCompactHeight) 18.sp else 20.sp

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = horizontalPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 460.dp)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Image(
                    painter = painterResource(id = R.drawable.ic_lifebuoy_ring),
                    contentDescription = stringResource(id = R.string.app_name),
                    modifier = Modifier.size(logoSize)
                )

                Spacer(modifier = Modifier.height(20.dp))

                if (cleanEmail.isNotEmpty()) {
                    Text(
                        text = "Log in as",
                        fontSize = headlineFontSize,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF141318),
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = cleanEmail,
                        fontSize = emailFontSize,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF141318),
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                } else {
                    Text(
                        text = "Google account ready",
                        fontSize = headlineFontSize,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF141318),
                        textAlign = TextAlign.Center
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                Button(
                    onClick = onOpenRapcha,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF141318),
                        contentColor = Color.White
                    ),
                    modifier = Modifier
                        .widthIn(min = 200.dp, max = 280.dp)
                        .height(52.dp)
                        .testTag("btn_open_rapcha")
                ) {
                    Text(
                        text = "Open Rapcha",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                val switchPrompt = buildAnnotatedString {
                    if (cleanEmail.isNotEmpty()) {
                        withStyle(SpanStyle(color = Color(0xFF6B7280))) {
                            append("Not $cleanEmail? ")
                        }
                    }
                    withStyle(
                        SpanStyle(
                            color = Color(0xFF141318),
                            fontWeight = FontWeight.Medium,
                            textDecoration = TextDecoration.Underline
                        )
                    ) {
                        append("Log in with another Google account")
                    }
                }

                Box(
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onUseAnotherAccount)
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                        .testTag("btn_switch_google_account"),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = switchPrompt,
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}
