package dev.opencode.mobile.ui.file

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
import androidx.compose.foundation.layout.width
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
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui

    private val api = app.repository.apiFor(server)

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(loading = true, error = null)
            try {
                val content = api.fileContent(path, projectDir())
                _ui.value = _ui.value.copy(content = content, loading = false)
            } catch (e: Exception) {
                _ui.value = _ui.value.copy(loading = false, error = e.message ?: "Failed to read file")
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
        Column(Modifier.fillMaxSize().padding(24.dp)) { Text("Server not found.", color = OcTheme.colors.textSecondary) }
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
                    Text(
                        path.substringAfterLast('/'),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                    )
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
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (ui.error != null) {
                Text(ui.error!!, color = androidx.compose.ui.graphics.Color(0xFFF85149), modifier = Modifier.padding(16.dp))
            }
            val content = ui.content
            if (content != null) {
                if (content.type == "binary") {
                    Text(
                        "Binary file (${content.mimeType ?: "unknown mime"}) — preview not supported.",
                        color = OcTheme.colors.textSecondary,
                        modifier = Modifier.padding(16.dp),
                    )
                } else {
                    val code = content.content
                    val lines = remember(code) { code.split('\n').map { it.replace("\t", "    ") } }
                    val lineCount = lines.size
                    val language = languageForPath(path)
                    Row(
                        Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    ) {
                        Text(
                            language ?: "plain",
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.labelSmall,
                        )
                        Spacer(Modifier.width(12.dp))
                        Text("$lineCount lines", color = OcTheme.colors.textSecondary, style = MaterialTheme.typography.labelSmall)
                    }
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
                            .background(OcTheme.colors.surfaceVariant)
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
                                            if (isTarget) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
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
                                    val highlighted = remember(language, lines[index]) {
                                        CodeHighlighter.highlight(lines[index], language)
                                    }
                                    Text(
                                        text = highlighted,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 13.sp,
                                        lineHeight = 19.sp,
                                        color = MaterialTheme.colorScheme.onSurface,
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