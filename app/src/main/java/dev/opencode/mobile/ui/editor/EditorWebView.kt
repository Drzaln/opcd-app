package dev.opencode.mobile.ui.editor

import android.annotation.SuppressLint
import android.content.Context
import android.util.Base64
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.opencode.mobile.R
import dev.opencode.mobile.ui.theme.OcColors
import dev.opencode.mobile.ui.theme.OcTheme
import kotlinx.coroutines.delay
import java.util.Locale

private const val BASE_URL = "https://editor.local/"
private const val TAG = "CodeEditor"

/**
 * CodeMirror 6 in a WebView (bundle: `tools/editor-bundle` → `assets/editor/`). Same recipe as
 * [dev.opencode.mobile.ui.terminal.XtermTerminalView]: the engine is inlined into `editor.html`
 * (never fetched), only the font is served by `shouldInterceptRequest`, the page loads on the first
 * non-zero layout, and `console.*` goes to logcat tag `CodeEditor`.
 *
 * All state lives in [EditorViewModel]; this view only replays [EditorCommand]s and forwards
 * callbacks, so it can be torn down and rebuilt at any time.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun EditorWebView(vm: EditorViewModel, modifier: Modifier = Modifier, fontSizeSp: Float = 13f) {
    val colors = OcTheme.colors
    val density = LocalDensity.current
    val holder = remember { EditorWebHolder() }
    var ready by remember { mutableIntStateOf(0) }
    var jsError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(ready) {
        if (ready != 0) return@LaunchedEffect
        delay(8000)
        if (ready == 0) jsError = "The editor never reported ready"
    }

    Box(modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                createEditorWebView(
                    context = ctx,
                    vm = vm,
                    holder = holder,
                    backgroundArgb = colors.bg.toArgb(),
                    onReady = { ready++ },
                    onFail = { message ->
                        android.util.Log.e(TAG, message)
                        jsError = message
                    },
                )
            },
            onRelease = {
                holder.web = null
                it.destroy()
            },
        )
        jsError?.let { message ->
            Surface(color = colors.errorBg, modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter)) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Text("Editor problem", style = MaterialTheme.typography.labelLarge, color = colors.errorText)
                    Text(message, style = MaterialTheme.typography.bodySmall, color = colors.errorText)
                    TextButton(onClick = { jsError = null }) { Text("Dismiss") }
                }
            }
        }
    }

    // Theme + font follow the app (light/dark switch, font scale) without reloading the page.
    LaunchedEffect(ready, colors) {
        if (ready == 0) return@LaunchedEffect
        holder.js("window.__ocSetTheme && window.__ocSetTheme(${quoteJs(themeJson(colors))})")
    }
    LaunchedEffect(ready, fontSizeSp, density.fontScale) {
        if (ready == 0) return@LaunchedEffect
        // CSS px in a WebView are dp, so scale sp by the user's font scale ourselves.
        holder.js("window.__ocSetFontSize && window.__ocSetFontSize(${fontSizeSp * density.fontScale})")
    }

    // Replay the ViewModel's tabs into a fresh page, then follow its command stream.
    LaunchedEffect(ready) {
        if (ready == 0) return@LaunchedEffect
        vm.onEditorReady()
        vm.commands.collect { command -> holder.js(command.toJs()) }
    }

    DisposableEffect(Unit) {
        onDispose {
            holder.web = null
            vm.onEditorGone()
        }
    }
}

private fun EditorCommand.toJs(): String = when (this) {
    is EditorCommand.Load -> "window.__ocLoad(${quoteJs(path)},'${b64(text)}',${quoteJs(language.id)},$line" +
        (savedText?.let { ",'${b64(it)}'" } ?: "") + ")"
    is EditorCommand.Show -> "window.__ocShow(${quoteJs(path)},$line)"
    is EditorCommand.Close -> "window.__ocClose(${quoteJs(path)})"
    is EditorCommand.GetText -> "window.__ocGetText($id,${quoteJs(path)})"
    is EditorCommand.Saved -> "window.__ocSaved($id,${quoteJs(path)})"
    is EditorCommand.Insert -> "window.__ocInsert('${b64(text)}')"
    is EditorCommand.Run -> "window.__ocCommand(${quoteJs(name)})"
    is EditorCommand.Wrap -> "window.__ocSetWrap($on)"
    is EditorCommand.LspStart -> "window.__ocLspStart(${quoteJs(rootUri)})"
    is EditorCommand.LspRecv -> "window.__ocLspRecv('${b64(message)}')"
    EditorCommand.LspStop -> "window.__ocLspStop()"
    is EditorCommand.RenameTo -> "window.__ocRenameTo(${quoteJs(name)})"
    is EditorCommand.Replace -> "window.__ocReplace($id,${quoteJs(path)},'${b64(text)}')"
}

/** Large text crosses the bridge base64-encoded (as the terminal does), so quotes/newlines/U+2028 are safe. */
private fun b64(text: String): String = Base64.encodeToString(text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

/** A JS string literal for short values (paths, names): JSON-style escaping. */
internal fun quoteJs(value: String): String = buildString {
    append('"')
    for (c in value) {
        when {
            c == '"' -> append("\\\"")
            c == '\\' -> append("\\\\")
            c < ' ' || c == ' ' || c == ' ' || c == '<' -> append(String.format(Locale.US, "\\u%04x", c.code))
            else -> append(c)
        }
    }
    append('"')
}

private class EditorWebHolder {
    var web: WebView? = null

    fun js(script: String) {
        web?.evaluateJavascript(script, null)
    }
}

private fun createEditorWebView(
    context: Context,
    vm: EditorViewModel,
    holder: EditorWebHolder,
    backgroundArgb: Int,
    onReady: () -> Unit,
    onFail: (String) -> Unit,
): WebView = WebView(context).apply {
    settings.javaScriptEnabled = true
    settings.allowFileAccess = false
    settings.allowContentAccess = false
    settings.setSupportZoom(false)
    settings.builtInZoomControls = false
    settings.displayZoomControls = false
    overScrollMode = View.OVER_SCROLL_NEVER
    importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
    isFocusable = true
    isFocusableInTouchMode = true
    setBackgroundColor(backgroundArgb)
    addJavascriptInterface(
        EditorBridge(
            vm = vm,
            ready = { post(onReady) },
            fail = { message -> post { onFail(message) } },
        ),
        "AndroidEditor",
    )
    webChromeClient = object : android.webkit.WebChromeClient() {
        override fun onConsoleMessage(msg: android.webkit.ConsoleMessage): Boolean {
            val line = "console: ${msg.message()} (${msg.sourceId()}:${msg.lineNumber()})"
            android.util.Log.i(TAG, line)
            if (msg.messageLevel() == android.webkit.ConsoleMessage.MessageLevel.ERROR) post { onFail(line) }
            return true
        }
    }
    webViewClient = EditorAssetClient(context, onFail)
    holder.web = this
    // Load only once the view has a real size (see the xterm gotcha in PROGRESS.md: a page loaded
    // at 0x0 keeps a 0-height layout viewport forever).
    var loaded = false
    fun loadOnce() {
        if (loaded) return
        loaded = true
        loadDataWithBaseURL(BASE_URL, readEditorSeed(context), "text/html", "utf-8", null)
    }
    addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
        override fun onLayoutChange(v: View, l: Int, t: Int, r: Int, b: Int, ol: Int, ot: Int, or: Int, ob: Int) {
            if (r - l > 0 && b - t > 0) {
                v.removeOnLayoutChangeListener(this)
                loadOnce()
            }
        }
    })
    if (width > 0 && height > 0) loadOnce()
}

