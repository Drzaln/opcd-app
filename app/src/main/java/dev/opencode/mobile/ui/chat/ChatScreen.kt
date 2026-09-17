package dev.opencode.mobile.ui.chat

import dev.opencode.mobile.ui.theme.OcTheme

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.net.Uri
import android.widget.Toast
import kotlin.math.roundToInt
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.opencode.mobile.AppViewModel
import dev.opencode.mobile.OpenCodeApp
import dev.opencode.mobile.data.model.Agent
import dev.opencode.mobile.data.model.Command
import dev.opencode.mobile.data.model.MessageData
import dev.opencode.mobile.data.model.Part
import dev.opencode.mobile.data.model.Todo
import dev.opencode.mobile.ui.common.JsonUtil
import dev.opencode.mobile.ui.common.MarkdownText
import dev.opencode.mobile.ui.common.MutedLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    appVm: AppViewModel,
    serverId: String,
    sessionId: String,
    onBack: () -> Unit,
    onDiff: () -> Unit,
    onMessageDiff: (String) -> Unit,
    onOpenFile: (String) -> Unit,
    onOpenSession: (String) -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as OpenCodeApp

    val servers by appVm.servers.collectAsState()
    val actualServer = remember(servers, serverId) { servers.firstOrNull { it.id == serverId } }

    if (actualServer == null) {
        Column(Modifier.fillMaxSize().padding(24.dp)) {
            Text("Server not found.", color = OcTheme.colors.textSecondary)
            TextButton(onClick = onBack) { Text("Go back") }
        }
        return
    }

    var foreground by remember { mutableStateOf(true) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { foreground = true }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { foreground = false }
    var showSummarize by remember { mutableStateOf(false) }

    val vm: ChatViewModel = viewModel(
        key = "chat_${serverId}_$sessionId",
        factory = viewModelFactory {
            initializer {
                ChatViewModel(
                    app,
                    actualServer,
                    sessionId,
                    projectDir = { appVm.currentDirectory.value },
                    isForeground = { foreground },
                )
            }
        },
    )
    val ui by vm.ui.collectAsState()
    val input by vm.input.collectAsState()
    val attachments by vm.attachments.collectAsState()

    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { vm.addAttachment(it.toString()) }
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { vm.addAttachment(it.toString()) }
    }

    val contextStatus = remember(ui.messages, ui.models, ui.session) {
        val last = ui.messages.lastOrNull {
            it.info.role == "assistant" && (it.info.tokens?.output ?: 0) > 0
        }?.info
        val t = last?.tokens
        val tokens = if (t != null) t.input + t.output + t.reasoning + t.cache.read + t.cache.write else 0L
        val limit = ui.models.firstOrNull {
            it.providerId == last?.providerID && it.modelId == last?.modelID
        }?.contextLimit ?: 0L
        val percent = if (limit > 0) ((tokens.toDouble() / limit) * 100).roundToInt() else null
        Triple(tokens, percent, ui.session?.cost)
    }

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
                            Text(dir, style = MaterialTheme.typography.labelSmall, color = OcTheme.colors.textSecondary, maxLines = 1)
                        }
                        val (ctxTokens, ctxPercent, sessionCost) = contextStatus
                        val bits = mutableListOf<String>()
                        if (ctxTokens > 0) bits.add("${"%,d".format(ctxTokens)} tokens")
                        if (ctxPercent != null) bits.add("$ctxPercent% used")
                        if (sessionCost != null && sessionCost > 0) {
                            bits.add("$" + if (sessionCost >= 0.01) "%.2f".format(sessionCost) else "%.4f".format(sessionCost) + " spent")
                        }
                        if (bits.isNotEmpty()) {
                            val color = when {
                                (ctxPercent ?: 0) >= 90 -> OcTheme.colors.red
                                (ctxPercent ?: 0) >= 70 -> OcTheme.colors.orange
                                else -> OcTheme.colors.textSecondary
                            }
                            Text(bits.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = color, maxLines = 1)
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    if (ui.session?.revert != null) {
                        TextButton(onClick = { vm.unrevert() }) { Text("Undo revert") }
                    }
                    if (ui.busy) {
                        IconButton(onClick = { vm.abort() }) {
                            Icon(Icons.Filled.Block, contentDescription = "Abort", tint = OcTheme.colors.red)
                        }
                    }
                    IconButton(onClick = { showSummarize = true }) {
                        Icon(Icons.Filled.Compress, contentDescription = "Summarize")
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
                enabled = true,
                queued = ui.queued,
                onCancelQueued = { vm.cancelQueued() },
                commands = ui.commands,
                agents = ui.agents,
                models = ui.models,
                selectedAgent = ui.selectedAgent,
                selectedModel = ui.selectedModel,
                onSelectAgent = { vm.selectAgent(it) },
                onSelectModel = { vm.selectModel(it) },
                attachments = attachments,
                onRemoveAttachment = { vm.removeAttachment(it) },
                onPickPhoto = { photoPicker.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                onPickFile = { filePicker.launch(arrayOf("*/*")) },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (ui.error != null) {
                Surface(color = OcTheme.colors.redBg, modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                    Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        SelectionContainer(modifier = Modifier.weight(1f)) {
                            Text(ui.error!!, color = OcTheme.colors.red, style = MaterialTheme.typography.bodySmall)
                        }
                        if (input.isNotBlank()) {
                            TextButton(onClick = { vm.dismissError(); vm.send() }) { Text("Retry") }
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
            TodosPanel(todos = ui.todos)
            MessageList(
                messages = ui.messages,
                models = ui.models,
                busy = ui.busy,
                loadingOlder = ui.loadingOlder,
                hasMore = ui.hasMore,
                onLoadOlder = { vm.loadOlder() },
                onRefresh = { vm.refresh() },
                onOpenFile = onOpenFile,
                onMessageDiff = onMessageDiff,
                onFork = { messageId -> vm.forkFrom(messageId, onOpenSession) },
                onRevert = { messageId -> vm.revertTo(messageId) },
                onDelete = { messageId -> vm.deleteMessage(messageId) },
            )
        }
    }
    if (showSummarize) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showSummarize = false },
            title = { Text("Summarize session?") },
            text = { Text("This compacts the conversation context using the selected model. Older turns are replaced by a summary.") },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { showSummarize = false; vm.summarize() }) { Text("Summarize") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showSummarize = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun InputBar(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    busy: Boolean,
    enabled: Boolean,
    queued: Int,
    onCancelQueued: () -> Unit,
    commands: List<Command>,
    agents: List<Agent>,
    models: List<ModelOption>,
    selectedAgent: String?,
    selectedModel: ModelOption?,
    onSelectAgent: (String?) -> Unit,
    onSelectModel: (ModelOption?) -> Unit,
    attachments: List<Attachment>,
    onRemoveAttachment: (String) -> Unit,
    onPickPhoto: () -> Unit,
    onPickFile: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 8.dp, modifier = Modifier.navigationBarsPadding()) {
        Column {
            if (attachments.isNotEmpty()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    for (attachment in attachments) {
                        Surface(color = OcTheme.colors.surfaceVariant, shape = RoundedCornerShape(8.dp)) {
                            Row(
                                Modifier.padding(start = 8.dp, end = 2.dp, top = 4.dp, bottom = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    attachment.name,
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.width(120.dp),
                                )
                                IconButton(onClick = { onRemoveAttachment(attachment.uri) }, modifier = Modifier.size(24.dp)) {
                                    Icon(Icons.Filled.Close, contentDescription = "Remove attachment", modifier = Modifier.size(14.dp))
                                }
                            }
                        }
                    }
                }
            }
            if (agents.isNotEmpty() || models.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (agents.isNotEmpty()) {
                        SelectionChip(
                            label = "Agent",
                            options = agents,
                            optionLabel = { it.name },
                            selected = agents.firstOrNull { it.name == selectedAgent },
                            onSelect = { agent -> onSelectAgent(agent?.name) },
                        )
                    }
                    if (models.isNotEmpty()) {
                        SelectionChip(
                            label = "Model",
                            options = models,
                            optionLabel = { it.label },
                            selected = selectedModel,
                            onSelect = onSelectModel,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    Text("·", color = OcTheme.colors.textSecondary)
                }
            }
            if (queued > 0) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MutedLabel("$queued message(s) queued")
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onCancelQueued) { Text("Cancel queued", color = OcTheme.colors.red) }
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                OutlinedTextField(
                    value = value,
                    onValueChange = onValueChange,
                    placeholder = { Text(if (busy) "opencode is working…" + (if (queued > 0) " ($queued queued)" else "") else "Message opencode") },
                    modifier = Modifier.weight(1f),
                    maxLines = 6,
                )
                Spacer(Modifier.width(6.dp))
                if (commands.isNotEmpty()) {
                    CommandMenu(commands = commands, onPick = { cmd ->
                        onValueChange("/${cmd} ")
                    })
                }
                AttachMenu(
                    enabled = enabled,
                    onPickPhoto = onPickPhoto,
                    onPickFile = onPickFile,
                )
                IconButton(
                    onClick = onSend,
                    enabled = enabled && (value.isNotBlank() || attachments.isNotEmpty()),
                    modifier = Modifier.padding(bottom = 4.dp),
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                }
            }
        }
    }
}

