package dev.opencode.mobile.ui.sessions

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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import dev.opencode.mobile.ui.theme.Green
import dev.opencode.mobile.ui.theme.Orange
import dev.opencode.mobile.ui.theme.Red
import dev.opencode.mobile.ui.theme.TextSecondary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SessionsViewModel(
    app: OpenCodeApp,
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
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui

    private val api = app.repository.apiFor(server)

    init {
        refresh()
        refreshProjects()
    }

    fun refresh() {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(loading = true, error = null)
            try {
                val sessions = api.sessions(projectDir())
                    .sortedByDescending { it.time?.updated ?: it.time?.created ?: 0L }
                val statuses = runCatching { api.sessionStatus(projectDir()) }.getOrDefault(emptyMap())
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
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionsScreen(
    appVm: AppViewModel,
    serverId: String,
    onChat: (String) -> Unit,
    onFiles: () -> Unit,
    onDiff: (String) -> Unit,
    onBack: () -> Unit,
) {
    val servers by appVm.servers.collectAsState()
    val server = servers.firstOrNull { it.id == serverId }
    val context = androidx.compose.ui.platform.LocalContext.current
    val app = context.applicationContext as OpenCodeApp

    if (server == null) {
        Column(Modifier.fillMaxSize().padding(24.dp)) {
            Text("Server not found.", color = TextSecondary)
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

    LaunchedEffect(projectDir) {
        vm.refresh()
        vm.refreshProjects()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(server.name) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    IconButton(onClick = onFiles) { Icon(Icons.Filled.Folder, contentDescription = "Browse files") }
                    IconButton(onClick = { vm.refresh() }) { Icon(Icons.Filled.Refresh, contentDescription = "Refresh") }
                    IconButton(onClick = { vm.createSession() }) { Icon(Icons.Filled.Add, contentDescription = "New session") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            DirectoryBar(
                directory = projectDir,
                onChange = { showDirPicker = true },
            )
            if (ui.error != null) {
                Text(ui.error!!, Modifier.padding(16.dp), color = Red)
            }
            if (ui.loading && ui.sessions.isEmpty()) {
                androidx.compose.material3.CircularProgressIndicator(Modifier.padding(24.dp))
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(ui.sessions, key = { it.id }) { session ->
                    SessionCard(
                        session = session,
                        status = ui.statuses[session.id],
                        onClick = { onChat(session.id) },
                        onDiff = { onDiff(session.id) },
                        onEditTitle = { editTarget = session },
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
                    color = TextSecondary,
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
    session: Session,
    status: SessionStatus?,
    onClick: () -> Unit,
    onDiff: () -> Unit,
    onEditTitle: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onEditTitle),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    session.title.ifEmpty { "Untitled session" },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    modifier = Modifier.weight(1f),
                )
                status?.let {
                    val color = when (it.type) {
                        "busy" -> Orange
                        "retry" -> Red
                        else -> Green
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
                MutedLabel(formatTime(session.time?.updated ?: session.time?.created))
                Spacer(Modifier.width(12.dp))
                val summary = session.summary
                if (summary != null && (summary.additions > 0 || summary.deletions > 0)) {
                    Text("+${summary.additions} -${summary.deletions}", color = Green, style = MaterialTheme.typography.labelSmall)
                    Spacer(Modifier.width(8.dp))
                    Text("${summary.files} files", color = TextSecondary, style = MaterialTheme.typography.labelSmall)
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
}

private fun formatTime(epochMillis: Long?): String {
    if (epochMillis == null) return ""
    return try {
        SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(epochMillis))
    } catch (e: Exception) {
        ""
    }
}