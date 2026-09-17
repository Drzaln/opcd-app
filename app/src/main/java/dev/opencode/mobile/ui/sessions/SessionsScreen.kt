package dev.opencode.mobile.ui.sessions

import dev.opencode.mobile.ui.theme.OcTheme

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.opencode.mobile.AppViewModel
import dev.opencode.mobile.OpenCodeApp
import dev.opencode.mobile.data.model.Session
import dev.opencode.mobile.data.model.SessionStatus
import dev.opencode.mobile.data.model.SessionUpdateBody
import dev.opencode.mobile.data.net.ServerConfig
import dev.opencode.mobile.ui.common.MutedLabel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json as KxJson
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SessionsViewModel(
    private val app: OpenCodeApp,
    private val server: ServerConfig,
    private val projectDir: () -> String?,
) : ViewModel() {

    data class UiState(
        val sessions: List<Session> = emptyList(),
        val projects: List<dev.opencode.mobile.data.model.Project> = emptyList(),
        val statuses: Map<String, SessionStatus> = emptyMap(),
        val loading: Boolean = true,
        val error: String? = null,
        val creating: Boolean = false,
        val query: String = "",
        val shareUrl: String? = null,
    ) {
        val visibleSessions: List<Session>
            get() = if (query.isBlank()) sessions else sessions.filter { session ->
                session.title.contains(query, ignoreCase = true) ||
                    session.directory.contains(query, ignoreCase = true) ||
                    session.id.contains(query, ignoreCase = true)
            }
    }

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui

    private val api = app.repository.apiFor(server)
    private val unshared = mutableSetOf<String>()

    init {
        loadCached()
        refresh()
        refreshProjects()
    }

    private val jsonCache = KxJson { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }
    private val sessionsKey: String get() = "sessions:${server.id}:${projectDir() ?: ""}"

    private fun loadCached() {
        viewModelScope.launch {
            val cached = app.cacheStore.get(sessionsKey) ?: return@launch
            val sessions = runCatching { jsonCache.decodeFromString<List<Session>>(cached) }.getOrNull() ?: return@launch
            if (sessions.isNotEmpty()) {
                _ui.value = _ui.value.copy(sessions = sessions, loading = false)
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(loading = true, error = null)
            try {
                val sessions = api.sessions(projectDir())
                    .sortedByDescending { it.time?.updated ?: it.time?.created ?: 0L }
                    .map { if (it.id in unshared) it.copy(share = null) else it }
                val statuses = runCatching { api.sessionStatus(projectDir()) }.getOrDefault(emptyMap())
                runCatching { app.cacheStore.put(sessionsKey, jsonCache.encodeToString(sessions)) }
                _ui.value = _ui.value.copy(sessions = sessions, statuses = statuses, loading = false)
            } catch (e: Exception) {
                _ui.value = _ui.value.copy(loading = false, error = e.message ?: "Failed to load sessions")
            }
        }
    }

    fun updateTitle(id: String, title: String) {
        viewModelScope.launch {
            runCatching { api.updateSession(id, SessionUpdateBody(title = title), projectDir()) }
            refresh()
        }
    }

    fun refreshProjects() {
        viewModelScope.launch {
            runCatching { api.projects() }.getOrNull()?.let { projects ->
                _ui.value = _ui.value.copy(projects = projects)
            }
        }
    }

    fun createSession() {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(creating = true)
            try {
                api.createSession(dev.opencode.mobile.data.model.CreateSessionBody(), projectDir())
                refresh()
            } catch (e: Exception) {
                _ui.value = _ui.value.copy(creating = false, error = e.message ?: "Failed to create session")
            }
        }
    }

    fun deleteSession(id: String) {
        viewModelScope.launch {
            runCatching { api.deleteSession(id, projectDir()) }
            refresh()
        }
    }

    fun setQuery(query: String) {
        _ui.value = _ui.value.copy(query = query)
    }

    fun shareSession(id: String) {
        viewModelScope.launch {
            val session = runCatching { api.shareSession(id, projectDir()) }.getOrNull()
            val url = session?.share?.url
            if (url.isNullOrBlank()) {
                _ui.value = _ui.value.copy(error = "Could not create share link")
            } else {
                _ui.value = _ui.value.copy(shareUrl = url)
            }
            refresh()
        }
    }

    fun unshareSession(id: String) {
        viewModelScope.launch {
            runCatching { api.unshareSession(id, projectDir()) }
            // 1.18.31 keeps `session.share` populated after unshare, so mask it locally too.
            unshared.add(id)
            refresh()
        }
    }

    fun copyShareUrl(session: Session) {
        val url = session.share?.url
        if (!url.isNullOrBlank()) _ui.value = _ui.value.copy(shareUrl = url)
    }

    fun consumeSharedUrl() {
        _ui.value = _ui.value.copy(shareUrl = null)
    }

    fun forkSession(id: String, onForked: (String) -> Unit) {
        viewModelScope.launch {
            val forked = runCatching { api.forkSession(id, directory = projectDir()) }.getOrNull()
            if (forked == null) {
                _ui.value = _ui.value.copy(error = "Fork failed")
            } else {
                refresh()
                onForked(forked.id)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionsScreen(
    appVm: AppViewModel,
    serverId: String,
    onChat: (String) -> Unit,
    onFiles: () -> Unit,
    onSettings: () -> Unit,
    onTerminal: () -> Unit,
    onDiff: (String) -> Unit,
    onBack: () -> Unit,
) {
    val servers by appVm.servers.collectAsState()
    val server = servers.firstOrNull { it.id == serverId }
    val context = androidx.compose.ui.platform.LocalContext.current
    val app = context.applicationContext as OpenCodeApp

    if (server == null) {
        Column(Modifier.fillMaxSize().padding(24.dp)) {
            Text("Server not found.", color = OcTheme.colors.textSecondary)
            TextButton(onClick = onBack) { Text("Go back") }
        }
        return
    }

    val vm: SessionsViewModel = viewModel(
        key = "sessions_$serverId",
        factory = viewModelFactory {
            initializer { SessionsViewModel(app, server, projectDir = { appVm.currentDirectory.value }) }
        },
    )
    val ui by vm.ui.collectAsState()
    val projectDir by appVm.currentDirectory.collectAsState()

    var showDirPicker by remember { mutableStateOf(false) }
    var editTarget by remember { mutableStateOf<Session?>(null) }
    var editTitle by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<Session?>(null) }
    var serverMenu by remember { mutableStateOf(false) }
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current

    LaunchedEffect(projectDir) {
        vm.refresh()
        vm.refreshProjects()
    }

    val sharedUrl = ui.shareUrl
    LaunchedEffect(sharedUrl) {
        if (!sharedUrl.isNullOrBlank()) {
            clipboard.setText(androidx.compose.ui.text.AnnotatedString(sharedUrl))
            android.widget.Toast.makeText(context, "Share link copied", android.widget.Toast.LENGTH_SHORT).show()
            vm.consumeSharedUrl()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Box {
                        Row(
                            Modifier.clickable { serverMenu = true },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(server.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                            Icon(Icons.Filled.ArrowDropDown, contentDescription = "Switch server", tint = OcTheme.colors.textSecondary)
                        }
                        DropdownMenu(expanded = serverMenu, onDismissRequest = { serverMenu = false }) {
                            for (s in servers) {
                                DropdownMenuItem(
                                    text = { Text(s.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                    onClick = {
                                        serverMenu = false
                                        if (s.id != serverId) appVm.setActive(s.id)
                                    },
                                )
                            }
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    IconButton(onClick = { vm.refresh() }) { Icon(Icons.Filled.Refresh, contentDescription = "Refresh") }
                },
                colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        floatingActionButton = {
            androidx.compose.material3.ExtendedFloatingActionButton(
                onClick = { vm.createSession() },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("New session") },
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
            ) {
                NavigationBarItem(
                    selected = true,
                    onClick = {},
                    icon = { Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null) },
                    label = { Text("Sessions") },
                )
                NavigationBarItem(
                    selected = false,
                    onClick = onFiles,
                    icon = { Icon(Icons.Filled.Folder, contentDescription = null) },
                    label = { Text("Files") },
                )
                NavigationBarItem(
                    selected = false,
                    onClick = onTerminal,
                    icon = { Icon(Icons.Filled.Terminal, contentDescription = null) },
                    label = { Text("Terminal") },
                )
                NavigationBarItem(
                    selected = false,
                    onClick = onSettings,
                    icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                    label = { Text("Settings") },
                )
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            DirectoryBar(
                directory = projectDir,
                onChange = { showDirPicker = true },
            )
            OutlinedTextField(
                value = ui.query,
                onValueChange = { vm.setQuery(it) },
                placeholder = { Text("Filter sessions") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (ui.query.isNotEmpty()) {
                        IconButton(onClick = { vm.setQuery("") }) { Icon(Icons.Filled.Close, contentDescription = "Clear") }
                    }
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            )
            if (ui.error != null) {
                Text(ui.error!!, Modifier.padding(16.dp), color = OcTheme.colors.red)
            }
            if (ui.loading && ui.sessions.isEmpty()) {
                androidx.compose.material3.CircularProgressIndicator(Modifier.padding(24.dp))
            }
            val visible = ui.visibleSessions
            if (!ui.loading && visible.isEmpty()) {
                Column(
                    Modifier.fillMaxWidth().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        if (ui.query.isBlank()) "No sessions in this folder yet" else "No sessions match \"${ui.query}\"",
                        color = OcTheme.colors.textSecondary,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (ui.query.isBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = { vm.createSession() }) { Text("New session") }
                    }
                }
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(visible, key = { it.id }) { session ->
                    SessionCard(
                        modifier = Modifier.animateItem(),
                        session = session,
                        status = ui.statuses[session.id],
                        onClick = { onChat(session.id) },
                        onDiff = { onDiff(session.id) },
                        onRename = { editTarget = session },
                        onDelete = { deleteTarget = session },
                        onShare = { vm.shareSession(session.id) },
                        onCopyLink = { vm.copyShareUrl(session) },
                        onUnshare = { vm.unshareSession(session.id) },
                        onFork = { vm.forkSession(session.id) { newId -> onChat(newId) } },
                    )
                }
            }
        }
    }

    if (showDirPicker) {
        DirectoryPickerDialog(
            projects = ui.projects.map { it.worktree }.filter { it.isNotBlank() }.distinct(),
            current = projectDir,
            onDismiss = { showDirPicker = false },
            onSelect = { dir ->
                showDirPicker = false
                appVm.setDirectory(dir)
            },
        )
    }

    val target = editTarget
    if (target != null) {
        AlertDialog(
            onDismissRequest = { editTarget = null },
            title = { Text("Rename session") },
            text = {
                OutlinedTextField(
                    value = editTitle.ifEmpty { target.title },
                    onValueChange = { editTitle = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                Button(
                    enabled = editTitle.isNotBlank(),
                    onClick = {
                        vm.updateTitle(target.id, editTitle.trim())
                        editTarget = null
                        editTitle = ""
                    },
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editTarget = null }) { Text("Cancel") } },
        )
    }

    val del = deleteTarget
    if (del != null) {
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete session?") },
            text = { Text(del.title.ifEmpty { "Untitled session" }) },
            confirmButton = {
                Button(onClick = {
                    vm.deleteSession(del.id)
                    deleteTarget = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun DirectoryBar(directory: String?, onChange: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(8.dp))
        Text(
            directory ?: "Mac default project",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onChange) { Text("Change") }
    }
}

@Composable
private fun DirectoryPickerDialog(
    projects: List<String>,
    current: String?,
    onDismiss: () -> Unit,
    onSelect: (String?) -> Unit,
) {
    var custom by remember { mutableStateOf(current ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Project folder") },
        text = {
            Column {
                Text(
                    "Sessions are stored per folder. Pick which project folder to show.",
                    style = MaterialTheme.typography.bodySmall,
                    color = OcTheme.colors.textSecondary,
                )
                Spacer(Modifier.height(12.dp))
                Text("Detected projects", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(4.dp))
                if (projects.isEmpty()) {
                    MutedLabel("None detected yet")
                }
                for (path in projects) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(path) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text(path, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = custom,
                    onValueChange = { custom = it },
                    label = { Text("Or type an absolute path") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = { onSelect(null) }) { Text("Default") }
                Spacer(Modifier.width(8.dp))
                Button(
                    enabled = custom.isNotBlank(),
                    onClick = { onSelect(custom.trim()) },
                ) { Text("Use path") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun SessionCard(
    modifier: Modifier = Modifier,
    session: Session,
    status: SessionStatus?,
    onClick: () -> Unit,
    onDiff: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onShare: () -> Unit,
    onCopyLink: () -> Unit,
    onUnshare: () -> Unit,
    onFork: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Box {
        Card(
            modifier = modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onClick, onLongClick = { menu = true }),
        ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    session.title.ifEmpty { "Untitled session" },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    modifier = Modifier.weight(1f),
                )
                if (!session.share?.url.isNullOrBlank()) {
                    MutedLabel("shared", modifier = Modifier.padding(end = 8.dp))
                }
                status?.let {
                    val color = when (it.type) {
                        "busy" -> OcTheme.colors.orange
                        "retry" -> OcTheme.colors.red
                        else -> OcTheme.colors.green
                    }
                    Box(
                        Modifier
                            .width(8.dp)
                            .height(8.dp)
                            .background(color, shape = CircleShape),
                    )
                }
            }
            MutedLabel(session.directory)
            Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                MutedLabel(relativeTime(session.time?.updated ?: session.time?.created))
                Spacer(Modifier.width(12.dp))
                val summary = session.summary
                if (summary != null && (summary.additions > 0 || summary.deletions > 0)) {
                    Text("+${summary.additions} -${summary.deletions}", color = OcTheme.colors.green, style = MaterialTheme.typography.labelSmall)
                    Spacer(Modifier.width(8.dp))
                    Text("${summary.files} files", color = OcTheme.colors.textSecondary, style = MaterialTheme.typography.labelSmall)
                }
                Spacer(Modifier.weight(1f))
                Text("Diff", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium, modifier = Modifier.clickable(onClick = onDiff))
            }
            val tokens = session.tokens
            val cost = session.cost
            if ((tokens != null && (tokens.input + tokens.output) > 0) || (cost != null && cost > 0)) {
                Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (tokens != null) {
                        MutedLabel("${"%,d".format(tokens.input + tokens.output)} tok")
                        Spacer(Modifier.width(12.dp))
                    }
                    if (cost != null && cost > 0) {
                        MutedLabel("$" + if (cost >= 0.01) "%.2f".format(cost) else "%.4f".format(cost))
                    }
                }
            }
        }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; onRename() })
            DropdownMenuItem(text = { Text("Fork session") }, onClick = { menu = false; onFork() })
            if (session.share?.url.isNullOrBlank()) {
                DropdownMenuItem(text = { Text("Share link") }, onClick = { menu = false; onShare() })
            } else {
                DropdownMenuItem(text = { Text("Copy share link") }, onClick = { menu = false; onCopyLink() })
                DropdownMenuItem(text = { Text("Unshare") }, onClick = { menu = false; onUnshare() })
            }
            DropdownMenuItem(
                text = { Text("Delete", color = OcTheme.colors.red) },
                onClick = { menu = false; onDelete() },
            )
        }
    }
}

private fun relativeTime(epochMillis: Long?): String {
    if (epochMillis == null) return ""
    val diff = System.currentTimeMillis() - epochMillis
    return when {
        diff < 60_000 -> "just now"
        diff < 3_600_000 -> "${diff / 60_000}m ago"
        diff < 86_400_000 -> "${diff / 3_600_000}h ago"
        diff < 2_592_000_000 -> "${diff / 86_400_000}d ago"
        else -> formatTime(epochMillis)
    }
}

private fun formatTime(epochMillis: Long?): String {
    if (epochMillis == null) return ""
    return try {
        SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(epochMillis))
    } catch (e: Exception) {
        ""
    }
}