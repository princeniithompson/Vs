package com.princeniithompson.rapcha.ui.screens.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.princeniithompson.rapcha.ui.theme.OnboardingProgressTrack

/**
 * Renders the top segmented progress bar using dynamic color (deep primary tone).
 */
@Composable
fun OnboardingTopBar(
    currentSegment: Int,
    totalSegments: Int = 6,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        val activeColor = MaterialTheme.colorScheme.primary
        for (i in 0 until totalSegments) {
            val isActive = i <= currentSegment
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(3.5.dp)
                    .clip(CircleShape)
                    .background(
                        if (isActive) activeColor else OnboardingProgressTrack
                    )
            )
        }
    }
}
