package dev.opencode.mobile.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.opencode.mobile.AppViewModel
import dev.opencode.mobile.OpenCodeApp
import dev.opencode.mobile.data.model.MessageData
import dev.opencode.mobile.data.model.Part
import dev.opencode.mobile.ui.common.JsonUtil
import dev.opencode.mobile.ui.common.MarkdownText
import dev.opencode.mobile.ui.common.MutedLabel
import dev.opencode.mobile.ui.theme.Border
import dev.opencode.mobile.ui.theme.Green
import dev.opencode.mobile.ui.theme.Red
import dev.opencode.mobile.ui.theme.RedBg
import dev.opencode.mobile.ui.theme.SurfaceVariant
import dev.opencode.mobile.ui.theme.TextSecondary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    appVm: AppViewModel,
    serverId: String,
    sessionId: String,
    onBack: () -> Unit,
    onDiff: () -> Unit,
    onOpenFile: (String) -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as OpenCodeApp

    val servers by appVm.servers.collectAsState()
    val actualServer = remember(servers, serverId) { servers.firstOrNull { it.id == serverId } }

    if (actualServer == null) {
        Column(Modifier.fillMaxSize().padding(24.dp)) {
            Text("Server not found.", color = TextSecondary)
            TextButton(onClick = onBack) { Text("Go back") }
        }
        return
    }

    val vm: ChatViewModel = viewModel(
        key = "chat_${serverId}_$sessionId",
        factory = viewModelFactory {
            initializer {
                ChatViewModel(app, actualServer, sessionId, projectDir = { appVm.currentDirectory.value })
            }
        },
    )
    val ui by vm.ui.collectAsState()
    val input by vm.input.collectAsState()

    Scaffold(
        modifier = Modifier.fillMaxSize().imePadding(),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            ui.session?.title?.ifEmpty { "Session" } ?: "Session",
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val dir = ui.session?.directory
                        if (!dir.isNullOrEmpty()) {
                            Text(dir, style = MaterialTheme.typography.labelSmall, color = TextSecondary, maxLines = 1)
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    if (ui.busy) {
                        IconButton(onClick = { vm.abort() }) {
                            Icon(Icons.Filled.Block, contentDescription = "Abort", tint = Red)
                        }
                    }
                    TextButton(onClick = onDiff) { Text("Diff") }
                },
            )
        },
        bottomBar = {
            InputBar(
                value = input,
                onValueChange = { vm.onInputChange(it) },
                onSend = { vm.send() },
                busy = ui.busy,
                enabled = !ui.busy,
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (ui.error != null) {
                Surface(color = RedBg, modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                    Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        SelectionContainer(modifier = Modifier.weight(1f)) {
                            Text(ui.error!!, color = Red, style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = { vm.dismissError() }) { Text("Dismiss") }
                    }
                }
            }
            if (ui.loading && ui.messages.isEmpty()) {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            MessageList(
                messages = ui.messages,
                onOpenFile = onOpenFile,
            )
        }
    }
}

@Composable
private fun InputBar(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    busy: Boolean,
    enabled: Boolean,
) {
    Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 8.dp, modifier = Modifier.navigationBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                placeholder = { Text(if (busy) "opencode is working…" else "Message opencode") },
                modifier = Modifier.weight(1f),
                maxLines = 6,
            )
            Spacer(Modifier.width(6.dp))
            IconButton(
                onClick = onSend,
                enabled = enabled && value.isNotBlank(),
                modifier = Modifier.padding(bottom = 4.dp),
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
            }
        }
    }
}

@Composable
private fun MessageList(
    messages: List<MessageData>,
    onOpenFile: (String) -> Unit,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(messages.size, messages.lastOrNull()?.parts?.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }
    SelectionContainer {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(messages, key = { it.info.id }) { message ->
                MessageRow(message = message, onOpenFile = onOpenFile)
            }
        }
    }
}

