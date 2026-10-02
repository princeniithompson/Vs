package com.example.ui.theme

import android.content.Context
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.example.service.FloatingBubbleManager

private val DarkColorScheme = darkColorScheme(
    primary = CyanPrimary,
    onPrimary = CyanOnPrimary,
    primaryContainer = CyanPrimaryContainer,
    onPrimaryContainer = CyanOnPrimaryContainer,
    secondary = VioletSecondary,
    onSecondary = VioletOnSecondary,
    secondaryContainer = VioletSecondaryContainer,
    onSecondaryContainer = VioletOnSecondaryContainer,
    tertiary = CoralTertiary,
    onTertiary = CoralOnTertiary,
    background = DarkBackground,
    surface = DarkSurface,
    surfaceVariant = DarkSurfaceVariant,
    onSurface = DarkOnSurface,
    onSurfaceVariant = DarkOnSurfaceVariant,
    outline = DarkOutline,
    error = Color(0xFFEF4444),
    errorContainer = Color(0xFF7F1D1D),
    onError = Color.White,
    onErrorContainer = Color(0xFFFECACA)
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF006874),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF9EEFFF),
    onPrimaryContainer = Color(0xFF001F24),
    secondary = Color(0xFF6B4EA2),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFECDCFF),
    onSecondaryContainer = Color(0xFF24005B),
    tertiary = Color(0xFF9E2A43),
    onTertiary = Color.White,
    background = Color(0xFFF8FAFC),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFE2E8F0),
    onSurface = Color(0xFF0F172A),
    onSurfaceVariant = Color(0xFF475569),
    outline = Color(0xFFCBD5E1),
    error = Color(0xFFDC2626),
    errorContainer = Color(0xFFFEE2E2),
    onError = Color.White,
    onErrorContainer = Color(0xFF7F1D1D)
)

/**
 * Extracts and blends the user's Android 12+ wallpaper dynamic colors into a unified 4-tone palette:
 * - Luminous: Bright, airy surfaces and containers
 * - Deep: Rich deep tones for active controls, buttons, and bold headers
 * - Muted: Soft tones for secondary elements and subtle borders
 * - Accent: Complementary tertiary tones (e.g. pink accent on blue wallpaper)
 */
fun getAppDynamicColorScheme(context: Context, darkTheme: Boolean, toneId: String): ColorScheme {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        try {
            val a1_100 = Color(ContextCompat.getColor(context, android.R.color.system_accent1_100))
            val a1_200 = Color(ContextCompat.getColor(context, android.R.color.system_accent1_200))
            val a1_500 = Color(ContextCompat.getColor(context, android.R.color.system_accent1_500))
            val a1_600 = Color(ContextCompat.getColor(context, android.R.color.system_accent1_600))
            val a1_700 = Color(ContextCompat.getColor(context, android.R.color.system_accent1_700))
            val a1_800 = Color(ContextCompat.getColor(context, android.R.color.system_accent1_800))
            val a1_900 = Color(ContextCompat.getColor(context, android.R.color.system_accent1_900))

            val a2_100 = Color(ContextCompat.getColor(context, android.R.color.system_accent2_100))
            val a2_200 = Color(ContextCompat.getColor(context, android.R.color.system_accent2_200))
            val a2_600 = Color(ContextCompat.getColor(context, android.R.color.system_accent2_600))
            val a2_700 = Color(ContextCompat.getColor(context, android.R.color.system_accent2_700))

            val a3_100 = Color(ContextCompat.getColor(context, android.R.color.system_accent3_100))
            val a3_200 = Color(ContextCompat.getColor(context, android.R.color.system_accent3_200))
            val a3_500 = Color(ContextCompat.getColor(context, android.R.color.system_accent3_500))
            val a3_600 = Color(ContextCompat.getColor(context, android.R.color.system_accent3_600))
            val a3_700 = Color(ContextCompat.getColor(context, android.R.color.system_accent3_700))

            val n1_50 = Color(ContextCompat.getColor(context, android.R.color.system_neutral1_50))
            val n1_100 = Color(ContextCompat.getColor(context, android.R.color.system_neutral1_100))
            val n1_800 = Color(ContextCompat.getColor(context, android.R.color.system_neutral1_800))
            val n1_900 = Color(ContextCompat.getColor(context, android.R.color.system_neutral1_900))

            val n2_100 = Color(ContextCompat.getColor(context, android.R.color.system_neutral2_100))
            val n2_200 = Color(ContextCompat.getColor(context, android.R.color.system_neutral2_200))

            if (!darkTheme) {
                // Light mode with 4-tone dynamic blending
                val (primaryCol, primaryCont) = when (toneId) {
                    "deep" -> Pair(a1_800, a1_200)
                    "muted" -> Pair(a2_700, a2_100)
                    "tertiary" -> Pair(a3_700, a3_100)
                    else -> Pair(a1_700, a1_100) // Luminous
                }
                val (secCol, secCont) = when (toneId) {
                    "muted" -> Pair(a1_600, a1_100)
                    else -> Pair(a2_600, a2_100)
                }
                val (tertCol, tertCont) = Pair(a3_600, a3_100)

                return lightColorScheme(
                    primary = primaryCol,
                    onPrimary = Color.White,
                    primaryContainer = primaryCont,
                    onPrimaryContainer = a1_900,
                    secondary = secCol,
                    onSecondary = Color.White,
                    secondaryContainer = secCont,
                    onSecondaryContainer = Color(0xFF1E1B4B),
                    tertiary = tertCol,
                    onTertiary = Color.White,
                    tertiaryContainer = tertCont,
                    onTertiaryContainer = Color(0xFF4C0519),
                    background = n1_50,
                    surface = Color.White,
                    surfaceVariant = n2_100,
                    onSurface = n1_900,
                    onSurfaceVariant = n1_800,
                    outline = n2_200,
                    outlineVariant = a1_100,
                    error = Color(0xFFDC2626),
                    errorContainer = Color(0xFFFEE2E2),
                    onError = Color.White,
                    onErrorContainer = Color(0xFF7F1D1D)
                )
            } else {
                return dynamicDarkColorScheme(context)
            }
        } catch (_: Throwable) {
            return if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
    }
    return if (darkTheme) DarkColorScheme else LightColorScheme
}

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = false,
    dynamicColor: Boolean = true,
    toneId: String = "luminous",
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            getAppDynamicColorScheme(context, darkTheme, toneId)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
