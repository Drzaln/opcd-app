package dev.opencode.mobile.ui.terminal

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Base64
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.opencode.mobile.R
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.InputStream
import java.util.Locale

private const val BASE_URL = "https://terminal.local/"
private const val FONT_RESOURCE = R.font.jetbrains_mono_nerd_regular

/**
 * xterm.js renderer: a WebView running a vendored xterm.js, fed the same PTY stream as the native
 * canvas (see [TerminalViewModel.rawOutput]). Kotlin keeps owning the WebSocket so auth, the 1.x/2.x
 * URL split and reconnection stay in one place; this file only bridges bytes ↔ terminal.
 *
 * Only mounted while the "xterm.js" renderer is selected — the Compose canvas stays as the fallback.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun XtermTerminalView(vm: TerminalViewModel) {
    val ui by vm.ui.collectAsState()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val defaultBg = MaterialTheme.colorScheme.surface
    val defaultFg = MaterialTheme.colorScheme.onSurface

    val holder = remember { WebViewHolder() }
    var ready by remember { mutableIntStateOf(0) }
    var jsError by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize().background(defaultBg)) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    createTerminalWebView(
                        context = ctx,
                        vm = vm,
                        holder = holder,
                        backgroundArgb = defaultBg.toArgb(),
                        onReady = { ready++ },
                        onFail = { message ->
                            android.util.Log.e("XtermTerminal", message)
                            jsError = message
                        },
                    )
                },
                onRelease = { holder.web = null },
            )
        }

        KeyRow(
            ctrl = false,
            onCtrlToggle = {},
            showCtrl = false,
            onKey = { key -> vm.send(key) },
        )

        if (!ui.connected) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    ui.status,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { ui.active?.let { vm.attach(it) } }) { Text("Reconnect") }
            }
        }

        Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 6.dp) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    when {
                        jsError != null -> "xterm.js error: $jsError"
                        ui.connected -> "tap the terminal to type"
                        else -> ui.status
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (jsError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { holder.js("window.__ocCopy && window.__ocCopy(true)") }) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = "Copy")
                    Spacer(Modifier.width(4.dp))
                    Text("Copy")
                }
                TextButton(onClick = {
                    val text = clipboard.getText()?.text
                    if (!text.isNullOrEmpty()) {
                        // Route through xterm (base64 so newlines/quotes survive) for bracketed paste.
                        val b64 = Base64.encodeToString(text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                        holder.js("window.__ocPaste64 && window.__ocPaste64('$b64')")
                    }
                }) { Text("Paste") }
                TextButton(onClick = {
                    holder.web?.requestFocus()
                    holder.js("window.__ocFocus && window.__ocFocus()")
                }) {
                    Icon(Icons.Filled.Keyboard, contentDescription = "Show keyboard")
                    Spacer(Modifier.width(4.dp))
                    Text("Keyboard")
                }
                TextButton(onClick = { vm.detach() }) { Text("Detach") }
            }
        }
    }

    // Paint the palette into xterm as soon as it is up, and whenever the app theme changes.
    LaunchedEffect(ready, defaultBg, defaultFg) {
        if (ready == 0) return@LaunchedEffect
        holder.js("window.__ocTheme && window.__ocTheme('${themeJson(defaultBg, defaultFg)}')")
    }

    // Start draining once xterm.js reports it is laid out. The VM buffers the PTY stream until then,
    // so nothing is lost between attaching and the WebView finishing its load.
    LaunchedEffect(ready, holder) {
        val web = holder.web ?: return@LaunchedEffect
        if (ready == 0) return@LaunchedEffect
        // Overflowed while loading? Ask for a clean full replay rather than a torn escape sequence.
        if (vm.consumeRawDropped()) vm.reattach()
        val pending = StringBuilder()
        var lastGeneration = vm.attachGeneration.value
        fun flush() {
            if (pending.isEmpty()) return
            // A re-attach (reconnect / renderer switch / buffer overflow) replays the whole PTY, so
            // clear the terminal in the same call that delivers the first chunk of the new stream.
            val generation = vm.attachGeneration.value
            val reset = if (generation != lastGeneration) {
                lastGeneration = generation
                "window.__ocReset&&window.__ocReset();"
            } else {
                ""
            }
            val b64 = Base64.encodeToString(pending.toString().toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            pending.setLength(0)
            runCatching { web.evaluateJavascript("$reset window.__ocWrite && window.__ocWrite('$b64')", null) }
        }
        coroutineScope {
            // One bridge call per frame: a build log arrives as thousands of tiny chunks.
            val ticker = launch {
                while (isActive) {
                    delay(16)
                    flush()
                }
            }
            try {
                for (chunk in vm.rawOutput) {
                    pending.append(chunk)
                    if (pending.length >= 128 * 1024) flush()
                }
            } finally {
                ticker.cancel()
                flush()
            }
        }
    }
}

private class WebViewHolder {
    var web: WebView? = null

    fun js(script: String) {
        web?.evaluateJavascript(script, null)
    }
}

private fun createTerminalWebView(
    context: Context,
    vm: TerminalViewModel,
    holder: WebViewHolder,
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
    isVerticalScrollBarEnabled = false
    importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
    setBackgroundColor(backgroundArgb)
    addJavascriptInterface(
        TerminalBridge(
            vm = vm,
            copyTo = { text ->
                // Called on a WebView JS thread → clipboard + Toast must run on the main thread.
                post {
                    if (text.isNotBlank()) {
                        val cm = context.getSystemService(ClipboardManager::class.java)
                        cm?.setPrimaryClip(ClipData.newPlainText("terminal", text))
                        Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                    }
                }
            },
            failTo = onFail,
            ready = { post(onReady) },
        ),
        "AndroidTerminal",
    )
    webViewClient = TerminalAssetClient(context)
    loadDataWithBaseURL(BASE_URL, readSeed(context), "text/html", "utf-8", null)
    holder.web = this
}

private fun readSeed(context: Context): String =
    runCatching { context.assets.open("terminal/terminal.html").bufferedReader().use { it.readText() } }
        .getOrDefault("<html><body>terminal.html missing from assets</body></html>")

private fun themeJson(bg: Color, fg: Color): String {
    val ansi = Ansi16.joinToString(",\"", "\"", "\"") { hex(it) }
    return "{\"bg\":\"${hex(bg)}\",\"fg\":\"${hex(fg)}\",\"cursor\":\"${hex(fg)}\"," +
        "\"selection\":\"#40407080\",\"names\":$ANSI_NAMES,\"ansi\":[$ansi]}"
}

private fun hex(color: Color) = String.format(Locale.US, "#%06X", 0xFFFFFF and color.toArgb())

private const val ANSI_NAMES =
    "[\"black\",\"red\",\"green\",\"yellow\",\"blue\",\"magenta\",\"cyan\",\"white\"," +
        "\"brightBlack\",\"brightRed\",\"brightGreen\",\"brightYellow\",\"brightBlue\"," +
        "\"brightMagenta\",\"brightCyan\",\"brightWhite\"]"

/**
 * Serves the bundled page from a fake https origin and the terminal font straight out of `res/font`,
 * so the Nerd Font isn't duplicated into assets. (`file://` can't reach res/ for targetSdk 30+.)
 */
