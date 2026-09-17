package dev.opencode.mobile.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private sealed class Block {
    data class Code(val language: String?, val code: String) : Block()
    data class Paragraph(val lines: List<String>) : Block()
}

private fun parseBlocks(markdown: String): List<Block> {
    val blocks = mutableListOf<Block>()
    val para = mutableListOf<String>()
    var inFence = false
    var fenceLang: String? = null
    val fence = mutableListOf<String>()

    fun flushPara() {
        if (para.isNotEmpty()) {
            blocks.add(Block.Paragraph(para.toList()))
            para.clear()
        }
    }

    for (line in markdown.lines()) {
        if (inFence) {
            if (line.trimStart().startsWith("```")) {
                blocks.add(Block.Code(fenceLang, fence.joinToString("\n")))
                fence.clear()
                inFence = false
                fenceLang = null
            } else {
                fence.add(line)
            }
            continue
        }
        val trimmed = line.trimStart()
        if (trimmed.startsWith("```")) {
            flushPara()
            inFence = true
            fenceLang = trimmed.removePrefix("```").trim().ifEmpty { null }
            continue
        }
        if (line.isBlank()) {
            flushPara()
        } else {
            para.add(line)
        }
    }
    flushPara()
    if (inFence) blocks.add(Block.Code(fenceLang, fence.joinToString("\n")))
    return blocks
}

@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    bodySize: androidx.compose.ui.unit.TextUnit = 15.sp,
) {
    val blocks = remember(markdown) { parseBlocks(markdown) }
    val codeColor = MaterialTheme.colorScheme.primary
    val linkColor = MaterialTheme.colorScheme.primary
    Column(modifier = modifier) {
        for (block in blocks) {
            when (block) {
                is Block.Code -> CodeBlock(
                    language = block.language,
                    code = block.code,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                )
                is Block.Paragraph -> {
                    for (line in block.lines) {
                        val isHeading = line.trimStart().startsWith('#')
                        val content = if (isHeading) line.trim().trimStart('#').trim() else line
                        val base = MaterialTheme.typography.bodyMedium.copy(
                            fontSize = bodySize,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        val style = if (isHeading) {
                            base.copy(
                                fontSize = (bodySize.value + 1f).sp,
                                fontWeight = FontWeight.Bold,
                            )
                        } else {
                            base
                        }
                        Text(
                            text = renderInline(content, codeColor, linkColor),
                            style = style,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
                        )
                    }
                }
            }
        }
    }
}

private val inlineRegex = Regex(
    "\\*\\*(.+?)\\*\\*" +            // bold
        "|(?<!\\*)\\*([^*\\s][^*]*?)\\*(?!\\*)" + // italic
        "|`([^`]+)`" +               // inline code
        "|(https?://[^\\s<>()\\[\\]\"']+)", // link
)

private fun renderInline(line: String, codeColor: Color, linkColor: Color): AnnotatedString = buildAnnotatedString {
    var cursor = 0
    for (match in inlineRegex.findAll(line)) {
        if (match.range.first > cursor) {
            append(line.substring(cursor, match.range.first))
        }
        val bold = match.groupValues[1]
        val italic = match.groupValues[2]
        val code = match.groupValues[3]
        val url = match.groupValues[4]
        when {
            code.isNotEmpty() -> {
                pushStyle(SpanStyle(fontFamily = FontFamily.Monospace, color = codeColor))
                append(code)
                pop()
            }
            bold.isNotEmpty() -> {
                pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                append(bold)
                pop()
            }
            italic.isNotEmpty() -> {
                pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
                append(italic)
                pop()
            }
            url.isNotEmpty() -> {
                val clean = url.trimEnd('.', ',', ';', ':', '!', '?')
                val trailing = url.removePrefix(clean)
                withLink(
                    LinkAnnotation.Url(
                        clean,
                        TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)),
                    ),
                ) { append(clean) }
                if (trailing.isNotEmpty()) append(trailing)
            }
        }
        cursor = match.range.last + 1
    }
    if (cursor < line.length) {
        append(line.substring(cursor))
    }
}