@Composable
private fun AttachMenu(
    enabled: Boolean,
    onPickPhoto: () -> Unit,
    onPickFile: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }, enabled = enabled, modifier = Modifier.padding(bottom = 4.dp)) {
            Icon(Icons.Filled.AttachFile, contentDescription = "Attach")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("Photo") }, onClick = { expanded = false; onPickPhoto() })
            DropdownMenuItem(text = { Text("File") }, onClick = { expanded = false; onPickFile() })
        }
    }
}

@Composable
private fun CommandMenu(commands: List<Command>, onPick: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Text("/", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            for (cmd in commands) {
                DropdownMenuItem(
                    text = {
                        Column {
                            Text("/${cmd.name}", style = MaterialTheme.typography.bodyMedium)
                            if (!cmd.description.isNullOrBlank()) {
                                Text(
                                    cmd.description!!,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = OcTheme.colors.textSecondary,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    },
                    onClick = { onPick(cmd.name); expanded = false },
                )
            }
        }
    }
}

@Composable
private fun <T> SelectionChip(
    label: String,
    options: List<T>,
    optionLabel: (T) -> String,
    selected: T?,
    onSelect: (T?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }) {
            Text(
                selected?.let(optionLabel) ?: label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelMedium,
            )
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(label) },
                onClick = { onSelect(null); expanded = false },
                leadingIcon = {
                    if (selected == null) Icon(Icons.Filled.Check, contentDescription = "selected")
                },
            )
            for (option in options) {
                DropdownMenuItem(
                    text = { Text(optionLabel(option), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    onClick = { onSelect(option); expanded = false },
                    leadingIcon = {
                        if (option == selected) {
                            Icon(Icons.Filled.Check, contentDescription = "selected")
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun TodosPanel(todos: List<Todo>) {
    if (todos.isEmpty()) return
    var expanded by rememberSaveable { mutableStateOf(false) }
    Surface(
        color = OcTheme.colors.surfaceVariant,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Column(Modifier.padding(10.dp)) {
            Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }, verticalAlignment = Alignment.CenterVertically) {
                Text(if (expanded) "▾ Todos" else "▸ Todos", style = MaterialTheme.typography.labelMedium, color = OcTheme.colors.textSecondary)
                Spacer(Modifier.width(8.dp))
                val done = todos.count { it.status == "completed" }
                MutedLabel("$done/${todos.size}")
                Spacer(Modifier.weight(1f))
                Text(if (expanded) "collapse" else "expand", style = MaterialTheme.typography.labelSmall, color = OcTheme.colors.textSecondary)
            }
            androidx.compose.animation.AnimatedVisibility(
                visible = expanded,
                enter = androidx.compose.animation.expandVertically(),
                exit = androidx.compose.animation.shrinkVertically(),
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 220.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(top = 4.dp),
                ) {
                    todos.forEach { todo ->
                        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            val color = when (todo.status) {
                                "completed" -> OcTheme.colors.green
                                "in_progress" -> MaterialTheme.colorScheme.primary
                                "cancelled" -> OcTheme.colors.red
                                else -> OcTheme.colors.textSecondary
                            }
                            Text(
                                when (todo.status) {
                                    "completed" -> "☑"
                                    "in_progress" -> "◐"
                                    "cancelled" -> "✕"
                                    else -> "○"
                                },
                                color = color,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                todo.content,
                                style = MaterialTheme.typography.bodySmall,
                                textDecoration = if (todo.status == "completed") TextDecoration.LineThrough else null,
                                color = if (todo.status == "cancelled") OcTheme.colors.textSecondary else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MessageList(
    messages: List<MessageData>,
    models: List<ModelOption>,
    busy: Boolean,
    loadingOlder: Boolean,
    hasMore: Boolean,
    onLoadOlder: () -> Unit,
    onRefresh: () -> Unit,
    onOpenFile: (String) -> Unit,
    onMessageDiff: (String) -> Unit,
    onFork: (String) -> Unit,
    onRevert: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }
    val atBottom by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= listState.layoutInfo.totalItemsCount - 1
        }
    }
    // Only follow new messages appended at the end; loading older ones must not yank the viewport.
    LaunchedEffect(messages.lastOrNull()?.info?.id, messages.lastOrNull()?.parts?.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }
    LaunchedEffect(listState, hasMore, loadingOlder) {
        if (!hasMore || loadingOlder) return@LaunchedEffect
        snapshotFlow { listState.firstVisibleItemIndex }
            .distinctUntilChanged()
            .collect { if (it <= 1) onLoadOlder() }
    }
    Box(Modifier.fillMaxSize()) {
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = {
                refreshing = true
                onRefresh()
            },
            modifier = Modifier.fillMaxSize(),
        ) {
            SelectionContainer {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (hasMore || loadingOlder) {
                        item(key = "load-older") {
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (loadingOlder) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                } else {
                                    TextButton(onClick = onLoadOlder) { Text("Load older messages") }
                                }
                            }
                        }
                    }
                    items(messages, key = { it.info.id }) { message ->
                        MessageRow(
                            message = message,
                            models = models,
                            modifier = Modifier.animateItem(),
                            onOpenFile = onOpenFile,
                            onMessageDiff = onMessageDiff,
                            onFork = { onFork(message.info.id) },
                            onRevert = { onRevert(message.info.id) },
                            onDelete = { onDelete(message.info.id) },
                        )
                    }
                    if (busy) {
                        item(key = "streaming") {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                                Text("opencode is working…", color = OcTheme.colors.textSecondary, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        }
        if (!atBottom && messages.isNotEmpty()) {
            FloatingActionButton(
                onClick = { scope.launch { listState.animateScrollToItem(messages.lastIndex) } },
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                containerColor = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Scroll to bottom")
            }
        }
    }
    // Clear the pull-to-refresh indicator once the refresh round-trip settles.
    LaunchedEffect(messages, loadingOlder) { refreshing = false }
}

private fun resolveModelLabel(info: dev.opencode.mobile.data.model.Message, models: List<ModelOption>): String? {
    if (info.providerID.isNullOrBlank() && info.modelID.isNullOrBlank()) return null
    val resolved = models.firstOrNull { it.providerId == info.providerID && it.modelId == info.modelID }
    return resolved?.label ?: (info.modelID?.ifBlank { info.providerID } ?: info.providerID)
}

@Composable
private fun MessageRow(
    message: MessageData,
    models: List<ModelOption>,
    onOpenFile: (String) -> Unit,
    onMessageDiff: (String) -> Unit,
    onFork: () -> Unit,
    onRevert: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val fullText = message.parts.joinToString("\n") { it.text }.trim()
    var menu by remember { mutableStateOf(false) }

    fun copy() {
        if (fullText.isNotBlank()) {
            clipboard.setText(AnnotatedString(fullText))
            Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
        }
    }

    val isUser = message.info.role == "user"
    Box(modifier.fillMaxWidth()) {
    if (isUser) {
        val text = message.parts.joinToString("\n") { it.text }
        Column(
            Modifier.fillMaxWidth().combinedClickable(onClick = {}, onLongClick = { menu = true }),
            horizontalAlignment = Alignment.End,
        ) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.fillMaxWidth(0.85f),
            ) {
                MarkdownText(markdown = text.ifEmpty { "…" }, modifier = Modifier.padding(12.dp), bodySize = 15.sp)
            }
            Text(
                "± diff",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 2.dp).clickable { onMessageDiff(message.info.id) },
            )
        }
    } else {
        Column(
            Modifier.fillMaxWidth().combinedClickable(onClick = {}, onLongClick = { menu = true }),
        ) {
            if (message.info.error != null) {
                Surface(color = OcTheme.colors.redBg, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(10.dp)) {
                        Text("Message failed", color = OcTheme.colors.red, style = MaterialTheme.typography.labelMedium)
                        Text(
                            message.info.error.toString(),
                            color = OcTheme.colors.red,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 6,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            val parts = message.parts
            val agentName = parts.firstOrNull { it.agent.isNotBlank() }?.agent ?: "opencode"
            val modelLabel = resolveModelLabel(message.info, models)
            if (parts.isNotEmpty() || modelLabel != null) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 4.dp)) {
                    Text(agentName, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    if (modelLabel != null) {
                        Spacer(Modifier.width(8.dp))
                        MutedLabel(modelLabel)
                    }
                }
            }
            for (part in parts) {
                PartView(part = part, onOpenFile = onOpenFile)
            }
            val tokens = message.info.tokens
            val cost = message.info.cost
            if (tokens != null || cost != null) {
                val bits = mutableListOf<String>()
                if (tokens != null) {
                    bits.add("in ${"%,d".format(tokens.input)} · out ${"%,d".format(tokens.output)}")
                    val limit = models.firstOrNull {
                        it.providerId == message.info.providerID && it.modelId == message.info.modelID
                    }?.contextLimit ?: 0L
                    if (limit > 0) {
                        val used = tokens.input + tokens.output + tokens.reasoning + tokens.cache.read + tokens.cache.write
                        val pct = used * 100.0 / limit
                        bits.add("ctx ${"%.0f".format(pct)}%")
                    }
                }
                if (cost != null && cost > 0) {
                    bits.add("$" + if (cost >= 0.01) "%.2f".format(cost) else "%.4f".format(cost))
                }
                MutedLabel(bits.joinToString(" · "), modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
        DropdownMenuItem(text = { Text("Copy") }, onClick = { menu = false; copy() })
        if (isUser) {
            DropdownMenuItem(text = { Text("Fork from here") }, onClick = { menu = false; onFork() })
            DropdownMenuItem(text = { Text("Revert to here") }, onClick = { menu = false; onRevert() })
            DropdownMenuItem(
                text = { Text("± Diff this message") },
                onClick = { menu = false; onMessageDiff(message.info.id) },
            )
        }
        DropdownMenuItem(
            text = { Text("Delete message", color = OcTheme.colors.red) },
            onClick = { menu = false; onDelete() },
        )
    }
    }
}

@Composable
private fun PartView(
    part: Part,
    onOpenFile: (String) -> Unit,
) {
    when (part.type) {
        "text" -> {
            MarkdownText(markdown = part.text, modifier = Modifier.padding(bottom = 6.dp))
        }
        "reasoning" -> {
            var expanded by rememberSaveable(part.id) { mutableStateOf(false) }
            Surface(
                color = OcTheme.colors.surfaceVariant,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
            ) {
                Column(Modifier.clickable { expanded = !expanded }.padding(10.dp)) {
                    Text(
                        if (expanded) "▾ reasoning" else "▸ reasoning",
                        style = MaterialTheme.typography.labelMedium,
                        color = OcTheme.colors.textSecondary,
                    )
                    if (expanded) {
                        Text(
                            part.text,
                            style = MaterialTheme.typography.bodySmall,
                            color = OcTheme.colors.textSecondary,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
        }
        "tool" -> ToolCard(part)
        "step-start" -> {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                Box(Modifier.width(24.dp).height(1.dp).background(OcTheme.colors.border))
                Text(" step ", color = OcTheme.colors.textSecondary, style = MaterialTheme.typography.labelSmall)
                Box(Modifier.weight(1f).height(1.dp).background(OcTheme.colors.border))
            }
        }
        "step-finish" -> {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                Box(Modifier.width(24.dp).height(1.dp).background(OcTheme.colors.border))
                Text(
                    " step finished (${part.reason.ifEmpty { "done" }}) ",
                    color = OcTheme.colors.textSecondary,
                    style = MaterialTheme.typography.labelSmall,
                )
                Box(Modifier.weight(1f).height(1.dp).background(OcTheme.colors.border))
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
            if (part.mime.startsWith("image/") && part.url.startsWith("data:")) {
                ImagePart(url = part.url, filename = display, sourcePath = sourcePath, onOpenFile = onOpenFile)
            } else {
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
        }
        "retry" -> MutedLabel("retry attempt ${part.attempt}", modifier = Modifier.padding(vertical = 2.dp))
    }
}

@Composable
private fun ImagePart(
    url: String,
    filename: String,
    sourcePath: String?,
    onOpenFile: (String) -> Unit,
) {
    val bitmap by produceState<android.graphics.Bitmap?>(initialValue = null, url) {
        value = withContext(Dispatchers.IO) { decodeDataUrl(url) }
    }
    val image = bitmap
    if (image != null) {
        Image(
            bitmap = image.asImageBitmap(),
            contentDescription = filename,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .clickable { sourcePath?.let(onOpenFile) },
        )
    } else {
        MutedLabel(filename, modifier = Modifier.padding(vertical = 2.dp))
    }
}

private fun decodeDataUrl(url: String): android.graphics.Bitmap? {
    val comma = url.indexOf(',')
    if (comma <= 0) return null
    val bytes = runCatching { Base64.decode(url.substring(comma + 1), Base64.DEFAULT) }.getOrNull() ?: return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1600) sample *= 2
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    return runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) }.getOrNull()
}

@Composable
private fun ToolCard(part: Part) {
    val state = part.state ?: return
    var expandedInput by rememberSaveable(part.id) { mutableStateOf(false) }
    var expandedOutput by rememberSaveable(part.id) { mutableStateOf(false) }
    val statusColor = when (state.status) {
        "completed" -> OcTheme.colors.green
        "error" -> OcTheme.colors.red
        else -> MaterialTheme.colorScheme.primary
    }
    Surface(
        color = OcTheme.colors.surfaceVariant,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
    ) {
        Column(Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(part.tool, style = MaterialTheme.typography.labelLarge, color = statusColor)
                Spacer(Modifier.width(8.dp))
                Text(state.status, style = MaterialTheme.typography.labelSmall, color = OcTheme.colors.textSecondary)
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
                        color = OcTheme.colors.textSecondary,
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
                        color = OcTheme.colors.textSecondary,
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
                Text(state.error, color = OcTheme.colors.red, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
}