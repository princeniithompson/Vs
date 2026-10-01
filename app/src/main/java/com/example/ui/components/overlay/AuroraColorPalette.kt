package com.example.ui.components.overlay

import android.content.Context
import android.os.Build
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.example.service.FloatingBubbleManager

/**
 * Dynamic Aurora Color Palette extracted directly from the user's Android 12+ wallpaper Monet palette.
 * Automatically adapts on device (e.g. vibrant green on this device, pink on pink wallpapers, etc.).
 */
data class AuroraColorPalette(
    val primary: Color,
    val primaryLight: Color,
    val primaryVibrant: Color,
    val secondary: Color,
    val deep: Color
)

/**
 * Pure function to extract the dynamic color palette based on chosen tone.
 */
fun getDynamicTonePalette(context: Context, toneId: String): AuroraColorPalette {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        try {
            when (toneId) {
                "deep" -> {
                    val c500 = ContextCompat.getColor(context, android.R.color.system_accent1_500)
                    val c600 = ContextCompat.getColor(context, android.R.color.system_accent1_600)
                    val c700 = ContextCompat.getColor(context, android.R.color.system_accent1_700)
                    val c800 = ContextCompat.getColor(context, android.R.color.system_accent1_800)
                    val c2_600 = ContextCompat.getColor(context, android.R.color.system_accent2_600)
                    return AuroraColorPalette(
                        primary = Color(c700),
                        primaryLight = Color(c500),
                        primaryVibrant = Color(c600),
                        secondary = Color(c2_600),
                        deep = Color(c800)
                    )
                }
                "muted" -> {
                    val a2_200 = ContextCompat.getColor(context, android.R.color.system_accent2_200)
                    val a2_300 = ContextCompat.getColor(context, android.R.color.system_accent2_300)
                    val a2_400 = ContextCompat.getColor(context, android.R.color.system_accent2_400)
                    val a2_600 = ContextCompat.getColor(context, android.R.color.system_accent2_600)
                    val a1_300 = ContextCompat.getColor(context, android.R.color.system_accent1_300)
                    return AuroraColorPalette(
                        primary = Color(a2_400),
                        primaryLight = Color(a2_200),
                        primaryVibrant = Color(a2_300),
                        secondary = Color(a1_300),
                        deep = Color(a2_600)
                    )
                }
                "tertiary" -> {
                    val a3_200 = ContextCompat.getColor(context, android.R.color.system_accent3_200)
                    val a3_300 = ContextCompat.getColor(context, android.R.color.system_accent3_300)
                    val a3_400 = ContextCompat.getColor(context, android.R.color.system_accent3_400)
                    val a3_600 = ContextCompat.getColor(context, android.R.color.system_accent3_600)
                    val a1_300 = ContextCompat.getColor(context, android.R.color.system_accent1_300)
                    return AuroraColorPalette(
                        primary = Color(a3_400),
                        primaryLight = Color(a3_200),
                        primaryVibrant = Color(a3_300),
                        secondary = Color(a1_300),
                        deep = Color(a3_600)
                    )
                }
                else -> {
                    val c200 = ContextCompat.getColor(context, android.R.color.system_accent1_200)
                    val c300 = ContextCompat.getColor(context, android.R.color.system_accent1_300)
                    val c400 = ContextCompat.getColor(context, android.R.color.system_accent1_400)
                    val c600 = ContextCompat.getColor(context, android.R.color.system_accent1_600)
                    val c2_300 = ContextCompat.getColor(context, android.R.color.system_accent2_300)
                    return AuroraColorPalette(
                        primary = Color(c400),
                        primaryLight = Color(c200),
                        primaryVibrant = Color(c300),
                        secondary = Color(c2_300),
                        deep = Color(c600)
                    )
                }
            }
        } catch (e: Throwable) {
            val darkDyn = dynamicDarkColorScheme(context)
            return when (toneId) {
                "deep" -> AuroraColorPalette(
                    primary = darkDyn.primaryContainer,
                    primaryLight = darkDyn.primary,
                    primaryVibrant = darkDyn.primaryContainer,
                    secondary = darkDyn.secondaryContainer,
                    deep = darkDyn.surfaceTint
                )
                "muted" -> AuroraColorPalette(
                    primary = darkDyn.secondary,
                    primaryLight = darkDyn.secondaryContainer,
                    primaryVibrant = darkDyn.secondary,
                    secondary = darkDyn.primary,
                    deep = darkDyn.surfaceTint
                )
                "tertiary" -> AuroraColorPalette(
                    primary = darkDyn.tertiary,
                    primaryLight = darkDyn.tertiaryContainer,
                    primaryVibrant = darkDyn.tertiary,
                    secondary = darkDyn.secondary,
                    deep = darkDyn.surfaceTint
                )
                else -> AuroraColorPalette(
                    primary = darkDyn.primary,
                    primaryLight = darkDyn.primaryContainer,
                    primaryVibrant = darkDyn.primary,
                    secondary = darkDyn.secondary,
                    deep = darkDyn.surfaceTint
                )
            }
        }
    } else {
        return when (toneId) {
            "deep" -> AuroraColorPalette(
                primary = Color(0xFF065F46),
                primaryLight = Color(0xFF047857),
                primaryVibrant = Color(0xFF059669),
                secondary = Color(0xFF0891B2),
                deep = Color(0xFF022C22)
            )
            "muted" -> AuroraColorPalette(
                primary = Color(0xFF0D9488),
                primaryLight = Color(0xFF5EEAD4),
                primaryVibrant = Color(0xFF2DD4BF),
                secondary = Color(0xFF38BDF8),
                deep = Color(0xFF134E4A)
            )
            "tertiary" -> AuroraColorPalette(
                primary = Color(0xFF8B5CF6),
                primaryLight = Color(0xFFC4B5FD),
                primaryVibrant = Color(0xFFA78BFA),
                secondary = Color(0xFFEC4899),
                deep = Color(0xFF4C1D95)
            )
            else -> AuroraColorPalette(
                primary = Color(0xFF10B981),
                primaryLight = Color(0xFF6EE7B7),
                primaryVibrant = Color(0xFF34D399),
                secondary = Color(0xFF06B6D4),
                deep = Color(0xFF064E3B)
            )
        }
    }
}

@Composable
fun rememberDynamicAuroraPalette(toneIdOverride: String? = null): AuroraColorPalette {
    val context = LocalContext.current
    val globalTone by FloatingBubbleManager.selectedColorTone.collectAsState()
    val activeTone = toneIdOverride ?: globalTone
    return remember(context, activeTone) {
        getDynamicTonePalette(context, activeTone)
    }
}

fun isColorDark(color: Color): Boolean {
    val darkness = 1 - (0.299 * color.red + 0.587 * color.green + 0.114 * color.blue)
    return darkness >= 0.5
}
