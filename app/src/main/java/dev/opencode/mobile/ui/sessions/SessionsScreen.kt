package dev.opencode.mobile.ui.sessions

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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.opencode.mobile.AppViewModel
import dev.opencode.mobile.OpenCodeApp
import dev.opencode.mobile.data.model.Session
import dev.opencode.mobile.data.net.ServerConfig
import dev.opencode.mobile.ui.common.MutedLabel
import dev.opencode.mobile.ui.theme.Green
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
) : ViewModel() {

    data class UiState(
        val sessions: List<Session> = emptyList(),
        val loading: Boolean = true,
        val error: String? = null,
        val creating: Boolean = false,
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
                val sessions = api.sessions().sortedByDescending { it.time?.updated ?: it.time?.created ?: 0L }
                _ui.value = _ui.value.copy(sessions = sessions, loading = false)
            } catch (e: Exception) {
                _ui.value = _ui.value.copy(loading = false, error = e.message ?: "Failed to load sessions")
            }
        }
    }

    fun createSession() {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(creating = true)
            try {
                api.createSession(dev.opencode.mobile.data.model.CreateSessionBody())
                refresh()
            } catch (e: Exception) {
                _ui.value = _ui.value.copy(creating = false, error = e.message ?: "Failed to create session")
            }
        }
    }

    fun deleteSession(id: String) {
        viewModelScope.launch {
            runCatching { api.deleteSession(id) }
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
        factory = viewModelFactory { initializer { SessionsViewModel(app, server) } },
    )
    val ui by vm.ui.collectAsState()

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
                        onClick = { onChat(session.id) },
                        onDiff = { onDiff(session.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SessionCard(
    session: Session,
    onClick: () -> Unit,
    onDiff: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(12.dp)) {
            Text(
                session.title.ifEmpty { "Untitled session" },
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
            )
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