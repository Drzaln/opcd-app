package dev.opencode.mobile.ui.theme

import androidx.compose.ui.graphics.Color

// Backgrounds / surfaces
val Bg = Color(0xFF0E1116)
val Surface = Color(0xFF161B22)
val SurfaceVariant = Color(0xFF21262D)
val Border = Color(0xFF262C36)

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
// "Text muted" in the editor mockups; existing screens use it for labels/metadata.
val TextSecondary = Color(0xFF9AA4B2)
val TextBody = Color(0xFFC9D1D9)
val LineNumber = Color(0xFF6E7681)
val AccentSoft = Color(0xFF10243F)
val ErrorText = Color(0xFFFFB4AE)
val ErrorBg = Color(0xFF3A1D20)
val EditorLine = Color(0xFF1B2B44)
val EditorActiveLine = Color(0xFF141C28)
val Popup = Color(0xFF1C2129)
val PopupBorder = Color(0xFF30363D)

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
val LightTextBody = Color(0xFF32383F)
val LightLineNumber = Color(0xFF8C959F)
val LightAccentSoft = Color(0xFFDDF4FF)
val LightErrorText = Color(0xFF82071E)
val LightErrorBg = Color(0xFFFFEBE9)
val LightEditorLine = Color(0xFFCDE3FF)
val LightEditorActiveLine = Color(0xFFF2F7FF)
val LightPopup = Color(0xFFFFFFFF)
val LightPopupBorder = Color(0xFFD0D7DE)

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
    /** Muted labels, metadata, inactive icons (mockup "text muted"). */
    val textSecondary: Color,
    /** Body copy one step below primary (mockup "text secondary"). */
    val textBody: Color,
    val lineNumber: Color,
    /** Primary buttons. */
    val accent: Color,
    /** Selected chip / tab / tree row / nav pill. */
    val accentSoft: Color,
    /** Links and active icons. */
    val link: Color,
    val warning: Color,
    val errorText: Color,
    val errorBg: Color,
    /** Editor selection. */
    val editorLine: Color,
    val editorActiveLine: Color,
    val popup: Color,
    val popupBorder: Color,
    val syntax: SyntaxColors,
)

@androidx.compose.runtime.Immutable
data class SyntaxColors(
    val keyword: Color,
    val string: Color,
    val number: Color,
    val function: Color,
    val type: Color,
    val tag: Color,
    val comment: Color,
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
    textBody = TextBody,
    lineNumber = LineNumber,
    accent = PrimaryDim,
    accentSoft = AccentSoft,
    link = Primary,
    warning = Orange,
    errorText = ErrorText,
    errorBg = ErrorBg,
    editorLine = EditorLine,
    editorActiveLine = EditorActiveLine,
    popup = Popup,
    popupBorder = PopupBorder,
    syntax = SyntaxColors(
        keyword = CodeKeyword,
        string = CodeString,
        number = CodeNumber,
        function = CodeFunction,
        type = CodeClass,
        tag = CodeTag,
        comment = CodeComment,
    ),
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
    textBody = LightTextBody,
    lineNumber = LightLineNumber,
    accent = LightPrimary,
    accentSoft = LightAccentSoft,
    link = LightPrimary,
    warning = LightOrange,
    errorText = LightErrorText,
    errorBg = LightErrorBg,
    editorLine = LightEditorLine,
    editorActiveLine = LightEditorActiveLine,
    popup = LightPopup,
    popupBorder = LightPopupBorder,
    syntax = SyntaxColors(
        keyword = Color(0xFFCF222E),
        string = Color(0xFF0A3069),
        number = Color(0xFF0550AE),
        function = Color(0xFF8250DF),
        type = Color(0xFF953800),
        tag = Color(0xFF116329),
        comment = Color(0xFF6E7781),
    ),
)

val LocalOcColors = androidx.compose.runtime.staticCompositionLocalOf { DarkOcColors }

object OcTheme {
    val colors: OcColors
        @androidx.compose.runtime.Composable
        @androidx.compose.runtime.ReadOnlyComposable
        get() = LocalOcColors.current
}