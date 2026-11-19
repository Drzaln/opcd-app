package dev.opencode.mobile.ui.common

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import dev.opencode.mobile.ui.theme.CodeAttr
import dev.opencode.mobile.ui.theme.CodeChanged
import dev.opencode.mobile.ui.theme.CodeClass
import dev.opencode.mobile.ui.theme.CodeComment
import dev.opencode.mobile.ui.theme.CodeDeleted
import dev.opencode.mobile.ui.theme.CodeFunction
import dev.opencode.mobile.ui.theme.CodeInserted
import dev.opencode.mobile.ui.theme.CodeKeyword
import dev.opencode.mobile.ui.theme.CodeNumber
import dev.opencode.mobile.ui.theme.CodeOperator
import dev.opencode.mobile.ui.theme.CodePlain
import dev.opencode.mobile.ui.theme.CodeProperty
import dev.opencode.mobile.ui.theme.CodePunctuation
import dev.opencode.mobile.ui.theme.CodeSelector
import dev.opencode.mobile.ui.theme.CodeString
import dev.opencode.mobile.ui.theme.CodeTag
import dev.opencode.mobile.ui.theme.CodeVariable
import io.noties.prism4j.GrammarLocator
import io.noties.prism4j.Prism4j
import io.noties.prism4j.Prism4j.Node
import io.noties.prism4j.Prism4j.Syntax
import io.noties.prism4j.Prism4j.Text
import io.noties.prism4j.annotations.PrismBundle

@PrismBundle(
    include = [
        "markup", "clike", "css", "javascript", "java", "kotlin",
        "python", "go", "c", "cpp", "csharp", "dart", "swift",
        "sql", "json", "yaml", "markdown", "git", "groovy",
    ],
    grammarLocatorClassName = ".PrismLocator",
)
private object PrismLanguages

object CodeHighlighter {

    private val prism4j: Prism4j by lazy { Prism4j(PrismLocator() as GrammarLocator) }

    private val tokenColors: Map<String, Color> = mapOf(
        "comment" to CodeComment,
        "prolog" to CodeComment,
        "doctype" to CodeComment,
        "cdata" to CodeComment,
        "keyword" to CodeKeyword,
        "atrule" to CodeKeyword,
        "rule" to CodeKeyword,
        "important" to CodeKeyword,
        "boolean" to CodeNumber,
        "number" to CodeNumber,
        "constant" to CodeNumber,
        "unit" to CodeNumber,
        "string" to CodeString,
        "char" to CodeString,
        "regex" to CodeString,
        "url" to CodeString,
        "attr-value" to CodeString,
        "function" to CodeFunction,
        "class-name" to CodeClass,
        "builtin" to CodeClass,
        "operator" to CodeOperator,
        "punctuation" to CodePunctuation,
        "property" to CodeProperty,
        "tag" to CodeTag,
        "attr-name" to CodeAttr,
        "variable" to CodeVariable,
        "selector" to CodeSelector,
        "inserted" to CodeInserted,
        "deleted" to CodeDeleted,
        "changed" to CodeChanged,
        "namespace" to CodeOperator,
        "plain" to CodePlain,
    )

    /** The same token roles in GitHub-light tones, for light mode (the dark ones wash out on white). */
    private val lightTokenColors: Map<String, Color> = run {
        val comment = Color(0xFF6E7781); val keyword = Color(0xFFCF222E); val number = Color(0xFF0550AE)
        val string = Color(0xFF0A3069); val function = Color(0xFF8250DF); val type = Color(0xFF953800)
        val tag = Color(0xFF116329); val plain = Color(0xFF1F2328); val punct = Color(0xFF57606A)
        tokenColors.mapValues { (k, _) ->
            when (k) {
                "comment", "prolog", "doctype", "cdata" -> comment
                "keyword", "atrule", "rule", "important" -> keyword
                "boolean", "number", "constant", "unit", "property", "attr-name", "variable" -> number
                "string", "char", "regex", "url", "attr-value" -> string
                "function" -> function
                "class-name", "builtin", "selector" -> type
                "tag", "inserted" -> tag
                "deleted" -> Color(0xFF82071E)
                "changed" -> Color(0xFF953800)
                "operator", "namespace", "punctuation" -> punct
                else -> plain
            }
        }
    }

    fun highlight(code: String, language: String?, light: Boolean = false): AnnotatedString {
        if (code.isEmpty()) return AnnotatedString(code)
        return try {
            val grammar = prism4j.grammar(language ?: "markup") ?: return AnnotatedString(code)
            val nodes = prism4j.tokenize(code, grammar)
            val builder = AnnotatedString.Builder()
            val colors = if (light) lightTokenColors else tokenColors
            for (node in nodes) traverse(node, builder, colors)
            builder.toAnnotatedString()
        } catch (t: Throwable) {
            AnnotatedString(code)
        }
    }

    private fun traverse(node: Node, builder: AnnotatedString.Builder, tokenColors: Map<String, Color>) {
        if (node is Text) {
            builder.append(node.literal())
            return
        }
        if (node is Syntax) {
            val start = builder.length
            for (child in node.children()) traverse(child, builder, tokenColors)
            val color = tokenColors[node.type()] ?: tokenColors[node.alias()]
            if (color != null && builder.length > start) {
                builder.addStyle(SpanStyle(color = color), start, builder.length)
            }
        }
    }
}

fun languageForPath(path: String): String? {
    val name = path.substringAfterLast('.').lowercase()
    return when (name) {
        "kt", "kts" -> "kotlin"
        "java" -> "java"
        "js", "mjs", "cjs", "jsx", "ts", "tsx" -> "javascript"
        "py" -> "python"
        "go" -> "go"
        "c" -> "c"
        "h" -> "c"
        "cpp", "cc", "cxx", "hpp", "hh" -> "cpp"
        "cs" -> "csharp"
        "dart" -> "dart"
        "swift" -> "swift"
        "sql" -> "sql"
        "json" -> "json"
        "yml", "yaml" -> "yaml"
        "html", "htm", "xml", "svg", "vue" -> "markup"
        "css" -> "css"
        "md", "markdown" -> "markdown"
        "gradle", "gradle.kts" -> "kotlin"
        "groovy" -> "groovy"
        else -> null
    }
}