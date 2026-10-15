package com.example.ui.screens.onboarding

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.R
import androidx.compose.material3.MaterialTheme
import com.example.ui.theme.EditorialSerifFontFamily
import com.example.ui.theme.OnboardingBorderStroke
import com.example.ui.theme.OnboardingCreamBackground
import com.example.ui.theme.OnboardingInkBlack
import com.example.ui.theme.OnboardingMutedText

/**
 * Screen 9: Matching Screenshot_20261010-024825.
 * Guides the user to allow "Display over other apps".
 */
@Composable
fun OnboardingOverlayPermissionScreen(
    canDrawOverlays: Boolean,
    onNext: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var isPermissionAllowed by remember {
        mutableStateOf(canDrawOverlays || Settings.canDrawOverlays(context))
    }

    LaunchedEffect(canDrawOverlays) {
        if (canDrawOverlays) {
            isPermissionAllowed = true
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isPermissionAllowed = Settings.canDrawOverlays(context)
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
            .padding(horizontal = 24.dp)
            .testTag("onboarding_overlay_screen")
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
                text = "Allow VoxStream to\nappear over other apps",
                fontFamily = EditorialSerifFontFamily,
                fontSize = 32.sp,
                lineHeight = 40.sp,
                fontWeight = FontWeight.Normal,
                color = OnboardingInkBlack,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Subhead
            Text(
                text = "It can't work without this",
                fontSize = 17.sp,
                fontWeight = FontWeight.Normal,
                color = OnboardingMutedText,
                textAlign = TextAlign.Center
            )

            // Center Illustration: Lifesaver bubble with curved arrow pointing to Settings toggle
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    // Small Lifesaver bubble card
                    Card(
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = Color(0xFFF3EDF7)
                        ),
                        modifier = Modifier.size(54.dp)
                    ) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Image(
                                painter = painterResource(id = R.drawable.ic_lifebuoy_ring),
                                contentDescription = "Lifesaver",
                                modifier = Modifier.size(34.dp)
                            )
                        }
                    }

                    // Orange curved guide arrow
                    Canvas(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                    ) {
                        val path = Path().apply {
                            moveTo(size.width * 0.35f, 10f)
                            cubicTo(
                                size.width * 0.45f, 40f,
                                size.width * 0.65f, 20f,
                                size.width * 0.72f, size.height - 4f
                            )
                        }
                        drawPath(
                            path = path,
                            color = Color(0xFFF59E0B),
                            style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
                        )
                        // Arrow tip
                        drawLine(
                            color = Color(0xFFF59E0B),
                            start = androidx.compose.ui.geometry.Offset(size.width * 0.72f, size.height - 4f),
                            end = androidx.compose.ui.geometry.Offset(size.width * 0.68f, size.height - 18f),
                            strokeWidth = 3.dp.toPx(),
                            cap = StrokeCap.Round
                        )
                    }

                    // Mock Android Settings Card
                    Card(
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = Color(0xFFECE6F0)
                        ),
                        modifier = Modifier
                            .fillMaxWidth(0.9f)
                            .padding(top = 4.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = "VoxStream",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = OnboardingInkBlack,
                                modifier = Modifier.align(Alignment.CenterHorizontally)
                            )

                            Spacer(modifier = Modifier.height(10.dp))

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color.White, RoundedCornerShape(12.dp))
                                    .clickable {
                                        if (!isPermissionAllowed) {
                                            val intent = Intent(
                                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                                Uri.parse("package:${context.packageName}")
                                            )
                                            context.startActivity(intent)
                                        }
                                    }
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Display over other apps",
                                    fontSize = 14.sp,
                                    color = OnboardingInkBlack
                                )
                                Switch(
                                    checked = isPermissionAllowed,
                                    onCheckedChange = {
                                        if (!isPermissionAllowed) {
                                            val intent = Intent(
                                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                                Uri.parse("package:${context.packageName}")
                                            )
                                            context.startActivity(intent)
                                        }
                                    },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = Color.White,
                                        checkedTrackColor = Color(0xFF388E3C)
                                    )
                                )
                            }
                        }
                    }
                }
            }

            // Bottom Action Button
            Button(
                onClick = {
                    if (isPermissionAllowed) {
                        onNext()
                    } else {
                        val intent = Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${context.packageName}")
                        )
                        context.startActivity(intent)
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
                    .testTag("btn_overlay_open_settings")
            ) {
                Text(
                    text = if (isPermissionAllowed) "Continue" else "Open Settings",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
    }
}
