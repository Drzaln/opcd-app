package dev.opencode.mobile.ui.diff

import dev.opencode.mobile.ui.theme.OcTheme

import androidx.compose.foundation.background
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
                val diffs = api.sessionDiff(sessionId, messageId = messageId, directory = projectDir())
                _ui.value = _ui.value.copy(diffs = diffs, loading = false)
            } catch (e: Exception) {
                _ui.value = _ui.value.copy(loading = false, error = e.message ?: "Failed to load diff")
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
            if (ui.error != null) {
                Text(ui.error!!, color = OcTheme.colors.red, modifier = Modifier.padding(16.dp))
            }
            if (!ui.loading && ui.diffs.isEmpty()) {
                Text(
                    "No changes in this session.",
                    color = OcTheme.colors.textSecondary,
                    modifier = Modifier.padding(24.dp),
                )
            }
            val computed = remember(ui.diffs) {
                ui.diffs.map { diff -> diff to DiffLines.compute(diff.before, diff.after) }
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                for ((diff, lines) in computed) {
                    stickyHeader(key = "header_${diff.file}") {
                        DiffHeader(diff)
                    }
                    items(lines, key = { "${diff.file}_${it.hashCode()}" }) { line ->
                        DiffRow(line.kind, line.oldNo, line.newNo, line.text)
                    }
                }
            }
        }
    }
}

@Composable
private fun DiffHeader(diff: FileDiff) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(OcTheme.colors.surfaceVariant)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            diff.file,
            style = MaterialTheme.typography.titleSmall,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Text("+${diff.additions}", color = OcTheme.colors.green, style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.width(6.dp))
        Text("-${diff.deletions}", color = OcTheme.colors.red, style = MaterialTheme.typography.labelSmall)
    }
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