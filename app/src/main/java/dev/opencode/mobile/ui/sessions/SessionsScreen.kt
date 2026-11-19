package dev.opencode.mobile.ui.sessions

import dev.opencode.mobile.ui.theme.OcTheme

import androidx.compose.foundation.background
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.draw.clip
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
import dev.opencode.mobile.data.net.GitResult
import dev.opencode.mobile.data.net.GitRunner
import dev.opencode.mobile.data.net.ServerConfig
import dev.opencode.mobile.ui.common.MutedLabel
import dev.opencode.mobile.ui.common.ConnectionIndicator
import dev.opencode.mobile.ui.common.shortenPath
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
        val branch: String? = null,
        val branches: List<String> = emptyList(),
        val branchLoading: Boolean = false,
        val branchOpRunning: Boolean = false,
        val branchError: String? = null,
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
    private val git = GitRunner(server, api)
    private val unshared = mutableSetOf<String>()

    init {
        loadCached()
        refresh()
        refreshProjects()
        refreshBranch()
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

    fun switchDirectory() {
        _ui.value = _ui.value.copy(
            sessions = emptyList(),
            statuses = emptyMap(),
            loading = true,
            error = null,
            query = "",
            branch = null,
            branches = emptyList(),
            branchError = null,
        )
        loadCached()
        refresh()
        refreshBranch()
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

    fun refreshBranch() {
        viewModelScope.launch {
            val info = runCatching { api.vcs(projectDir()) }.getOrNull()
            _ui.value = _ui.value.copy(branch = info?.branch)
        }
    }

    fun loadBranches() {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(branchLoading = true, branchError = null)
            val result = git.run("git --no-pager -c color.ui=false branch --format='%(refname:short)'", projectDir())
            val branches = if (result.ok) {
                result.output.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.distinct().sorted().toList()
            } else {
                emptyList()
            }
            _ui.value = _ui.value.copy(
                branches = branches,
                branchLoading = false,
                branchError = if (result.ok) null else result.output.ifBlank { "git branch failed (exit ${result.exitCode})" },
            )
        }
    }

    fun checkoutBranch(name: String) = runBranchOp(name) { git.run("git --no-pager -c color.ui=false checkout $name", projectDir()) }

    fun createBranch(name: String) = runBranchOp(name) { git.run("git --no-pager -c color.ui=false checkout -b $name", projectDir()) }

    fun clearBranchError() {
        _ui.value = _ui.value.copy(branchError = null)
    }

    private fun runBranchOp(name: String, op: suspend () -> GitResult) {
        if (!GitRunner.isValidBranch(name)) {
            _ui.value = _ui.value.copy(branchError = "Invalid branch name")
            return
        }
        viewModelScope.launch {
            _ui.value = _ui.value.copy(branchOpRunning = true, branchError = null)
            val result = op()
            if (result.ok) {
                _ui.value = _ui.value.copy(branchOpRunning = false)
                refreshBranch()
                loadBranches()
            } else {
                _ui.value = _ui.value.copy(
                    branchOpRunning = false,
                    branchError = result.output.ifBlank { "git exited with code ${result.exitCode}" },
                )
            }
        }
    }

    fun createSession(onCreated: (String) -> Unit) {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(creating = true)
            try {
                val session = api.createSession(dev.opencode.mobile.data.model.CreateSessionBody(), projectDir())
                refresh()
                onCreated(session.id)
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
    /** Opens the Code tab. */
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
    val connection by appVm.connection.collectAsState()

    var showDirPicker by remember { mutableStateOf(false) }
    var showBranchSheet by remember { mutableStateOf(false) }
    var editTarget by remember { mutableStateOf<Session?>(null) }
    var editTitle by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<Session?>(null) }
    var serverMenu by remember { mutableStateOf(false) }
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current

    LaunchedEffect(projectDir) {
        vm.switchDirectory()
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
                    ConnectionIndicator(state = connection, onRecheck = { appVm.refreshConnection() })
                    IconButton(onClick = { vm.refresh() }) { Icon(Icons.Filled.Refresh, contentDescription = "Refresh") }
                },
                colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        floatingActionButton = {
            androidx.compose.material3.ExtendedFloatingActionButton(
                onClick = { vm.createSession { onChat(it) } },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("New session") },
            )
        },
        bottomBar = {
            dev.opencode.mobile.ui.common.AppBottomBar(dev.opencode.mobile.ui.common.AppTab.SESSIONS) { tab ->
                when (tab) {
                    dev.opencode.mobile.ui.common.AppTab.CODE -> onFiles()
                    dev.opencode.mobile.ui.common.AppTab.TERMINAL -> onTerminal()
                    dev.opencode.mobile.ui.common.AppTab.SETTINGS -> onSettings()
                    dev.opencode.mobile.ui.common.AppTab.SESSIONS -> Unit
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            DirectoryBar(
                directory = projectDir,
                onChange = { showDirPicker = true },
            )
            if (!ui.branch.isNullOrBlank()) {
                BranchBar(
                    branch = ui.branch!!,
                    onChange = {
                        vm.clearBranchError()
                        showBranchSheet = true
                        vm.loadBranches()
                    },
                )
            }
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
            if (connection.status == dev.opencode.mobile.ConnectionStatus.OFFLINE) {
                dev.opencode.mobile.ui.common.OfflineBanner(connection, onRetry = { appVm.refreshConnection(); vm.refresh() })
            } else if (ui.error != null) {
                dev.opencode.mobile.ui.common.Banner(ui.error!!, error = true, onDismiss = { vm.refresh() })
            }
            if (ui.loading && ui.sessions.isEmpty()) {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    androidx.compose.material3.CircularProgressIndicator()
                }
            }
            val visible = ui.visibleSessions
            if (!ui.loading && visible.isEmpty()) {
                Box(Modifier.fillMaxWidth().padding(top = 32.dp), contentAlignment = Alignment.Center) {
                    if (ui.query.isBlank()) dev.opencode.mobile.ui.common.EmptyState(
                        icon = Icons.AutoMirrored.Filled.Chat,
                        title = "No sessions here yet",
                        body = "Start one to ask opencode about ${folderName(projectDir)}.",
                        action = "New session",
                        onAction = { vm.createSession { onChat(it) } },
                    ) else dev.opencode.mobile.ui.common.EmptyState(
                        icon = Icons.Filled.Search,
                        title = "No matches",
                        body = "No session title contains \"${ui.query}\".",
                        tint = OcTheme.colors.textSecondary,
                    )
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
                        showPath = session.directory != projectDir,
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
        DirectoryPickerSheet(
            projects = ui.projects.map { it.worktree }.filter { it.isNotBlank() }.distinct().sorted(),
            current = projectDir,
            onDismiss = { showDirPicker = false },
            onSelect = { dir ->
                showDirPicker = false
                if (dir != projectDir) appVm.setDirectory(dir)
            },
        )
    }

    if (showBranchSheet) {
        BranchSheet(
            current = ui.branch,
            branches = ui.branches,
            loading = ui.branchLoading,
            running = ui.branchOpRunning,
            error = ui.branchError,
            onDismiss = { showBranchSheet = false },
            onCheckout = { vm.checkoutBranch(it) },
            onCreate = { vm.createBranch(it) },
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
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onChange)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                folderName(directory),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                directory?.let { shortenPath(it, keep = 3) } ?: "Server default folder",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = OcTheme.colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Icon(Icons.Filled.ArrowDropDown, contentDescription = "Change folder", tint = OcTheme.colors.textSecondary)
    }
}

@Composable
private fun BranchBar(branch: String, onChange: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onChange)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.AccountTree,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text("Branch", style = MaterialTheme.typography.labelSmall, color = OcTheme.colors.textSecondary)
        Spacer(Modifier.width(8.dp))
        Text(
            branch,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Icon(
            Icons.Filled.SwapHoriz,
            contentDescription = "Switch or create branch",
            tint = OcTheme.colors.textSecondary,
            modifier = Modifier.size(18.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DirectoryPickerSheet(
    projects: List<String>,
    current: String?,
    onDismiss: () -> Unit,
    onSelect: (String?) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var showCustom by remember { mutableStateOf(false) }
    var custom by remember { mutableStateOf(current ?: "") }

    val filtered = remember(projects, query) {
        if (query.isBlank()) projects
        else projects.filter { it.contains(query, ignoreCase = true) }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
            Column(Modifier.padding(horizontal = 20.dp)) {
                Text("Project folder", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(2.dp))
                Text(
                    current?.let { shortenPath(it, keep = 3) } ?: "Server default",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = OcTheme.colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (projects.size > 6) {
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text("Filter folders") },
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                        trailingIcon = {
                            if (query.isNotEmpty()) {
                                IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Close, contentDescription = "Clear") }
                            }
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.height(4.dp))
            }
            LazyColumn(
                Modifier.fillMaxWidth().heightIn(max = 420.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 4.dp),
            ) {
                if (query.isBlank()) {
                    item {
                        DirectoryRow(
                            name = "Server default",
                            subtitle = "Use the server's default folder",
                            selected = current.isNullOrBlank(),
                            onClick = { onSelect(null) },
                        )
                    }
                }
                if (filtered.isEmpty()) {
                    item {
                        Text(
                            if (query.isBlank()) "No folders detected yet" else "No folders match \"$query\"",
                            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
                            color = OcTheme.colors.textSecondary,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                items(filtered, key = { it }) { path ->
                    DirectoryRow(
                        name = folderName(path),
                        subtitle = shortenPath(path, keep = 3),
                        selected = path == current,
                        onClick = { onSelect(path) },
                    )
                }
            }
            HorizontalDivider()
            if (showCustom) {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                    OutlinedTextField(
                        value = custom,
                        onValueChange = { custom = it },
                        label = { Text("Absolute path") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            enabled = custom.isNotBlank(),
                            onClick = { onSelect(custom.trim()) },
                        ) { Text("Use this path") }
                        TextButton(onClick = { showCustom = false }) { Text("Cancel") }
                    }
                }
            } else {
                TextButton(
                    onClick = {
                        custom = current ?: ""
                        showCustom = true
                    },
                    modifier = Modifier.padding(horizontal = 8.dp),
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Use a custom path")
                }
            }
        }
    }
}

@Composable
private fun DirectoryRow(
    name: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.Folder,
            contentDescription = null,
            tint = if (selected) MaterialTheme.colorScheme.primary else OcTheme.colors.textSecondary,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                name,
                style = MaterialTheme.typography.bodyMedium,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = OcTheme.colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (selected) {
            Spacer(Modifier.width(12.dp))
            Icon(Icons.Filled.Check, contentDescription = "Selected", tint = MaterialTheme.colorScheme.primary)
        }
    }
}

private fun folderName(path: String?): String {
    if (path.isNullOrBlank()) return "Server default"
    val trimmed = path.trimEnd('/')
    return trimmed.substringAfterLast('/').ifEmpty { trimmed }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BranchSheet(
    current: String?,
    branches: List<String>,
    loading: Boolean,
    running: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onCheckout: (String) -> Unit,
    onCreate: (String) -> Unit,
) {
    var newName by remember { mutableStateOf("") }
    val valid = GitRunner.isValidBranch(newName.trim())

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
            Column(Modifier.padding(horizontal = 20.dp)) {
                Text("Branch", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.AccountTree,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        current ?: "—",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = OcTheme.colors.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        placeholder = { Text("New branch name") },
                        singleLine = true,
                        enabled = !running,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(
                        enabled = valid && !running,
                        onClick = {
                            val name = newName.trim()
                            newName = ""
                            onCreate(name)
                        },
                    ) { Text("Create") }
                }
                if (error != null && error.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(error, color = OcTheme.colors.red, style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(8.dp))
            }
            if (loading) {
                androidx.compose.material3.CircularProgressIndicator(Modifier.padding(20.dp))
            }
            LazyColumn(
                Modifier.fillMaxWidth().heightIn(max = 420.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 4.dp),
            ) {
                if (!loading && branches.isEmpty()) {
                    item {
                        Text(
                            "No branches found",
                            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
                            color = OcTheme.colors.textSecondary,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                items(branches, key = { it }) { name ->
                    val isCurrent = name == current
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !running && !isCurrent) { onCheckout(name) }
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            if (isCurrent) Icons.Filled.Check else Icons.Filled.AccountTree,
                            contentDescription = null,
                            tint = if (isCurrent) MaterialTheme.colorScheme.primary else OcTheme.colors.textSecondary,
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            name,
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = FontFamily.Monospace,
                            color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionCard(
    modifier: Modifier = Modifier,
    session: Session,
    /** Only when the session lives in another folder than the one shown (the list is per folder). */
    showPath: Boolean = false,
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
    val c = OcTheme.colors
    var menu by remember { mutableStateOf(false) }
    Box(modifier) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(dev.opencode.mobile.ui.common.Ui.cardShape)
                .background(c.surface)
                .border(1.dp, c.border, dev.opencode.mobile.ui.common.Ui.cardShape)
                .combinedClickable(onClick = onClick, onLongClick = { menu = true })
                .padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 10.dp),
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    session.title.ifEmpty { "Untitled session" },
                    style = MaterialTheme.typography.titleSmall.copy(fontSize = 15.sp, lineHeight = 20.sp),
                    color = c.textPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(12.dp))
                Text(relativeTime(session.time?.updated ?: session.time?.created), fontSize = 12.sp, color = c.textSecondary, modifier = Modifier.padding(top = 2.dp))
            }
            if (showPath) MutedLabel(shortenPath(session.directory))
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                when (status?.type) {
                    "busy" -> { dev.opencode.mobile.ui.common.StatusPill("Working", c.orange); Spacer(Modifier.width(8.dp)) }
                    "retry" -> { dev.opencode.mobile.ui.common.StatusPill("Retrying", c.red); Spacer(Modifier.width(8.dp)) }
                }
                if (!session.share?.url.isNullOrBlank()) {
                    dev.opencode.mobile.ui.common.StatusPill("Shared", c.purple, dot = false)
                    Spacer(Modifier.width(8.dp))
                }
                val summary = session.summary
                val meta = buildList {
                    session.tokens?.let { t -> if (t.input + t.output > 0) add(compactCount(t.input + t.output) + " tok") }
                    session.cost?.let { cost -> if (cost > 0) add("$" + if (cost >= 0.01) "%.2f".format(cost) else "%.4f".format(cost)) }
                }
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    if (summary != null && (summary.additions > 0 || summary.deletions > 0)) {
                        Text("+${summary.additions}", color = c.green, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Text(" −${summary.deletions}", color = c.red, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        if (meta.isNotEmpty()) Text("  ·  ", color = c.textSecondary, fontSize = 12.sp)
                    }
                    Text(meta.joinToString("  ·  "), color = c.textSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(
                    "Diff",
                    color = c.link,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onDiff).padding(horizontal = 8.dp, vertical = 6.dp),
                )
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

/** 11,563 → "11.6k", 2,400,000 → "2.4M" (a session card has no room for exact counts). */
private fun compactCount(n: Long): String = when {
    n >= 1_000_000 -> "%.1fM".format(n / 1_000_000.0)
    n >= 10_000 -> "%.0fk".format(n / 1_000.0)
    n >= 1_000 -> "%.1fk".format(n / 1_000.0)
    else -> n.toString()
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