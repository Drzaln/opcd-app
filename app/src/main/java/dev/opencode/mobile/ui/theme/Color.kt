package dev.opencode.mobile.ui.theme

import androidx.compose.ui.graphics.Color

// Backgrounds / surfaces
val Bg = Color(0xFF0D1117)
val Surface = Color(0xFF161B22)
val SurfaceVariant = Color(0xFF21262D)
val Border = Color(0xFF30363D)

// Accent
val Primary = Color(0xFF58A6FF)
val PrimaryDim = Color(0xFF1F6FEB)
val Green = Color(0xFF3FB950)
val GreenBg = Color(0xFF14301C)
val Red = Color(0xFFF85149)
val RedBg = Color(0xFF3A1B1D)
val Orange = Color(0xFFD29922)
val Purple = Color(0xFFBC8CFF)
val TextPrimary = Color(0xFFE6EDF3)
val TextSecondary = Color(0xFF8B949E)

// Code token colors
val CodeKeyword = Color(0xFFFF7B72)
val CodeString = Color(0xFFA5D6FF)
val CodeComment = Color(0xFF8B949E)
val CodeFunction = Color(0xFFD2A8FF)
val CodeNumber = Color(0xFF79C0FF)
val CodeClass = Color(0xFFFFA657)
val CodeOperator = Color(0xFFFFA657)
val CodePunctuation = Color(0xFF8B949E)
val CodeProperty = Color(0xFF79C0FF)
val CodeTag = Color(0xFF7EE787)
val CodeAttr = Color(0xFF79C0FF)
val CodeVariable = Color(0xFFFFA657)
val CodeSelector = Color(0xFFD2A8FF)
val CodeInserted = Color(0xFF3FB950)
val CodeDeleted = Color(0xFFF85149)
val CodeChanged = Color(0xFFFFA657)
val CodePlain = TextPrimary

// Light palette (GitHub Light-ish).
val LightBg = Color(0xFFFFFFFF)
val LightSurface = Color(0xFFF6F8FA)
val LightSurfaceVariant = Color(0xFFEFF2F5)
val LightBorder = Color(0xFFD0D7DE)
val LightPrimary = Color(0xFF0969DA)
val LightPrimaryDim = Color(0xFFDDF4FF)
val LightGreen = Color(0xFF1A7F37)
val LightGreenBg = Color(0xFFDAFBE1)
val LightRed = Color(0xFFCF222E)
val LightRedBg = Color(0xFFFFEBE9)
val LightOrange = Color(0xFF9A6700)
val LightPurple = Color(0xFF8250DF)
val LightTextPrimary = Color(0xFF1F2328)
val LightTextSecondary = Color(0xFF57606A)

/**
 * Theme-aware palette. Use `OcTheme.colors.*` instead of the raw `Color.kt` constants in UI code
 * so a screen works in both dark and light mode.
 */
@androidx.compose.runtime.Immutable
data class OcColors(
    val bg: Color,
    val surface: Color,
    val surfaceVariant: Color,
    val border: Color,
    val primary: Color,
    val primaryDim: Color,
    val green: Color,
    val greenBg: Color,
    val red: Color,
    val redBg: Color,
    val orange: Color,
    val purple: Color,
    val textPrimary: Color,
    val textSecondary: Color,
)

val DarkOcColors = OcColors(
    bg = Bg,
    surface = Surface,
    surfaceVariant = SurfaceVariant,
    border = Border,
    primary = Primary,
    primaryDim = PrimaryDim,
    green = Green,
    greenBg = GreenBg,
    red = Red,
    redBg = RedBg,
    orange = Orange,
    purple = Purple,
    textPrimary = TextPrimary,
    textSecondary = TextSecondary,
)

val LightOcColors = OcColors(
    bg = LightBg,
    surface = LightSurface,
    surfaceVariant = LightSurfaceVariant,
    border = LightBorder,
    primary = LightPrimary,
    primaryDim = LightPrimaryDim,
    green = LightGreen,
    greenBg = LightGreenBg,
    red = LightRed,
    redBg = LightRedBg,
    orange = LightOrange,
    purple = LightPurple,
    textPrimary = LightTextPrimary,
    textSecondary = LightTextSecondary,
)

val LocalOcColors = androidx.compose.runtime.staticCompositionLocalOf { DarkOcColors }

object OcTheme {
    val colors: OcColors
        @androidx.compose.runtime.Composable
        @androidx.compose.runtime.ReadOnlyComposable
        get() = LocalOcColors.current
}