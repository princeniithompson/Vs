package com.princeniithompson.rapcha.ui.components

import android.provider.Settings
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.princeniithompson.rapcha.ui.MainScreenTab

/**
 * Modern Floating Navigation Bar (App-wide)
 *
 * Requirements:
 * - 16dp inset from left and right edges, 64dp tall, fully rounded (percent = 50).
 * - Sits 12dp above the gesture or system inset.
 * - Surface = Home's card surface at ~94% alpha.
 * - Light mode: Soft shadow. Dark mode: Tonal with faint 1dp top highlight (white ~8%).
 * - Selected item: Tonal pill showing icon + 13sp medium label, sliding open with spring over ~300ms.
 * - Unselected items: 24dp outlined icon in onSurfaceVariant.
 * - Touch targets >= 48dp. Content descriptions provided for accessibility.
 * - Respects system "remove animations" setting.
 */
@Composable
fun ModernFloatingBottomNav(
    selectedTab: MainScreenTab,
    onTabSelected: (MainScreenTab) -> Unit,
    onOpenSnippets: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f

    // Check system animation scale
    val animationsEnabled = remember(context) {
        try {
            val scale = Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1.0f
            )
            scale > 0.01f
        } catch (_: Exception) {
            true
        }
    }

    val cardSurfaceColor = if (isDark) Color(0xFF16151F) else Color.White
    val navSurfaceColor = cardSurfaceColor.copy(alpha = 0.94f)

    val shape = RoundedCornerShape(percent = 50)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(bottom = 12.dp)
            .padding(horizontal = 16.dp)
            .height(64.dp)
            .then(
                if (!isDark) {
                    Modifier.shadow(
                        elevation = 8.dp,
                        shape = shape,
                        ambientColor = Color(0x1F000000),
                        spotColor = Color(0x1F000000)
                    )
                } else Modifier
            )
            .clip(shape)
            .background(navSurfaceColor)
            .then(
                if (isDark) {
                    Modifier.border(1.dp, Color.White.copy(alpha = 0.08f), shape)
                } else Modifier
            )
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 1. Home
            FloatingNavItem(
                label = "Home",
                filledIcon = Icons.Filled.Home,
                outlinedIcon = Icons.Outlined.Home,
                isSelected = selectedTab == MainScreenTab.HOME,
                animationsEnabled = animationsEnabled,
                onClick = { onTabSelected(MainScreenTab.HOME) },
                testTag = "nav_tab_home"
            )

            // 2. Dictionary
            FloatingNavItem(
                label = "Dictionary",
                filledIcon = Icons.Filled.Description,
                outlinedIcon = Icons.Outlined.Description,
                isSelected = selectedTab == MainScreenTab.DICTIONARY,
                animationsEnabled = animationsEnabled,
                onClick = { onTabSelected(MainScreenTab.DICTIONARY) },
                testTag = "nav_tab_dictionary"
            )

            // 3. Style
            FloatingNavItem(
                label = "Style",
                filledIcon = Icons.Filled.Palette,
                outlinedIcon = Icons.Outlined.Palette,
                isSelected = selectedTab == MainScreenTab.STYLE,
                animationsEnabled = animationsEnabled,
                onClick = { onTabSelected(MainScreenTab.STYLE) },
                testTag = "nav_tab_style"
            )

            // 4. Snippets
            FloatingNavItem(
                label = "Snippets",
                filledIcon = Icons.Filled.ContentCut,
                outlinedIcon = Icons.Outlined.ContentCut,
                isSelected = false,
                animationsEnabled = animationsEnabled,
                onClick = onOpenSnippets,
                testTag = "nav_tab_snippets"
            )
        }
    }
}

@Composable
private fun FloatingNavItem(
    label: String,
    filledIcon: ImageVector,
    outlinedIcon: ImageVector,
    isSelected: Boolean,
    animationsEnabled: Boolean,
    onClick: () -> Unit,
    testTag: String
) {
    val interactionSource = remember { MutableInteractionSource() }

    val iconScale by animateFloatAsState(
        targetValue = if (isSelected) 1f else 0.92f,
        animationSpec = if (animationsEnabled) {
            spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMediumLow)
        } else {
            tween(0)
        },
        label = "iconScale"
    )

    val itemShape = RoundedCornerShape(percent = 50)
    val pillBg = MaterialTheme.colorScheme.primaryContainer
    val selectedTextColor = MaterialTheme.colorScheme.onPrimaryContainer
    val unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant

    Box(
        modifier = Modifier
            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            .fillMaxHeight()
            .clip(itemShape)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .testTag(testTag),
        contentAlignment = Alignment.Center
    ) {
        if (isSelected) {
            Row(
                modifier = Modifier
                    .clip(itemShape)
                    .background(pillBg)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = filledIcon,
                    contentDescription = label,
                    tint = selectedTextColor,
                    modifier = Modifier
                        .size(24.dp)
                        .graphicsLayer {
                            scaleX = iconScale
                            scaleY = iconScale
                        }
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = label,
                    color = selectedTextColor,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        } else {
            Box(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = outlinedIcon,
                    contentDescription = label,
                    tint = unselectedIconColor,
                    modifier = Modifier
                        .size(24.dp)
                        .graphicsLayer {
                            scaleX = iconScale
                            scaleY = iconScale
                        }
                )
            }
        }
    }
}
