package dev.opencode.mobile.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.opencode.mobile.ui.theme.SurfaceVariant
import dev.opencode.mobile.ui.theme.TextSecondary

@Composable
fun CodeBlock(
    language: String?,
    code: String,
    modifier: Modifier = Modifier,
) {
    val highlighted = remember(language, code) { CodeHighlighter.highlight(code, language) }
    val scroll = rememberScrollState()
    Box(
        modifier = modifier
            .background(SurfaceVariant)
            .horizontalScroll(scroll)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(
            text = highlighted,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            lineHeight = 19.sp,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
fun MutedLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = TextSecondary,
        modifier = modifier,
    )
}