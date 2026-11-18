package dev.opencode.mobile.ui.files

import dev.opencode.mobile.ui.theme.OcTheme

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.opencode.mobile.AppViewModel
import dev.opencode.mobile.OpenCodeApp
import dev.opencode.mobile.data.model.FileNode
import dev.opencode.mobile.data.model.FindMatch
import dev.opencode.mobile.data.net.ServerConfig
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

enum class SearchMode { FILES, CONTENT }

class FilesViewModel(
    app: OpenCodeApp,
    private val server: ServerConfig,
    private val dir: String,
    private val projectDir: () -> String?,
) : ViewModel() {

    data class UiState(
        val files: List<FileNode> = emptyList(),
        val loading: Boolean = true,
        val error: String? = null,
        val query: String = "",
        val mode: SearchMode = SearchMode.FILES,
        val fileResults: List<String> = emptyList(),
        val contentResults: List<FindMatch> = emptyList(),
        val searching: Boolean = false,
    ) {
        val searchingActive: Boolean get() = query.isNotBlank()
    }

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui

    private val api = app.repository.apiFor(server)
    private var searchJob: kotlinx.coroutines.Job? = null

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(loading = true, error = null)
            try {
                val files = api.listFiles(dir, projectDir()).sortedWith(compareBy({ it.type != "directory" }, { it.name.lowercase() }))
                _ui.value = _ui.value.copy(files = files, loading = false)
            } catch (e: Exception) {
                _ui.value = _ui.value.copy(loading = false, error = e.message ?: "Failed to list files")
            }
        }
    }

    fun setMode(mode: SearchMode) {
        _ui.value = _ui.value.copy(mode = mode)
        runSearch(_ui.value.query)
    }

    fun onQueryChange(query: String) {
        _ui.value = _ui.value.copy(query = query)
        runSearch(query)
    }

    private fun runSearch(query: String) {
        searchJob?.cancel()
        if (query.isBlank()) {
            _ui.value = _ui.value.copy(fileResults = emptyList(), contentResults = emptyList(), searching = false)
            return
        }
        searchJob = viewModelScope.launch {
            delay(300)
            _ui.value = _ui.value.copy(searching = true)
            try {
                if (_ui.value.mode == SearchMode.FILES) {
                    val results = api.findFiles(query, limit = 50, directory = projectDir())
                    _ui.value = _ui.value.copy(fileResults = results, contentResults = emptyList(), searching = false)
                } else {
                    val results = api.findText(query, projectDir())
                    _ui.value = _ui.value.copy(contentResults = results, fileResults = emptyList(), searching = false)
                }
            } catch (e: Exception) {
                _ui.value = _ui.value.copy(searching = false, error = e.message ?: "Search failed")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun FilesScreen(
    appVm: AppViewModel,
    serverId: String,
    dir: String,
    onOpenDir: (String) -> Unit,
    onOpenFile: (String, Int?) -> Unit,
    onBack: (() -> Unit)?,
    recentFiles: List<String> = emptyList(),
    onViewReadOnly: ((String) -> Unit)? = null,
    selectedPath: String? = null,
    title: String? = null,
    bottomBar: @Composable () -> Unit = {},
    /** Side-pane tree (tablet / landscape): small section header instead of an app bar, no mode chips. */
    compact: Boolean = false,
) {
    val context = LocalContext.current
    val app = context.applicationContext as OpenCodeApp
    val servers by appVm.servers.collectAsState()
    val server = remember(servers, serverId) { servers.firstOrNull { it.id == serverId } }

    if (server == null) {
        Column(Modifier.fillMaxSize().padding(24.dp)) {
            Text("Server not found.", color = OcTheme.colors.textSecondary)
        }
        return
    }

    val vm: FilesViewModel = viewModel(
        key = "files_${serverId}_$dir",
        factory = viewModelFactory {
            initializer { FilesViewModel(app, server, dir, projectDir = { appVm.currentDirectory.value }) }
        },
    )
    val ui by vm.ui.collectAsState()
    val parent = dir.substringBeforeLast('/', "")

    LaunchedEffect(dir) {
        vm.refresh()
    }

    var menuFor by remember { mutableStateOf<String?>(null) }
    @Composable
    fun ReadOnlyMenu(path: String) {
        DropdownMenu(expanded = menuFor == path, onDismissRequest = { menuFor = null }) {
            DropdownMenuItem(text = { Text("Open in editor") }, onClick = { menuFor = null; onOpenFile(path, null) })
            onViewReadOnly?.let { view ->
                DropdownMenuItem(text = { Text("View read-only") }, onClick = { menuFor = null; view(path) })
            }
        }
    }
    fun Modifier.fileClicks(path: String, line: Int? = null) = combinedClickable(
        onClick = { onOpenFile(path, line) },
        onLongClick = if (onViewReadOnly != null) ({ menuFor = path }) else null,
    )

    Scaffold(
        bottomBar = bottomBar,
        contentWindowInsets = if (compact) androidx.compose.foundation.layout.WindowInsets(0) else androidx.compose.material3.ScaffoldDefaults.contentWindowInsets,
        topBar = {
            if (compact) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (dir.isNotEmpty()) {
                        IconButton(onClick = { onOpenDir(parent) }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Up one folder", modifier = Modifier.padding(0.dp))
                        }
                    }
                    Text(
                        if (dir.isEmpty()) (title ?: "Explorer").uppercase() else dir.substringAfterLast('/'),
                        style = MaterialTheme.typography.labelMedium,
                        color = OcTheme.colors.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { vm.refresh() }) { Icon(Icons.Filled.Refresh, contentDescription = "Refresh", tint = OcTheme.colors.textSecondary) }
                }
            } else TopAppBar(
                title = {
                    Text(
                        if (dir.isEmpty() && title != null) title else "/${dir.ifEmpty { "project root" }}",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
                navigationIcon = {
                    if (dir.isNotEmpty() || onBack != null) {
                        IconButton(onClick = { if (dir.isNotEmpty()) onOpenDir(parent) else onBack?.invoke() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { vm.refresh() }) { Icon(Icons.Filled.Refresh, contentDescription = "Refresh") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            SearchBar(
                query = ui.query,
                mode = ui.mode,
                onQueryChange = { vm.onQueryChange(it) },
                onModeChange = { vm.setMode(it) },
                showModes = !compact,
            )
            if (ui.error != null) {
                Text(ui.error!!, color = androidx.compose.ui.graphics.Color(0xFFF85149), modifier = Modifier.padding(16.dp))
            }
            if (ui.searchingActive) {
                if (ui.searching) {
                    androidx.compose.material3.CircularProgressIndicator(Modifier.padding(16.dp))
                }
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 6.dp),
                ) {
                    if (ui.mode == SearchMode.FILES) {
                        items(ui.fileResults, key = { it }) { path ->
                            Row(
                                Modifier.fillMaxWidth().animateItem().fileClicks(path).padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                ReadOnlyMenu(path)
                                Icon(Icons.Filled.Description, contentDescription = null, tint = OcTheme.colors.textSecondary)
                                Spacer(Modifier.width(12.dp))
                                Text(path, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    } else {
                        items(ui.contentResults, key = { "${it.path.text}:${it.lineNumber}" }) { match ->
                            Row(
                                Modifier.fillMaxWidth().animateItem().fileClicks(match.path.text, match.lineNumber).padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.Top,
                            ) {
                                Icon(Icons.Filled.Description, contentDescription = null, tint = OcTheme.colors.textSecondary)
                                Spacer(Modifier.width(12.dp))
                                Column {
                                    Text(
                                        "${match.path.text}:${match.lineNumber}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(match.lines.text.trim(), style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                    if (!ui.searching && ((ui.mode == SearchMode.FILES && ui.fileResults.isEmpty()) || (ui.mode == SearchMode.CONTENT && ui.contentResults.isEmpty()))) {
                        item { Text("No results", color = OcTheme.colors.textSecondary, modifier = Modifier.padding(16.dp)) }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 6.dp),
                ) {
                    if (dir.isEmpty() && recentFiles.isNotEmpty()) {
                        item(key = "recent_header") {
                            Text(
                                "RECENT",
                                style = MaterialTheme.typography.labelMedium,
                                letterSpacing = androidx.compose.ui.unit.TextUnit(0.96f, androidx.compose.ui.unit.TextUnitType.Sp),
                                color = OcTheme.colors.textSecondary,
                                modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp),
                            )
                        }
                        items(recentFiles.take(5), key = { "recent_$it" }) { path ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .fileClicks(path)
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                ReadOnlyMenu(path)
                                Icon(Icons.Filled.History, contentDescription = null, tint = OcTheme.colors.textSecondary)
                                Spacer(Modifier.width(12.dp))
                                Column {
                                    Text(path.substringAfterLast('/'), style = MaterialTheme.typography.bodyMedium, color = OcTheme.colors.textPrimary, maxLines = 1)
                                    Text(path, style = MaterialTheme.typography.labelSmall, color = OcTheme.colors.textSecondary, maxLines = 1, overflow = TextOverflow.StartEllipsis)
                                }
                            }
                        }
                        item(key = "files_header") {
                            Text(
                                "FILES",
                                style = MaterialTheme.typography.labelMedium,
                                letterSpacing = androidx.compose.ui.unit.TextUnit(0.96f, androidx.compose.ui.unit.TextUnitType.Sp),
                                color = OcTheme.colors.textSecondary,
                                modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
                            )
                        }
                    }
                    items(ui.files, key = { it.path }) { node ->
                        val isDir = node.type == "directory"
                        if (!isDir) ReadOnlyMenu(node.path)
                        androidx.compose.material3.ListItem(
                            colors = androidx.compose.material3.ListItemDefaults.colors(
                                containerColor = if (node.path == selectedPath) OcTheme.colors.accentSoft else androidx.compose.ui.graphics.Color.Transparent,
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .animateItem()
                                .then(if (isDir) Modifier.clickable { onOpenDir(node.path) } else Modifier.fileClicks(node.path)),
                            leadingContent = {
                                Icon(
                                    if (isDir) Icons.Filled.Folder else Icons.Filled.Description,
                                    contentDescription = null,
                                    tint = if (isDir) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                            headlineContent = {
                                Text(
                                    node.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = if (node.ignored) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                    else MaterialTheme.colorScheme.onSurface,
                                )
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchBar(
    query: String,
    mode: SearchMode,
    onQueryChange: (String) -> Unit,
    onModeChange: (SearchMode) -> Unit,
    showModes: Boolean = true,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        androidx.compose.material3.OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            placeholder = { Text(if (mode == SearchMode.FILES) "Search files" else "Search in files") },
            singleLine = true,
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            modifier = Modifier.fillMaxWidth(),
        )
        if (showModes) Row(Modifier.padding(top = 4.dp)) {
            FilterChip(
                selected = mode == SearchMode.FILES,
                onClick = { onModeChange(SearchMode.FILES) },
                label = { Text("Files") },
            )
            Spacer(Modifier.width(8.dp))
            FilterChip(
                selected = mode == SearchMode.CONTENT,
                onClick = { onModeChange(SearchMode.CONTENT) },
                label = { Text("Content") },
            )
        }
    }
}