/** `editor.html` with `editor.js` inlined (the engine is never a subresource). */
private fun readEditorSeed(context: Context): String {
    fun asset(path: String) = runCatching { context.assets.open(path).bufferedReader().use { it.readText() } }.getOrNull()
    val page = asset("editor/editor.html")
        ?: return "<html><body style='font:12px monospace;color:#c00'>editor.html missing from assets</body></html>"
    val script = asset("editor/editor.js").orEmpty().replace("</script", "<\\/script")
    return page.replace("<script src=\"editor.js\"></script>", "<script>$script</script>")
}

private fun themeJson(c: OcColors): String {
    fun hex(color: Color) = String.format(Locale.US, "#%06X", 0xFFFFFF and color.toArgb())
    val tokens = linkedMapOf(
        "bg" to c.bg, "surface" to c.surface, "border" to c.border, "text" to c.textBody, "muted" to c.textSecondary,
        "lineno" to c.lineNumber, "line" to c.editorActiveLine, "selection" to c.editorLine, "link" to c.link,
        "accentSoft" to c.accentSoft, "popup" to c.popup, "popupBorder" to c.popupBorder, "error" to c.red, "warning" to c.warning,
        "kw" to c.syntax.keyword, "str" to c.syntax.string, "num" to c.syntax.number, "prop" to c.syntax.number,
        "fn" to c.syntax.function, "type" to c.syntax.type, "tag" to c.syntax.tag, "comment" to c.syntax.comment,
    )
    return tokens.entries.joinToString(",", "{", "}") { (k, v) -> "\"$k\":\"${hex(v)}\"" }
}

