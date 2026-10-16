package com.princeniithompson.rapcha.ui.screens.onboarding

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.princeniithompson.rapcha.ui.theme.EditorialSerifFontFamily
import com.princeniithompson.rapcha.ui.theme.OnboardingBorderStroke
import com.princeniithompson.rapcha.ui.theme.OnboardingCreamBackground
import com.princeniithompson.rapcha.ui.theme.OnboardingInkBlack

/**
 * Screen 1: Editorial hero card with dynamic colors and adaptive Next button.
 */
@Composable
fun OnboardingIntroScreen(
    onNext: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(OnboardingCreamBackground)
            .padding(horizontal = 24.dp)
            .testTag("onboarding_intro_screen")
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            // Large Hero Card (Soft Dynamic Color)
            Card(
                shape = RoundedCornerShape(28.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    // Decorative Cream Ribbon Loop at the bottom of the card
                    Canvas(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(260.dp)
                            .align(Alignment.BottomCenter)
                    ) {
                        val path = Path().apply {
                            moveTo(-20f, size.height * 0.45f)
                            cubicTo(
                                size.width * 0.35f, size.height * 0.1f,
                                size.width * 0.55f, size.height * 0.95f,
                                size.width * 0.45f, size.height * 0.75f
                            )
                            cubicTo(
                                size.width * 0.35f, size.height * 0.55f,
                                size.width * 0.65f, size.height * 0.35f,
                                size.width + 30f, size.height * 0.7f
                            )
                        }
                        drawPath(
                            path = path,
                            color = Color(0xFFFFF9E6),
                            style = Stroke(width = 44f, cap = StrokeCap.Round)
                        )
                    }

                    // Headline in Editorial Serif Typography
                    Text(
                        text = "Rapcha turns your voice into\nperfectly formatted\ntext in every app",
                        fontFamily = EditorialSerifFontFamily,
                        fontSize = 32.sp,
                        lineHeight = 42.sp,
                        fontWeight = FontWeight.Normal,
                        color = OnboardingInkBlack,
                        modifier = Modifier
                            .padding(horizontal = 24.dp, vertical = 32.dp)
                            .align(Alignment.TopStart)
                    )
                }
            }

            Spacer(modifier = Modifier.height(28.dp))

            // Bottom Action Button (Soft dynamic color with crisp border)
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
                    .testTag("btn_intro_next")
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
