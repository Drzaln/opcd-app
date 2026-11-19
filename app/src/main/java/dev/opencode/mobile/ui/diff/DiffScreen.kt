package dev.opencode.mobile.ui.diff

import androidx.compose.ui.text.withStyle
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import dev.opencode.mobile.ui.theme.OcTheme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.opencode.mobile.AppViewModel
import dev.opencode.mobile.OpenCodeApp
import dev.opencode.mobile.data.model.FileDiff
import dev.opencode.mobile.data.net.ServerConfig
import dev.opencode.mobile.ui.common.DiffKind
import dev.opencode.mobile.ui.common.DiffLines
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

private const val MAX_SAVED = 1_500_000

class DiffViewModel(
    app: OpenCodeApp,
    private val server: ServerConfig,
    private val sessionId: String,
    private val messageId: String?,
    private val projectDir: () -> String?,
) : ViewModel() {

    data class UiState(
        val diffs: List<FileDiff> = emptyList(),
        val loading: Boolean = true,
        val error: String? = null,
        /** Set when showing the copy saved on the phone (the server didn't answer). */
        val savedAt: Long? = null,
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui

    private val api = app.repository.apiFor(server)
    private val cache = app.cacheStore
    private val cacheKey = "diff:${server.id}:$sessionId:${messageId.orEmpty()}"
    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(loading = true, error = null)
            try {
                val diffs = api.sessionDiff(sessionId, messageId = messageId, directory = projectDir())
                _ui.value = _ui.value.copy(diffs = diffs, loading = false, savedAt = null)
                val encoded = json.encodeToString(kotlinx.serialization.builtins.ListSerializer(FileDiff.serializer()), diffs)
                if (encoded.length <= MAX_SAVED) cache.put(cacheKey, encoded)
            } catch (e: Exception) {
                val saved = cache.getEntry(cacheKey)?.let { entry ->
                    runCatching { json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(FileDiff.serializer()), entry.json) }.getOrNull()?.let { it to entry.updatedAt }
                }
                _ui.value = if (saved != null) _ui.value.copy(diffs = saved.first, loading = false, savedAt = saved.second)
                else _ui.value.copy(loading = false, error = e.message ?: "Failed to load diff")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiffScreen(
    appVm: AppViewModel,
    serverId: String,
    sessionId: String,
    messageId: String?,
    onBack: () -> Unit,
    onOpenFile: ((path: String, line: Int?) -> Unit)? = null,
) {
    val context = LocalContext.current
    val app = context.applicationContext as OpenCodeApp
    val servers by appVm.servers.collectAsState()
    val server = remember(servers, serverId) { servers.firstOrNull { it.id == serverId } }

    if (server == null) {
        Column(Modifier.fillMaxSize().padding(24.dp)) { Text("Server not found.", color = OcTheme.colors.textSecondary) }
        return
    }

    val vm: DiffViewModel = viewModel(
        key = "diff_${serverId}_${sessionId}_${messageId ?: "all"}",
        factory = viewModelFactory {
            initializer {
                DiffViewModel(app, server, sessionId, messageId, projectDir = { appVm.currentDirectory.value })
            }
        },
    )
    val ui by vm.ui.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (messageId != null) "Changes · this message" else "Changes") },
                colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    IconButton(onClick = { vm.refresh() }) { Icon(Icons.Filled.Refresh, contentDescription = "Refresh") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            ui.savedAt?.let { dev.opencode.mobile.ui.common.SavedCopyNote(it, onRetry = vm::refresh) }
            if (ui.error != null) {
                dev.opencode.mobile.ui.common.Banner(ui.error!!, error = true, onDismiss = {})
            }
            if (ui.loading && ui.diffs.isEmpty()) {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { androidx.compose.material3.CircularProgressIndicator() }
            }
            if (!ui.loading && ui.diffs.isEmpty() && ui.error == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    dev.opencode.mobile.ui.common.EmptyState(
                        icon = androidx.compose.material.icons.Icons.Filled.Check,
                        title = if (messageId != null) "No changes from this message" else "No changes in this session",
                        body = "Files opencode edits show up here, line by line.",
                        tint = OcTheme.colors.green,
                    )
                }
            }
            val computed = remember(ui.diffs) {
                ui.diffs.map { diff ->
                    diff to if (diff.patch.isNotBlank()) DiffLines.parsePatch(diff.patch) else DiffLines.compute(diff.before, diff.after)
                }
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                for ((diff, lines) in computed) {
                    stickyHeader(key = "header_${diff.file}") {
                        val firstChange = lines.firstOrNull { it.kind != DiffKind.CONTEXT }?.let { it.newNo ?: it.oldNo }
                        DiffHeader(diff, onOpen = onOpenFile?.let { open -> { open(diff.file, firstChange) } })
                    }
                    items(lines, key = { "${diff.file}_${it.hashCode()}" }) { line ->
                        Box(
                            if (onOpenFile != null && line.kind != DiffKind.DEL) Modifier.clickable { onOpenFile(diff.file, line.newNo) } else Modifier,
                        ) { DiffRow(line.kind, line.oldNo, line.newNo, line.text) }
                    }
                }
            }
        }
    }
}