@Composable
private fun MessageRow(
    message: MessageData,
    onOpenFile: (String) -> Unit,
) {
    val isUser = message.info.role == "user"
    if (isUser) {
        val text = message.parts.joinToString("\n") { it.text }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.fillMaxWidth(0.85f),
            ) {
                MarkdownText(markdown = text.ifEmpty { "…" }, modifier = Modifier.padding(12.dp), bodySize = 15.sp)
            }
        }
    } else {
        Column(Modifier.fillMaxWidth()) {
            if (message.info.error != null) {
                Surface(color = RedBg, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(10.dp)) {
                        Text("Message failed", color = Red, style = MaterialTheme.typography.labelMedium)
                        Text(
                            message.info.error.toString(),
                            color = Red,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 6,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            val parts = message.parts
            val modelLabel = listOfNotNull(message.info.providerID, message.info.modelID)
                .joinToString("/")
                .ifEmpty { null }
            for ((index, part) in parts.withIndex()) {
                PartView(part = part, isFirst = index == 0, modelLabel = modelLabel, onOpenFile = onOpenFile)
            }
            val tokens = message.info.tokens
            if (tokens != null) {
                MutedLabel(
                    "in ${tokens.input} · out ${tokens.output}" +
                        (if (tokens.reasoning > 0) " · reasoning ${tokens.reasoning}" else ""),
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun PartView(
    part: Part,
    isFirst: Boolean,
    modelLabel: String?,
    onOpenFile: (String) -> Unit,
) {
    when (part.type) {
        "text" -> {
            if (isFirst) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 4.dp)) {
                    Text(
                        part.agent.ifEmpty { "opencode" },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    if (modelLabel != null) {
                        Spacer(Modifier.width(8.dp))
                        MutedLabel(modelLabel)
                    }
                }
            }
            MarkdownText(markdown = part.text, modifier = Modifier.padding(bottom = 6.dp))
        }
        "reasoning" -> {
            var expanded by rememberSaveable(part.id) { mutableStateOf(false) }
            Surface(
                color = SurfaceVariant,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
            ) {
                Column(Modifier.clickable { expanded = !expanded }.padding(10.dp)) {
                    Text(
                        if (expanded) "▾ reasoning" else "▸ reasoning",
                        style = MaterialTheme.typography.labelMedium,
                        color = TextSecondary,
                    )
                    if (expanded) {
                        Text(
                            part.text,
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
        }
        "tool" -> ToolCard(part)
        "step-start" -> {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                Box(Modifier.width(24.dp).height(1.dp).background(Border))
                Text(" step ", color = TextSecondary, style = MaterialTheme.typography.labelSmall)
                Box(Modifier.weight(1f).height(1.dp).background(Border))
            }
        }
        "step-finish" -> {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                Box(Modifier.width(24.dp).height(1.dp).background(Border))
                Text(
                    " step finished (${part.reason.ifEmpty { "done" }}) ",
                    color = TextSecondary,
                    style = MaterialTheme.typography.labelSmall,
                )
                Box(Modifier.weight(1f).height(1.dp).background(Border))
            }
        }
        "snapshot" -> MutedLabel("snapshot ${part.snapshot}", modifier = Modifier.padding(vertical = 2.dp))
        "patch" -> MutedLabel("patch: ${part.files.size} file(s)", modifier = Modifier.padding(vertical = 2.dp))
        "compaction" -> MutedLabel("context compacted", modifier = Modifier.padding(vertical = 2.dp))
        "agent" -> {
            Text(
                "⇄ subagent ${part.name}",
                color = MaterialTheme.colorScheme.tertiary,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(vertical = 2.dp),
            )
        }
        "subtask" -> MutedLabel("subtask: ${part.description.ifEmpty { part.prompt }}", modifier = Modifier.padding(vertical = 2.dp))
        "file" -> {
            val sourcePath = part.source?.get("path")?.let { it.toString().trim('"') }
            val display = part.filename ?: sourcePath ?: part.url
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp).clickable {
                    sourcePath?.let { onOpenFile(it) }
                },
            ) {
                Icon(Icons.Filled.Description, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.width(16.dp).height(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    display,
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        "retry" -> MutedLabel("retry attempt ${part.attempt}", modifier = Modifier.padding(vertical = 2.dp))
    }
}

@Composable
private fun ToolCard(part: Part) {
    val state = part.state ?: return
    var expandedInput by rememberSaveable(part.id) { mutableStateOf(false) }
    var expandedOutput by rememberSaveable(part.id) { mutableStateOf(false) }
    val statusColor = when (state.status) {
        "completed" -> Green
        "error" -> Red
        else -> MaterialTheme.colorScheme.primary
    }
    Surface(
        color = SurfaceVariant,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
    ) {
        Column(Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(part.tool, style = MaterialTheme.typography.labelLarge, color = statusColor)
                Spacer(Modifier.width(8.dp))
                Text(state.status, style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                Spacer(Modifier.weight(1f))
                if (state.status == "running" || state.status == "pending") {
                    CircularProgressIndicator(modifier = Modifier.width(12.dp).height(12.dp), strokeWidth = 2.dp)
                }
            }
            if (state.title.isNotBlank()) {
                Text(state.title, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
            }
            val inputText = JsonUtil.pretty(state.input)
            if (inputText.isNotBlank()) {
                Row(Modifier.clickable { expandedInput = !expandedInput }.padding(top = 6.dp)) {
                    Text(
                        if (expandedInput) "▾ input" else "▸ input",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary,
                    )
                }
                if (expandedInput) {
                    Text(
                        inputText,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 12,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            val output = state.output
            if (output.isNotBlank()) {
                Row(Modifier.clickable { expandedOutput = !expandedOutput }.padding(top = 6.dp)) {
                    Text(
                        if (expandedOutput) "▾ output" else "▸ output",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary,
                    )
                }
                if (expandedOutput) {
                    Text(
                        output.take(8000),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            if (state.status == "error" && state.error.isNotBlank()) {
                Text(state.error, color = Red, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
}