/** Serves the bundled Nerd Font from `res/font`; everything else is inlined. */
private class EditorAssetClient(
    private val context: Context,
    private val onFail: (String) -> Unit,
) : WebViewClient() {
    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        val url = request.url
        if (url.host != "editor.local") return null
        return when (url.path?.trimStart('/')) {
            "favicon.ico" -> WebResourceResponse("image/x-icon", null, 204, "No Content", emptyMap(), java.io.ByteArrayInputStream(ByteArray(0)))
            "font" -> runCatching { context.resources.openRawResource(R.font.jetbrains_mono_nerd_regular) }.getOrNull()
                ?.let { WebResourceResponse("font/ttf", null, 200, "OK", mapOf("Access-Control-Allow-Origin" to "*"), it) }
            else -> {
                android.util.Log.w(TAG, "unexpected subresource request: $url")
                null
            }
        }
    }

    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: android.webkit.WebResourceError) {
        if (request.isForMainFrame) onFail("load error: ${error.description}")
    }
}

private class EditorBridge(
    private val vm: EditorViewModel,
    private val ready: () -> Unit,
    private val fail: (String) -> Unit,
) {
    @JavascriptInterface fun onReady() = ready()

    @JavascriptInterface fun onChange(path: String, dirty: Boolean, @Suppress("UNUSED_PARAMETER") hash: String) = vm.onChange(path, dirty)

    @JavascriptInterface fun onSaveRequested(path: String) = vm.save(path)

    @JavascriptInterface fun onCursor(line: Int, col: Int) = vm.onCursor(line, col)

    @JavascriptInterface fun onCommand(name: String) {
        when (name) {
            "palette" -> vm.showPalette(true)
            "closeTab" -> vm.closeActive()
        }
    }

    @JavascriptInterface fun onText(id: Int, path: String, text: String) = vm.onText(id, path, text)

    @JavascriptInterface fun onSnapshot(path: String, text: String) = vm.onSnapshot(path, text)

    @JavascriptInterface fun onError(message: String) = fail(message)

    @JavascriptInterface fun onFocus(focused: Boolean) = vm.onFocus(focused)

    @JavascriptInterface fun onLsp(message: String) = vm.lspSend(message)

    @JavascriptInterface fun onOpenFile(path: String) = vm.onOpenFile(path)

    @JavascriptInterface fun onRenameRequest(word: String) = vm.onRenameRequest(word)

    @JavascriptInterface fun onInfo(message: String) = vm.onInfo(message)
}