@Composable
private fun DiffHeader(diff: FileDiff, onOpen: (() -> Unit)? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(OcTheme.colors.surface)
            .then(if (onOpen != null) Modifier.clickable(onClickLabel = "Open in editor", onClick = onOpen) else Modifier)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // "src/app/" muted, "index.tsx" bright: the file name is what you scan for.
        val dir = diff.file.substringBeforeLast('/', "")
        Text(
            androidx.compose.ui.text.buildAnnotatedString {
                if (dir.isNotEmpty()) withStyle(androidx.compose.ui.text.SpanStyle(color = OcTheme.colors.textSecondary)) { append("$dir/") }
                withStyle(androidx.compose.ui.text.SpanStyle(color = OcTheme.colors.textPrimary, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)) { append(diff.file.substringAfterLast('/')) }
            },
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.StartEllipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Text("+${diff.additions}", color = OcTheme.colors.green, fontSize = 12.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
        Spacer(Modifier.width(6.dp))
        Text("−${diff.deletions}", color = OcTheme.colors.red, fontSize = 12.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
        if (onOpen != null) Icon(androidx.compose.material.icons.Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Open in editor", tint = OcTheme.colors.textSecondary, modifier = Modifier.padding(start = 4.dp).size(18.dp))
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(OcTheme.colors.border))
}

@Composable
private fun DiffRow(kind: DiffKind, oldNo: Int?, newNo: Int?, text: String) {
    val bg = when (kind) {
        DiffKind.ADD -> OcTheme.colors.greenBg
        DiffKind.DEL -> OcTheme.colors.redBg
        DiffKind.CONTEXT -> androidx.compose.ui.graphics.Color.Transparent
    }
    val fg = when (kind) {
        DiffKind.ADD -> OcTheme.colors.green
        DiffKind.DEL -> OcTheme.colors.red
        DiffKind.CONTEXT -> MaterialTheme.colorScheme.onSurface
    }
    Row(Modifier.fillMaxWidth().background(bg)) {
        Text(
            (oldNo?.toString() ?: "").padStart(4),
            color = OcTheme.colors.textSecondary.copy(alpha = 0.6f),
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(44.dp).padding(vertical = 1.dp),
        )
        Text(
            (newNo?.toString() ?: "").padStart(4),
            color = OcTheme.colors.textSecondary.copy(alpha = 0.6f),
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(44.dp).padding(vertical = 1.dp),
        )
        Text(
            when (kind) {
                DiffKind.ADD -> "+"
                DiffKind.DEL -> "-"
                DiffKind.CONTEXT -> " "
            },
            color = fg,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(14.dp).padding(vertical = 1.dp),
        )
        Text(
            text.ifEmpty { " " },
            color = fg,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            lineHeight = 16.sp,
            modifier = Modifier.padding(vertical = 1.dp),
        )
    }
}