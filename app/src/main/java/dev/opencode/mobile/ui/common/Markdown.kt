package dev.opencode.mobile.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.opencode.mobile.ui.theme.OcTheme

private sealed class Block {
    data class Code(val language: String?, val code: String) : Block()
    data class Paragraph(val lines: List<String>) : Block()
    data class Table(val header: List<String>, val rows: List<List<String>>) : Block()
    data class ListRow(val level: Int, val marker: String, val text: String) : Block()
}

private val listRegex = Regex("^(\\s*)([-*+]|\\d+[.)])\\s+(.*)$")
private val tableSeparatorRegex = Regex("^\\s*\\|?[\\s:|-]+\\|?\\s*$")

private fun splitTableRow(line: String): List<String> =
    line.trim().trim('|').split('|').map { it.trim() }

private fun parseBlocks(markdown: String): List<Block> {
    val blocks = mutableListOf<Block>()
    val para = mutableListOf<String>()
    var inFence = false
    var fenceLang: String? = null
    val fence = mutableListOf<String>()
    val lines = markdown.lines()
    var index = 0

    fun flushPara() {
        if (para.isNotEmpty()) {
            blocks.add(Block.Paragraph(para.toList()))
            para.clear()
        }
    }

    while (index < lines.size) {
        val line = lines[index]
        if (inFence) {
            if (line.trimStart().startsWith("```")) {
                blocks.add(Block.Code(fenceLang, fence.joinToString("\n")))
                fence.clear()
                inFence = false
                fenceLang = null
            } else {
                fence.add(line)
            }
            index++
            continue
        }
        val trimmed = line.trimStart()
        if (trimmed.startsWith("```")) {
            flushPara()
            inFence = true
            fenceLang = trimmed.removePrefix("```").trim().ifEmpty { null }
            index++
            continue
        }
        // Table: a pipe row followed by a separator row.
        if (trimmed.startsWith("|") && index + 1 < lines.size && tableSeparatorRegex.matches(lines[index + 1]) && lines[index + 1].contains('-')) {
            flushPara()
            val header = splitTableRow(line)
            index += 2
            val rows = mutableListOf<List<String>>()
            while (index < lines.size && lines[index].trimStart().startsWith("|")) {
                rows.add(splitTableRow(lines[index]))
                index++
            }
            blocks.add(Block.Table(header, rows))
            continue
        }
        val listMatch = listRegex.find(line)
        if (listMatch != null) {
            flushPara()
            val indent = listMatch.groupValues[1].replace("\t", "  ").length
            val rawMarker = listMatch.groupValues[2]
            val marker = if (rawMarker.first().isDigit()) rawMarker else "•"
            blocks.add(Block.ListRow(indent / 2, marker, listMatch.groupValues[3]))
            index++
            continue
        }
        if (line.isBlank()) {
            flushPara()
        } else {
            para.add(line)
        }
        index++
    }
    flushPara()
    if (inFence) blocks.add(Block.Code(fenceLang, fence.joinToString("\n")))
    return blocks
}

@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    bodySize: TextUnit = 15.sp,
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
                is Block.Table -> MarkdownTable(block, bodySize)
                is Block.ListRow -> Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = (12 * block.level).dp, top = 1.dp, bottom = 1.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Text(
                        block.marker,
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = bodySize, color = OcTheme.colors.textSecondary),
                        modifier = Modifier.width(22.dp),
                    )
                    Text(
                        text = renderInline(block.text, codeColor, linkColor),
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = bodySize),
                        modifier = Modifier.weight(1f),
                    )
                }
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

@Composable
private fun MarkdownTable(table: Block.Table, bodySize: TextUnit) {
    val columns = maxOf(table.header.size, table.rows.maxOfOrNull { it.size } ?: 0)
    if (columns == 0) return
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(Modifier.fillMaxWidth().background(OcTheme.colors.surfaceVariant)) {
            for (c in 0 until columns) {
                Text(
                    table.header.getOrElse(c) { "" },
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = bodySize, fontWeight = FontWeight.Bold),
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp, vertical = 6.dp),
                )
            }
        }
        for (row in table.rows) {
            Row(Modifier.fillMaxWidth()) {
                for (c in 0 until columns) {
                    Text(
                        row.getOrElse(c) { "" },
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = bodySize),
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp, vertical = 6.dp),
                    )
                }
            }
            Spacer(Modifier.fillMaxWidth().height(1.dp).background(OcTheme.colors.border))
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
