package dev.opencode.mobile.ui.file

import androidx.compose.ui.graphics.luminance
import dev.opencode.mobile.ui.theme.OcTheme

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.InsertDriveFile
import dev.opencode.mobile.ui.common.Banner
import dev.opencode.mobile.ui.common.EmptyState
import dev.opencode.mobile.ui.common.StatusPill
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.opencode.mobile.AppViewModel
import dev.opencode.mobile.OpenCodeApp
import dev.opencode.mobile.data.model.FileContent
import dev.opencode.mobile.data.net.ServerConfig
import dev.opencode.mobile.ui.common.CodeHighlighter
import dev.opencode.mobile.ui.common.languageForPath
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class FileViewModel(
    app: OpenCodeApp,
    private val server: ServerConfig,
    private val path: String,
    private val projectDir: () -> String?,
) : ViewModel() {

    data class UiState(
        val content: FileContent? = null,
        val loading: Boolean = true,
        val error: String? = null,
        /** Set when showing the copy saved on the phone (the server didn't answer). */
        val savedAt: Long? = null,
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui

    private val api = app.repository.apiFor(server)
    private val cache = app.cacheStore
    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
    private fun cacheKey() = "file:${server.id}:${projectDir().orEmpty()}:$path"

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(loading = true, error = null)
            try {
                val content = api.fileContent(path, projectDir())
                _ui.value = _ui.value.copy(content = content, loading = false, savedAt = null)
                // Keep text files (up to ~400 KB) readable offline.
                if (content.type != "binary" && content.content.length <= 400_000) {
                    cache.put(cacheKey(), json.encodeToString(FileContent.serializer(), content))
                }
            } catch (e: Exception) {
                val saved = cache.getEntry(cacheKey())?.let { entry ->
                    runCatching { json.decodeFromString(FileContent.serializer(), entry.json) }.getOrNull()?.let { it to entry.updatedAt }
                }
                _ui.value = if (saved != null) _ui.value.copy(content = saved.first, loading = false, savedAt = saved.second, error = null)
                else _ui.value.copy(loading = false, error = e.message ?: "Failed to read file")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileViewerScreen(
    appVm: AppViewModel,
    serverId: String,
    path: String,
    line: Int?,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as OpenCodeApp
    val servers by appVm.servers.collectAsState()
    val server = remember(servers, serverId) { servers.firstOrNull { it.id == serverId } }

    if (server == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
            EmptyState(Icons.Filled.ErrorOutline, "Server not found", body = "It may have been removed.", action = "Back", onAction = onBack)
        }
        return
    }

    val vm: FileViewModel = viewModel(
        key = "file_${serverId}_$path",
        factory = viewModelFactory {
            initializer { FileViewModel(app, server, path, projectDir = { appVm.currentDirectory.value }) }
        },
    )
    val ui by vm.ui.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            path.substringAfterLast('/'),
                            style = MaterialTheme.typography.titleMedium,
                            color = OcTheme.colors.textPrimary,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                        val dir = path.substringBeforeLast('/', "")
                        if (dir.isNotEmpty()) Text(
                            dir,
                            fontSize = 11.sp,
                            color = OcTheme.colors.textSecondary,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.StartEllipsis,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    val text = ui.content?.takeIf { it.type != "binary" }?.content
                    if (!text.isNullOrBlank()) {
                        IconButton(onClick = {
                            val fenced = "```" + (languageForPath(path) ?: "") + "\n" + text + "\n```"
                            dev.opencode.mobile.ui.common.shareText(context, fenced, path)
                        }) { Icon(Icons.Filled.Share, contentDescription = "Share file") }
                    }
                },
                colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(
                    containerColor = OcTheme.colors.surface,
                ),
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            val content = ui.content
            val error = ui.error
            if (content == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                    if (error != null) EmptyState(
                        Icons.Filled.ErrorOutline, "Couldn't open this file", body = error,
                        action = "Try again", onAction = vm::refresh, tint = OcTheme.colors.red,
                    ) else if (ui.loading) androidx.compose.material3.CircularProgressIndicator(
                        color = OcTheme.colors.accent, strokeWidth = 2.dp, modifier = Modifier.size(28.dp),
                    )
                }
            }
            if (content != null) {
                if (error != null) Banner(error, error = true, onDismiss = vm::refresh)
                ui.savedAt?.let { dev.opencode.mobile.ui.common.SavedCopyNote(it, onRetry = vm::refresh) }
                if (content.type == "binary") {
                    Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                        EmptyState(
                            Icons.Filled.InsertDriveFile, "No preview for this file",
                            body = "It's a binary file (${content.mimeType ?: "unknown type"}).",
                            tint = OcTheme.colors.textSecondary,
                        )
                    }
                } else {
                    val code = content.content
                    val lines = remember(code) { code.split('\n').map { it.replace("\t", "    ") } }
                    val lineCount = lines.size
                    val language = languageForPath(path)
                    val light = OcTheme.colors.bg.luminance() > 0.5f
                    Row(
                        Modifier.fillMaxWidth().background(OcTheme.colors.surface).padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        StatusPill(language ?: "plain text", OcTheme.colors.link, dot = false)
                        Spacer(Modifier.width(10.dp))
                        Text(if (lineCount == 1) "1 line" else "$lineCount lines", color = OcTheme.colors.textSecondary, fontSize = 12.sp)
                        Spacer(Modifier.weight(1f))
                        Text("Read only", color = OcTheme.colors.textSecondary, fontSize = 12.sp)
                    }
                    androidx.compose.material3.HorizontalDivider(color = OcTheme.colors.border)
                    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
                    LaunchedEffect(code, line, lineCount) {
                        val target = (line ?: return@LaunchedEffect) - 1
                        if (target in 0 until lineCount) listState.scrollToItem(target)
                    }
                    // One horizontal scroll for the whole code area: share a single ScrollState via a
                    // fixed content width (per-line scroll states would fight each other).
                    val gutter = 52.dp
                    val density = androidx.compose.ui.platform.LocalDensity.current
                    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
                    val monoStyle = androidx.compose.ui.text.TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                    )
                    val charWidthPx = remember(measurer) {
                        measurer.measure(androidx.compose.ui.text.AnnotatedString("M"), monoStyle, softWrap = false)
                            .size.width.toFloat()
                    }
                    val maxChars = remember(lines) { lines.maxOfOrNull { it.length } ?: 1 }
                    val contentWidth = with(density) { (charWidthPx * maxChars).toDp() } + gutter + 24.dp
                    val hScroll = rememberScrollState()
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(OcTheme.colors.bg)
                            .horizontalScroll(hScroll),
                    ) {
                        androidx.compose.foundation.lazy.LazyColumn(
                            state = listState,
                            modifier = Modifier.width(contentWidth).fillMaxHeight(),
                        ) {
                            items(
                                count = lineCount,
                                key = { it },
                            ) { index ->
                                val isTarget = line != null && index == line - 1
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .background(
                                            if (isTarget) OcTheme.colors.accent.copy(alpha = 0.16f)
                                            else androidx.compose.ui.graphics.Color.Transparent,
                                        )
                                        .padding(vertical = 1.dp),
                                ) {
                                    Text(
                                        "${index + 1}",
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 12.sp,
                                        lineHeight = 19.sp,
                                        color = OcTheme.colors.textSecondary,
                                        textAlign = androidx.compose.ui.text.style.TextAlign.End,
                                        modifier = Modifier.width(gutter).padding(end = 10.dp),
                                    )
                                    val highlighted = remember(language, lines[index], light) {
                                        CodeHighlighter.highlight(lines[index], language, light)
                                    }
                                    Text(
                                        text = highlighted,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 13.sp,
                                        lineHeight = 19.sp,
                                        color = OcTheme.colors.textBody,
                                        softWrap = false,
                                        modifier = Modifier.padding(end = 16.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}