private class TerminalAssetClient(private val context: Context) : WebViewClient() {

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        val url = request.url
        if (url.host != "terminal.local") return null
        val path = url.path?.trimStart('/') ?: return null
        if (path == "font") {
            val stream = runCatching { context.resources.openRawResource(FONT_RESOURCE) }.getOrNull() ?: return null
            return response("font/ttf", stream)
        }
        val stream: InputStream = runCatching { context.assets.open("terminal/$path") }.getOrNull() ?: return null
        return response(mimeOf(path.substringAfterLast('.', "")), stream)
    }

    private fun response(mime: String, stream: InputStream) = WebResourceResponse(
        mime,
        null,
        200,
        "OK",
        mapOf("Access-Control-Allow-Origin" to "*", "Cache-Control" to "no-store"),
        stream,
    )

    private fun mimeOf(ext: String) = when (ext) {
        "js" -> "text/javascript"
        "css" -> "text/css"
        "html" -> "text/html"
        "ttf" -> "font/ttf"
        else -> "application/octet-stream"
    }
}

private class TerminalBridge(
    private val vm: TerminalViewModel,
    private val copyTo: (String) -> Unit,
    private val failTo: (String) -> Unit,
    private val ready: () -> Unit,
) {
    @JavascriptInterface
    fun onInput(data: String) {
        if (data.isNotEmpty()) vm.send(data)
    }

    @JavascriptInterface
    fun onResized(cols: Int, rows: Int) {
        if (cols > 1 && rows > 1) vm.resize(cols, rows)
    }

    @JavascriptInterface
    fun onCopy(text: String) {
        copyTo(text)
    }

    @JavascriptInterface
    fun onError(message: String) {
        failTo(message)
    }

    @JavascriptInterface
    fun onReady() {
        ready()
    }
}
