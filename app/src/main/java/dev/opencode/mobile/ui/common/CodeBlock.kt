package dev.opencode.mobile.ui.common

import dev.opencode.mobile.ui.theme.OcTheme

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun CodeBlock(
    language: String?,
    code: String,
    modifier: Modifier = Modifier,
) {
    val highlighted = remember(language, code) { CodeHighlighter.highlight(code, language) }
    val scroll = rememberScrollState()
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(OcTheme.colors.surfaceVariant, RoundedCornerShape(8.dp)),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(OcTheme.colors.border.copy(alpha = 0.4f))
                .padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                language?.ifBlank { "code" } ?: "code",
                color = OcTheme.colors.textSecondary,
                style = MaterialTheme.typography.labelSmall,
            )
            Row(
                Modifier.clickable {
                    clipboard.setText(AnnotatedString(code))
                    copied = true
                    android.widget.Toast.makeText(context, "Code copied", android.widget.Toast.LENGTH_SHORT).show()
                }.padding(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.ContentCopy,
                    contentDescription = "Copy code",
                    tint = if (copied) MaterialTheme.colorScheme.primary else OcTheme.colors.textSecondary,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.size(4.dp))
                Text("Copy", color = OcTheme.colors.textSecondary, style = MaterialTheme.typography.labelSmall)
            }
        }
        Box(
            Modifier
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
}

@Composable
fun MutedLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = OcTheme.colors.textSecondary,
        modifier = modifier,
    )
}
