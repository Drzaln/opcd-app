package dev.opencode.mobile.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

enum class ThemeMode {
    SYSTEM, DARK, LIGHT
}

fun themeModeFrom(value: String?): ThemeMode = when (value?.lowercase()) {
    "dark" -> ThemeMode.DARK
    "light" -> ThemeMode.LIGHT
    else -> ThemeMode.SYSTEM
}

private val DarkColors = darkColorScheme(
    primary = DarkOcColors.primary,
    onPrimary = DarkOcColors.bg,
    primaryContainer = DarkOcColors.primaryDim,
    onPrimaryContainer = DarkOcColors.textPrimary,
    secondary = DarkOcColors.textSecondary,
    onSecondary = DarkOcColors.bg,
    tertiary = DarkOcColors.purple,
    onTertiary = DarkOcColors.bg,
    background = DarkOcColors.bg,
    onBackground = DarkOcColors.textPrimary,
    surface = DarkOcColors.surface,
    onSurface = DarkOcColors.textPrimary,
    surfaceVariant = DarkOcColors.surfaceVariant,
    onSurfaceVariant = DarkOcColors.textSecondary,
    error = DarkOcColors.red,
    onError = DarkOcColors.bg,
    outline = DarkOcColors.border,
)

private val LightColors = lightColorScheme(
    primary = LightOcColors.primary,
    onPrimary = LightOcColors.bg,
    primaryContainer = LightOcColors.primaryDim,
    onPrimaryContainer = LightOcColors.textPrimary,
    secondary = LightOcColors.textSecondary,
    onSecondary = LightOcColors.bg,
    tertiary = LightOcColors.purple,
    onTertiary = LightOcColors.bg,
    background = LightOcColors.bg,
    onBackground = LightOcColors.textPrimary,
    surface = LightOcColors.surface,
    onSurface = LightOcColors.textPrimary,
    surfaceVariant = LightOcColors.surfaceVariant,
    onSurfaceVariant = LightOcColors.textSecondary,
    error = LightOcColors.red,
    onError = LightOcColors.bg,
    outline = LightOcColors.border,
)

// Slightly tighter, consistent type scale (024).
private val Base = Typography()
val AppTypography = Typography(
    titleLarge = Base.titleLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
    titleMedium = Base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    titleSmall = Base.titleSmall.copy(fontWeight = FontWeight.SemiBold),
    labelLarge = Base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    labelMedium = Base.labelMedium.copy(letterSpacing = 0.2.sp),
    bodyMedium = Base.bodyMedium.copy(lineHeight = 21.sp),
)

@Composable
fun OpenCodeTheme(
    mode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit,
) {
    val dark = when (mode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    val ocColors = if (dark) DarkOcColors else LightOcColors
    CompositionLocalProvider(LocalOcColors provides ocColors) {
        MaterialTheme(
            colorScheme = if (dark) DarkColors else LightColors,
            typography = AppTypography,
            content = content,
        )
    }
}
