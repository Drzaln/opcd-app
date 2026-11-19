package dev.opencode.mobile.ui.chat

import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.border
import dev.opencode.mobile.ui.theme.OcTheme

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
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
import androidx.compose.material.icons.filled.MoreVert
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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
import dev.opencode.mobile.ui.common.shareText
import dev.opencode.mobile.ui.common.MarkdownText
import dev.opencode.mobile.ui.common.MutedLabel
import dev.opencode.mobile.ui.common.ConnectionIndicator
import dev.opencode.mobile.ui.common.shortenPath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
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
    var chatMenu by remember { mutableStateOf(false) }
    var scrollToBottomSignal by remember { mutableStateOf(0) }

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
                    isOffline = { appVm.connection.value.status == dev.opencode.mobile.ConnectionStatus.OFFLINE },
                )
            }
        },
    )
    val ui by vm.ui.collectAsState()
    val input by vm.input.collectAsState()
    val attachments by vm.attachments.collectAsState()
    val queuedPrompts by vm.outbox.collectAsState()
    val connection by appVm.connection.collectAsState()

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
                            Text(shortenPath(dir), style = MaterialTheme.typography.labelSmall, color = OcTheme.colors.textSecondary, maxLines = 1)
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
                    ConnectionIndicator(state = connection, onRecheck = { appVm.refreshConnection() })
                    if (ui.busy) {
                        IconButton(onClick = { vm.abort() }) {
                            Icon(Icons.Filled.Block, contentDescription = "Abort", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                    Box {
                        IconButton(onClick = { chatMenu = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "More")
                        }
                        DropdownMenu(expanded = chatMenu, onDismissRequest = { chatMenu = false }) {
                            if (ui.session?.revert != null) {
                                DropdownMenuItem(
                                    text = { Text("Undo revert") },
                                    onClick = { chatMenu = false; vm.unrevert() },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("Summarize session") },
                                onClick = { chatMenu = false; showSummarize = true },
                            )
                            DropdownMenuItem(
                                text = { Text("View diff") },
                                onClick = { chatMenu = false; onDiff() },
                            )
                        }
                    }
                },
                colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        bottomBar = {
            InputBar(
                value = input,
                onValueChange = { vm.onInputChange(it) },
                onSend = { vm.send(); scrollToBottomSignal++ },
                busy = ui.busy,
                enabled = true,
                queued = ui.queued,
                onCancelQueued = { vm.cancelQueued() },
                commands = ui.commands,
                agents = ui.agents,
                models = ui.models,
                selectedAgent = ui.selectedAgent,
                selectedModel = ui.selectedModel,
                selectedVariant = ui.selectedVariant,
                onSelectAgent = { vm.selectAgent(it) },
                onSelectModel = { vm.selectModel(it) },
                onSelectVariant = { vm.selectVariant(it) },
                attachments = attachments,
                onRemoveAttachment = { vm.removeAttachment(it) },
                onPickPhoto = { photoPicker.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                onPickFile = { filePicker.launch(arrayOf("*/*")) },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            dev.opencode.mobile.ui.common.OfflineBanner(
                connection,
                onRetry = { appVm.refreshConnection() },
                savedNote = if (queuedPrompts.isEmpty()) "Showing the copy saved on this phone. Prompts you send now wait and go out when it's back."
                    else "Showing the copy saved on this phone.",
            )
            if (ui.error != null) {
                Surface(color = OcTheme.colors.redBg, modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                    Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        SelectionContainer(modifier = Modifier.weight(1f)) {
                            Text(ui.error!!, color = OcTheme.colors.red, style = MaterialTheme.typography.bodySmall)
                        }
                        if (input.isNotBlank()) {
                            TextButton(onClick = { vm.dismissError(); vm.send(); scrollToBottomSignal++ }) { Text("Retry") }
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
            Box(Modifier.weight(1f)) {
            MessageList(
                messages = ui.messages,
                models = ui.models,
                busy = ui.busy,
                loadingOlder = ui.loadingOlder,
                hasMore = ui.hasMore,
                scrollToBottomSignal = scrollToBottomSignal,
                onLoadOlder = { vm.loadOlder() },
                onRefresh = { vm.refresh() },
                onOpenFile = onOpenFile,
                onMessageDiff = onMessageDiff,
                onFork = { messageId -> vm.forkFrom(messageId, onOpenSession) },
                onRevert = { messageId -> vm.revertTo(messageId) },
                onDelete = { messageId -> vm.deleteMessage(messageId) },
            )
            }
            QueuedPrompts(queuedPrompts, onRemove = vm::removeQueued, onRetry = vm::retryQueued)
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
    selectedVariant: String?,
    onSelectAgent: (String?) -> Unit,
    onSelectModel: (ModelOption?) -> Unit,
    onSelectVariant: (String?) -> Unit,
    attachments: List<Attachment>,
    onRemoveAttachment: (String) -> Unit,
    onPickPhoto: () -> Unit,
    onPickFile: () -> Unit,
) {
    val c = OcTheme.colors
    Surface(color = c.bg, modifier = Modifier.navigationBarsPadding()) {
        Column(Modifier.padding(start = 10.dp, end = 10.dp, top = 4.dp, bottom = 8.dp)) {
            if (queued > 0) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    dev.opencode.mobile.ui.common.StatusPill("$queued queued", c.orange)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onCancelQueued) { Text("Cancel queued", color = c.red) }
                }
            }
            // One composer card: attachments, the text, then the controls.
            Column(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(c.surface)
                    .border(1.dp, c.border, RoundedCornerShape(18.dp)),
            ) {
                if (attachments.isNotEmpty()) {
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 10.dp, end = 10.dp, top = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        for (attachment in attachments) {
                            Row(
                                Modifier.clip(RoundedCornerShape(8.dp)).background(c.surfaceVariant).padding(start = 8.dp, end = 2.dp, top = 2.dp, bottom = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Filled.AttachFile, contentDescription = null, tint = c.textSecondary, modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(attachment.name, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 140.dp))
                                IconButton(onClick = { onRemoveAttachment(attachment.uri) }, modifier = Modifier.size(28.dp)) {
                                    Icon(Icons.Filled.Close, contentDescription = "Remove attachment", modifier = Modifier.size(14.dp))
                                }
                            }
                        }
                    }
                }
                androidx.compose.material3.TextField(
                    value = value,
                    onValueChange = onValueChange,
                    placeholder = { Text(if (busy) "opencode is working… you can queue a message" else "Message opencode", color = c.textSecondary) },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 6,
                    colors = androidx.compose.material3.TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        disabledContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent,
                    ),
                )
                Row(
                    Modifier.fillMaxWidth().padding(start = 4.dp, end = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (commands.isNotEmpty()) CommandMenu(commands = commands, onPick = { cmd -> onValueChange("/${cmd} ") })
                    AttachMenu(enabled = enabled, onPickPhoto = onPickPhoto, onPickFile = onPickFile)
                    Row(
                        Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (agents.isNotEmpty()) SelectionChip(
                            label = "Agent",
                            options = agents,
                            optionLabel = { it.name },
                            selected = agents.firstOrNull { it.name == selectedAgent },
                            onSelect = { agent -> onSelectAgent(agent?.name) },
                        )
                        if (models.isNotEmpty()) SelectionChip(
                            label = "Model",
                            options = models,
                            optionLabel = { it.label },
                            selected = selectedModel,
                            selectedLabel = { it.shortLabel },
                            onSelect = onSelectModel,
                        )
                        val variants = selectedModel?.variants.orEmpty()
                        if (variants.isNotEmpty()) SelectionChip(
                            label = "Variant",
                            options = variants,
                            optionLabel = { it },
                            selected = selectedVariant?.takeIf { it in variants },
                            onSelect = onSelectVariant,
                        )
                    }
                    val canSend = enabled && (value.isNotBlank() || attachments.isNotEmpty())
                    androidx.compose.material3.FilledIconButton(
                        onClick = onSend,
                        enabled = canSend,
                        modifier = Modifier.size(40.dp),
                        colors = androidx.compose.material3.IconButtonDefaults.filledIconButtonColors(
                            containerColor = c.accent,
                            contentColor = Color.White,
                            disabledContainerColor = c.surfaceVariant,
                            disabledContentColor = c.textSecondary,
                        ),
                    ) { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send", modifier = Modifier.size(18.dp)) }
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
        IconButton(onClick = { expanded = true }, enabled = enabled, modifier = Modifier.size(40.dp)) {
            Icon(Icons.Filled.AttachFile, contentDescription = "Attach", tint = OcTheme.colors.textSecondary, modifier = Modifier.size(20.dp))
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
        IconButton(onClick = { expanded = true }, modifier = Modifier.size(40.dp)) {
            Text("/", style = MaterialTheme.typography.titleMedium, color = OcTheme.colors.textSecondary)
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
    selectedLabel: (T) -> String = optionLabel,
) {
    var expanded by remember { mutableStateOf(false) }
    val c = OcTheme.colors
    Box {
        Row(
            Modifier.clip(RoundedCornerShape(8.dp)).background(c.surfaceVariant).clickable { expanded = true }
                .padding(start = 10.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                selected?.let(selectedLabel) ?: label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = if (selected != null) c.textPrimary else c.textSecondary,
                modifier = Modifier.widthIn(max = 160.dp),
            )
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null, tint = c.textSecondary, modifier = Modifier.size(18.dp))
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
    scrollToBottomSignal: Int = 0,
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
    // "At the bottom" means the end of the list is on screen, not just the top of the last item
    // (a long reply is taller than the screen).
    val atBottom by remember { derivedStateOf { !listState.canScrollForward } }
    // Scrolls to the very end: the last item's bottom, including the "working" row under it.
    suspend fun scrollToEnd() {
        val n = listState.layoutInfo.totalItemsCount
        if (n > 0) listState.scrollToItem(n - 1, scrollOffset = 1_000_000)
    }
    // Follow the tail only while the user is parked at the bottom. Scrolling up must not be
    // yanked back down by incoming/streaming messages.
    var autoFollow by remember { mutableStateOf(true) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }
            .filter { !it }
            .collect { autoFollow = !listState.canScrollForward }
    }
    // Follow the tail as it grows: new messages, new parts, and streamed text (every ~120 chars).
    // Loading older messages changes none of these, so it never yanks the viewport.
    val tail = messages.lastOrNull()
    val tailLength = tail?.parts?.sumOf { it.text.length + (it.state?.output?.length ?: 0) } ?: 0
    LaunchedEffect(tail?.info?.id, tail?.parts?.size, tailLength / 120) {
        if (autoFollow && messages.isNotEmpty()) scrollToEnd()
    }
    // Sending a message always snaps back to the latest turn.
    LaunchedEffect(scrollToBottomSignal) {
        if (scrollToBottomSignal > 0 && messages.isNotEmpty()) {
            autoFollow = true
            scrollToEnd()
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
            // No SelectionContainer around the list: selection over a lazy list is undefined and it
            // steals the long-press that opens the message menu. Copy/Share live in that menu.
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
        if (!atBottom && messages.isNotEmpty()) {
            FloatingActionButton(
                onClick = {
                    autoFollow = true
                    scope.launch { scrollToEnd() }
                },
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
                shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 6.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.widthIn(max = 520.dp).fillMaxWidth(0.85f),
            ) {
                MarkdownText(markdown = text.ifEmpty { "…" }, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp), bodySize = 15.sp)
            }
            Text(
                "Diff",
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = OcTheme.colors.textSecondary,
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { onMessageDiff(message.info.id) }.padding(horizontal = 6.dp, vertical = 4.dp),
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
            if (parts.isNotEmpty()) {
                // A quiet byline: agent · model (the provider prefix is noise here).
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 6.dp)) {
                    Box(Modifier.size(6.dp).clip(CircleShape).background(OcTheme.colors.link))
                    Spacer(Modifier.width(6.dp))
                    Text(agentName, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = OcTheme.colors.textPrimary)
                    if (modelLabel != null) {
                        Text("  ·  " + modelLabel.substringAfterLast(" · "), fontSize = 12.sp, color = OcTheme.colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
        DropdownMenuItem(
            text = { Text("Share") },
            onClick = { menu = false; if (fullText.isNotBlank()) shareText(context, fullText) },
        )
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
            val c = OcTheme.colors
            Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                Row(
                    Modifier.clip(RoundedCornerShape(8.dp)).clickable { expanded = !expanded }.padding(horizontal = 4.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Lightbulb, contentDescription = null, tint = c.textSecondary, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Thinking", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = c.textSecondary)
                    Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = if (expanded) "Hide" else "Show", tint = c.textSecondary, modifier = Modifier.size(18.dp))
                }
                if (expanded) Row(Modifier.padding(start = 10.dp, top = 2.dp, bottom = 6.dp).height(androidx.compose.foundation.layout.IntrinsicSize.Min)) {
                    Box(Modifier.width(2.dp).fillMaxHeight().background(c.border))
                    Text(part.text, fontSize = 13.sp, lineHeight = 19.sp, color = c.textSecondary, modifier = Modifier.padding(start = 10.dp))
                }
            }
        }
        "tool" -> ToolCard(part)
        // Step boundaries are bookkeeping; the tokens line under the message already sums them up.
        "step-start", "step-finish" -> Unit
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
                ImagePart(url = part.url, filename = display)
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
) {
    val bitmap by produceState<android.graphics.Bitmap?>(initialValue = null, url) {
        value = withContext(Dispatchers.IO) { decodeDataUrl(url) }
    }
    val image = bitmap
    if (image == null) {
        MutedLabel(filename, modifier = Modifier.padding(vertical = 2.dp))
        return
    }
    var viewer by rememberSaveable(url) { mutableStateOf(false) }
    Image(
        bitmap = image.asImageBitmap(),
        contentDescription = filename,
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable { viewer = true },
    )
    if (viewer) ImageViewerDialog(bitmap = image, filename = filename, onDismiss = { viewer = false })
}

@Composable
private fun ImageViewerDialog(
    bitmap: android.graphics.Bitmap,
    filename: String,
    onDismiss: () -> Unit,
) {
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val transform = rememberTransformableState { zoomChange, panChange, _ ->
        scale = (scale * zoomChange).coerceIn(1f, 6f)
        offset = if (scale == 1f) Offset.Zero else offset + panChange
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = filename,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offset.x,
                        translationY = offset.y,
                    )
                    .transformable(transform),
            )
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
            ) {
                Icon(Icons.Filled.Close, contentDescription = "Close image", tint = Color.White)
            }
        }
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
    val c = OcTheme.colors
    var expanded by rememberSaveable(part.id) { mutableStateOf(false) }
    val running = state.status == "running" || state.status == "pending"
    val failed = state.status == "error"
    val icon = when (part.tool.substringAfterLast('_').lowercase()) {
        "bash", "shell" -> Icons.Filled.Terminal
        "edit", "write", "patch", "multiedit" -> Icons.Filled.Edit
        "read", "view" -> Icons.Filled.Description
        "grep", "glob", "list", "ls", "search", "codesearch" -> Icons.Filled.Search
        "webfetch", "websearch", "fetch" -> Icons.Filled.Public
        "task" -> Icons.Filled.AccountTree
        "todowrite", "todoread" -> Icons.Filled.Checklist
        else -> Icons.Filled.Build
    }
    val inputText = JsonUtil.pretty(state.input)
    val output = state.output
    val hasDetails = inputText.isNotBlank() || output.isNotBlank()
    Column(
        Modifier.fillMaxWidth().padding(vertical = 3.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(c.surface)
            .border(1.dp, if (failed) c.red.copy(alpha = 0.5f) else c.border, RoundedCornerShape(10.dp)),
    ) {
        Row(
            Modifier.fillMaxWidth().clickable(enabled = hasDetails) { expanded = !expanded }.padding(horizontal = 10.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = if (failed) c.red else c.textSecondary, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text(part.tool, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = c.textPrimary)
            // The title when the tool gave one, else what it worked on (file, command, pattern).
            val subject = state.title.ifBlank { toolSubject(state.input) }
            if (subject.isNotBlank()) {
                Spacer(Modifier.width(6.dp))
                Text(subject, fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            } else Spacer(Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            when {
                running -> CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                failed -> Icon(Icons.Filled.ErrorOutline, contentDescription = "Failed", tint = c.red, modifier = Modifier.size(16.dp))
                state.status == "completed" -> Icon(Icons.Filled.Check, contentDescription = "Done", tint = c.green, modifier = Modifier.size(16.dp))
            }
            if (hasDetails) Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = if (expanded) "Hide details" else "Show details", tint = c.textSecondary, modifier = Modifier.size(18.dp))
        }
        if (failed && state.error.isNotBlank()) {
            Text(state.error, color = c.red, fontSize = 12.sp, maxLines = if (expanded) 40 else 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 34.dp, end = 10.dp, bottom = 8.dp))
        }
        if (expanded) Column(Modifier.padding(bottom = 8.dp)) {
            if (inputText.isNotBlank()) ToolBlock("Input", inputText, 12)
            if (output.isNotBlank()) ToolBlock("Output", output.take(8000), 200)
        }
    }
}

@Composable
private fun ToolBlock(label: String, text: String, maxLines: Int) {
    val c = OcTheme.colors
    Text(label.uppercase(), fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp, color = c.textSecondary, modifier = Modifier.padding(start = 10.dp, top = 4.dp, bottom = 4.dp))
    Text(
        text,
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        lineHeight = 17.sp,
        color = c.textBody,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp).clip(RoundedCornerShape(6.dp)).background(c.bg).padding(8.dp),
    )
}

private fun toolSubject(input: Any?): String {
    val obj = input as? kotlinx.serialization.json.JsonObject ?: return ""
    for (key in listOf("filePath", "path", "command", "pattern", "url", "query", "description")) {
        val v = (obj[key] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content ?: continue
        if (v.isNotBlank()) return if (key == "filePath" || key == "path") v.trimEnd('/').split('/').takeLast(3).joinToString("/") else v.lineSequence().first()
    }
    return ""
}

/**
 * Prompts typed while the server was unreachable, under the conversation and above the composer:
 * "waiting" until the connection is back (then they send by themselves), or failed with the server's
 * answer and a Retry.
 */
@Composable
private fun QueuedPrompts(
    items: List<dev.opencode.mobile.data.cache.OutboxItem>,
    onRemove: (String) -> Unit,
    onRetry: (String) -> Unit,
) {
    if (items.isEmpty()) return
    val c = OcTheme.colors
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (item in items) {
            val failed = item.error != null
            Column(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (failed) c.redBg else c.surfaceVariant.copy(alpha = 0.7f))
                    .padding(start = 12.dp, end = 4.dp, top = 8.dp),
            ) {
                Text(
                    if (failed) "Couldn't send" else "Waiting to send · goes out when the server is back",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (failed) c.red else c.textSecondary,
                )
                Text(item.text, fontSize = 14.sp, color = c.textPrimary, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp, end = 8.dp))
                if (failed) Text(item.error.orEmpty(), fontSize = 11.sp, color = c.red, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp, end = 8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    if (failed) TextButton(onClick = { onRetry(item.id) }) { Text("Retry") }
                    TextButton(onClick = { onRemove(item.id) }) { Text(if (failed) "Delete" else "Cancel", color = c.textSecondary) }
                }
            }
        }
    }
}
