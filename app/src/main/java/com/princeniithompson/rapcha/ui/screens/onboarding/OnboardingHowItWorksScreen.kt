package com.princeniithompson.rapcha.ui.screens.onboarding

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.princeniithompson.rapcha.R
import androidx.compose.material3.MaterialTheme
import com.princeniithompson.rapcha.ui.theme.EditorialSerifFontFamily
import com.princeniithompson.rapcha.ui.theme.OnboardingBorderStroke
import com.princeniithompson.rapcha.ui.theme.OnboardingCreamBackground
import com.princeniithompson.rapcha.ui.theme.OnboardingInkBlack
import com.princeniithompson.rapcha.ui.theme.OnboardingMutedText
import kotlin.math.cos
import kotlin.math.sin

/**
 * Screen 2: Matching Screenshot_20261010-024115 with animated sprinkle rays and center Lifesaver.
 */
@Composable
fun OnboardingHowItWorksScreen(
    onNext: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Sprinkle animation that bursts outwards and fades, leaving the bubble static
    val sprinkleScale = remember { Animatable(0.4f) }
    val sprinkleAlpha = remember { Animatable(1f) }

    LaunchedEffect(Unit) {
        sprinkleScale.animateTo(
            targetValue = 1.3f,
            animationSpec = tween(durationMillis = 1100, easing = FastOutSlowInEasing)
        )
        sprinkleAlpha.animateTo(
            targetValue = 0f,
            animationSpec = tween(durationMillis = 500)
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(OnboardingCreamBackground)
            .padding(horizontal = 24.dp)
            .testTag("onboarding_how_it_works_screen")
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(28.dp))

            // Headline
            Text(
                text = "How it works",
                fontFamily = EditorialSerifFontFamily,
                fontSize = 34.sp,
                fontWeight = FontWeight.Normal,
                color = OnboardingInkBlack,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(14.dp))

            // Subtitle
            Text(
                text = "Use Rapcha in any app with\nthe Lifesaver Bubble",
                fontSize = 19.sp,
                fontWeight = FontWeight.Medium,
                lineHeight = 27.sp,
                color = OnboardingMutedText,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 16.dp)
            )

            // Center area with Lifesaver and Radiating Sprinkle Ray Animation
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                // Radiating Sprinkle Rays
                Canvas(modifier = Modifier.size(240.dp)) {
                    val radiusBase = 68.dp.toPx() * sprinkleScale.value
                    val rayLength = 14.dp.toPx()
                    val rayColor = Color(0xFFF59E0B).copy(alpha = sprinkleAlpha.value)

                    for (i in 0 until 8) {
                        val angle = (i * 45f) * (Math.PI / 180f)
                        val startX = center.x + (radiusBase * cos(angle)).toFloat()
                        val startY = center.y + (radiusBase * sin(angle)).toFloat()
                        val endX = center.x + ((radiusBase + rayLength) * cos(angle)).toFloat()
                        val endY = center.y + ((radiusBase + rayLength) * sin(angle)).toFloat()

                        drawLine(
                            color = rayColor,
                            start = Offset(startX, startY),
                            end = Offset(endX, endY),
                            strokeWidth = 3.5.dp.toPx(),
                            cap = StrokeCap.Round
                        )
                    }
                }

                // Center Lifesaver Bubble (Enlarged, plain interface with no violet background)
                Image(
                    painter = painterResource(id = R.drawable.ic_lifebuoy_ring),
                    contentDescription = "Rapcha Lifesaver Bubble",
                    modifier = Modifier.size(92.dp)
                )
            }

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
                    .testTag("btn_how_it_works_next")
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
