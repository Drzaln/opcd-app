package dev.opencode.mobile.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Primary,
    onPrimary = Bg,
    primaryContainer = PrimaryDim,
    onPrimaryContainer = TextPrimary,
    secondary = TextSecondary,
    onSecondary = Bg,
    tertiary = Purple,
    onTertiary = Bg,
    background = Bg,
    onBackground = TextPrimary,
    surface = Surface,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceVariant,
    onSurfaceVariant = TextSecondary,
    error = Red,
    onError = Bg,
    outline = Border,
)

val AppTypography = Typography()

@Composable
fun OpenCodeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColors,
        typography = AppTypography,
        content = content,
    )
}