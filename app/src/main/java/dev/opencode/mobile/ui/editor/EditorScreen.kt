package dev.opencode.mobile.ui.editor

import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.opencode.mobile.AppViewModel
import dev.opencode.mobile.OpenCodeApp
import dev.opencode.mobile.data.net.GitChange
import dev.opencode.mobile.data.net.ServerConfig
import dev.opencode.mobile.ui.common.ConnectionIndicator
import dev.opencode.mobile.ui.common.DiffKind
import dev.opencode.mobile.ui.common.DiffLine
import dev.opencode.mobile.ui.file.FileViewerScreen
import dev.opencode.mobile.ui.terminal.MIN_WEBVIEW_CHROME
import dev.opencode.mobile.ui.terminal.webViewChromeMajor
import dev.opencode.mobile.ui.terminal.webViewVersionLabel
import dev.opencode.mobile.ui.theme.OcTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * One [EditorViewModel] per server + project folder, scoped to the Activity so the Code tab, the
 * phone editor route and the tablet layout all see the same open tabs.
 */
@Composable
fun rememberEditorViewModel(appVm: AppViewModel, server: ServerConfig): EditorViewModel {
    val context = LocalContext.current
    val app = context.applicationContext as OpenCodeApp
    val dir by appVm.currentDirectory.collectAsState()
    val activity = context as ComponentActivity
    return viewModel(
        viewModelStoreOwner = activity,
        key = "editor_${server.id}_${dir.orEmpty()}",
        factory = viewModelFactory { initializer { EditorViewModel(app, server, projectDir = { dir }) } },
    )
}

/** Whether the system WebView can run the CodeMirror bundle (built for Chromium 80, like xterm). */
@Composable
fun editorSupported(): Boolean {
    val context = LocalContext.current
    return remember(context) { webViewChromeMajor(context)?.let { it >= MIN_WEBVIEW_CHROME } ?: true }
}

/** Phone editor route: app bar, path bar with Save, tabs, CodeMirror, accessory key row. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    appVm: AppViewModel,
    serverId: String,
    path: String?,
    line: Int?,
    onBack: () -> Unit,
    onViewReadOnly: (String) -> Unit,
) {
    val servers by appVm.servers.collectAsState()
    val server = remember(servers, serverId) { servers.firstOrNull { it.id == serverId } }
    if (server == null) {
        Box(Modifier.fillMaxSize().padding(24.dp)) { Text("Server not found.", color = OcTheme.colors.textSecondary) }
        return
    }
    val vm = rememberEditorViewModel(appVm, server)
    val ui by vm.ui.collectAsState()
    LaunchedEffect(path, line) { if (!path.isNullOrBlank()) vm.open(path, line) }

    var leaveGuard by remember { mutableStateOf(false) }
    BackHandler(enabled = ui.dirtyTabs.isNotEmpty()) { leaveGuard = true }
    if (leaveGuard) {
        UnsavedLeaveDialog(
            count = ui.dirtyTabs.size,
            onKeepEditing = { leaveGuard = false },
            onDiscard = { leaveGuard = false; vm.discardDirty(); onBack() },
            onLeave = { leaveGuard = false; onBack() },
        )
    }

    Scaffold(
        modifier = Modifier.imePadding(),
        containerColor = OcTheme.colors.bg,
        topBar = {
            EditorAppBar(
                appVm = appVm,
                server = server,
                onBack = { if (ui.dirtyTabs.isNotEmpty()) leaveGuard = true else onBack() },
                vm = vm,
                onViewReadOnly = onViewReadOnly,
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            PathBar(vm = vm)
            EditorPane(vm = vm, modifier = Modifier.weight(1f), onViewReadOnly = onViewReadOnly, appVm = appVm, serverId = serverId)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditorAppBar(
    appVm: AppViewModel,
    server: ServerConfig,
    onBack: () -> Unit,
    vm: EditorViewModel,
    onViewReadOnly: (String) -> Unit,
) {
    val dir by appVm.currentDirectory.collectAsState()
    val connection by appVm.connection.collectAsState()
    TopAppBar(
        navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back") } },
        title = {
            Column {
                Text(
                    dir?.trimEnd('/')?.substringAfterLast('/') ?: "Project",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(server.name.ifBlank { "Mac" }, fontSize = 12.sp, color = OcTheme.colors.textSecondary, maxLines = 1)
                    ConnectionIndicator(state = connection, onRecheck = { appVm.refreshConnection() })
                }
            }
        },
        actions = {
            IconButton(onClick = { vm.showPalette(true) }) { Icon(Icons.Outlined.Search, contentDescription = "Go to file") }
            MetroAction(appVm = appVm, vm = vm)
            EditorOverflowMenu(vm = vm, onViewReadOnly = onViewReadOnly)
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = OcTheme.colors.bg),
    )
}

@Composable
fun EditorOverflowMenu(vm: EditorViewModel, onViewReadOnly: (String) -> Unit) {
    val ui by vm.ui.collectAsState()
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Outlined.MoreVert, contentDescription = "More") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Find and replace") }, onClick = { open = false; vm.command("find") })
            DropdownMenuItem(text = { Text("Go to line") }, onClick = { open = false; vm.command("goToLine") })
            DropdownMenuItem(
                text = { Text(if (ui.softWrap) "Soft wrap: on" else "Soft wrap: off") },
                onClick = { open = false; vm.toggleWrap() },
            )
            DropdownMenuItem(text = { Text("Toggle comment") }, onClick = { open = false; vm.command("toggleComment") })
            if (ui.lsp == LspStatus.READY && ui.activeTab?.language in LSP_LANGUAGES) {
                DropdownMenuItem(text = { Text("Go to definition") }, onClick = { open = false; vm.command("definition") })
                DropdownMenuItem(text = { Text("Rename symbol") }, onClick = { open = false; vm.command("rename") })
                DropdownMenuItem(text = { Text("Find references") }, onClick = { open = false; vm.command("references") })
                DropdownMenuItem(text = { Text("Problems") }, onClick = { open = false; vm.command("problems") })
                DropdownMenuItem(text = { Text("Format document") }, onClick = { open = false; vm.command("format") })
            }
            DropdownMenuItem(text = { Text("Redo") }, onClick = { open = false; vm.command("redo") })
            ui.active?.let { path ->
                DropdownMenuItem(text = { Text("View read-only") }, onClick = { open = false; onViewReadOnly(path) })
                DropdownMenuItem(text = { Text("Close tab") }, onClick = { open = false; vm.requestClose(path) })
            }
        }
    }
}

/** "src/screens/Signup.tsx  [Save]" — the phone's save affordance from the mockup. */
@Composable
private fun PathBar(vm: EditorViewModel) {
    val ui by vm.ui.collectAsState()
    val tab = ui.activeTab ?: return
    val c = OcTheme.colors
    Row(
        Modifier.fillMaxWidth().background(c.bg).padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            tab.path,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            color = c.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.StartEllipsis,
            modifier = Modifier.weight(1f),
        )
        SaveButton(vm = vm, tab = tab)
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(c.border))
}

@Composable
fun SaveButton(vm: EditorViewModel, tab: EditorTab) {
    val c = OcTheme.colors
    Button(
        onClick = { vm.save(tab.path) },
        enabled = tab.status != SaveStatus.SAVING,
        shape = RoundedCornerShape(10.dp),
        colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = androidx.compose.ui.graphics.Color.White),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
        modifier = Modifier.heightIn(min = 40.dp),
    ) {
        if (tab.status == SaveStatus.SAVING) {
            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = androidx.compose.ui.graphics.Color.White)
            Spacer(Modifier.width(8.dp))
        }
        Text("Save", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * Tabs + CodeMirror + key row + every editor dialog. Shared by the phone route and the wide layout.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EditorPane(
    vm: EditorViewModel,
    appVm: AppViewModel,
    serverId: String,
    onViewReadOnly: (String) -> Unit,
    modifier: Modifier = Modifier,
    showGit: Boolean = true,
) {
    val ui by vm.ui.collectAsState()
    val c = OcTheme.colors
    val supported = editorSupported()
    var changesOpen by remember { mutableStateOf(false) }

    Column(modifier.background(c.bg)) {
        EditorTabs(vm = vm, ui = ui, showGit = showGit, onOpenChanges = { changesOpen = true })
        ui.error?.let { message ->
            Row(
                Modifier.fillMaxWidth().background(c.errorBg).padding(start = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(message, color = c.errorText, fontSize = 13.sp, modifier = Modifier.weight(1f).padding(vertical = 8.dp))
                IconButton(onClick = { vm.clearError() }) { Icon(Icons.Outlined.Close, contentDescription = "Dismiss", tint = c.errorText) }
            }
        }
        ui.notice?.let { message ->
            Row(
                Modifier.fillMaxWidth().background(c.surface).padding(start = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(message, color = c.textBody, fontSize = 13.sp, modifier = Modifier.weight(1f).padding(vertical = 8.dp))
                IconButton(onClick = { vm.clearNotice() }) { Icon(Icons.Outlined.Close, contentDescription = "Dismiss", tint = c.textSecondary) }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                !supported -> {
                    Column(Modifier.fillMaxSize()) {
                        val context = LocalContext.current
                        Text(
                            "Editing needs WebView Chromium $MIN_WEBVIEW_CHROME+ (this device: ${webViewVersionLabel(context)}). Showing the file read-only — update Android System WebView to edit.",
                            color = c.errorText,
                            fontSize = 13.sp,
                            modifier = Modifier.fillMaxWidth().background(c.errorBg).padding(12.dp),
                        )
                        ui.active?.let { FileViewerScreen(appVm = appVm, serverId = serverId, path = it, line = null, onBack = {}) }
                    }
                }
                else -> EditorWebView(vm = vm, modifier = Modifier.fillMaxSize())
            }
            if (ui.tabs.isEmpty()) {
                Column(
                    Modifier.fillMaxSize().background(c.bg).padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (ui.loading != null) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(12.dp))
                        Text("Opening ${ui.loading!!.substringAfterLast('/')}…", color = c.textSecondary)
                    } else {
                        Text("No file open", color = c.textBody, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(6.dp))
                        Text("Pick a file from the tree, or press Ctrl+P.", color = c.textSecondary, fontSize = 13.sp)
                        Spacer(Modifier.height(12.dp))
                        TextButton(onClick = { vm.showPalette(true) }) { Text("Go to file") }
                    }
                }
            } else if (ui.loading != null) {
                CircularProgressIndicator(Modifier.align(Alignment.TopEnd).padding(12.dp).size(20.dp), strokeWidth = 2.dp)
            }
        }
        if (supported && ui.tabs.isNotEmpty() && ui.editorFocused && WindowInsets.isImeVisible) {
            AccessoryKeyRow(vm = vm)
        }
    }

    ui.conflict?.let { ConflictDialog(vm = vm, conflict = it) }
    ui.pendingClose?.let { path ->
        AlertDialog(
            onDismissRequest = { vm.cancelClose() },
            title = { Text("Discard changes?") },
            text = { Text("${path.substringAfterLast('/')} has unsaved changes.") },
            confirmButton = { TextButton(onClick = { vm.close(path) }) { Text("Discard", color = c.red) } },
            dismissButton = { TextButton(onClick = { vm.cancelClose() }) { Text("Cancel") } },
        )
    }
    if (ui.paletteOpen) GoToFileDialog(vm = vm)
    ui.renameWord?.let { word ->
        var name by remember(word) { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(word, androidx.compose.ui.text.TextRange(0, word.length))) }
        val focus = remember { FocusRequester() }
        LaunchedEffect(word) { runCatching { focus.requestFocus() } }
        AlertDialog(
            onDismissRequest = { vm.renameTo(null) },
            title = { Text("Rename symbol") },
            text = {
                Column {
                    Text("Renames \"$word\" in every file that uses it. Changed files open as tabs; save them to apply.", fontSize = 13.sp, color = c.textSecondary)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, modifier = Modifier.fillMaxWidth().focusRequester(focus))
                }
            },
            confirmButton = { TextButton(enabled = name.text.isNotBlank() && name.text != word, onClick = { vm.renameTo(name.text) }) { Text("Rename") } },
            dismissButton = { TextButton(onClick = { vm.renameTo(null) }) { Text("Cancel") } },
        )
    }
    if (changesOpen) ChangesSheet(vm = vm, onDismiss = { changesOpen = false })
}

@Composable
private fun EditorTabs(vm: EditorViewModel, ui: EditorUi, showGit: Boolean, onOpenChanges: () -> Unit) {
    val c = OcTheme.colors
    Column {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (tab in ui.tabs) {
                val active = tab.path == ui.active
                Row(
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (active) c.surface else androidx.compose.ui.graphics.Color.Transparent)
                        .clickable { vm.select(tab.path) }
                        .heightIn(min = 40.dp)
                        .padding(start = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        tab.name,
                        fontSize = 12.sp,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (active) c.textPrimary else c.textSecondary,
                        maxLines = 1,
                    )
                    if (tab.dirty) {
                        Spacer(Modifier.width(6.dp))
                        Box(Modifier.size(7.dp).background(c.warning, CircleShape))
                    }
                    IconButton(onClick = { vm.requestClose(tab.path) }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Outlined.Close, contentDescription = "Close ${tab.name}", tint = c.textSecondary, modifier = Modifier.size(14.dp))
                    }
                }
            }
        }
        // Second row: git (left, opens Changes) and the "TypeScript · LSP ready · Saved" status (right).
        val showBranch = showGit && ui.branch != null
        if (showBranch || ui.activeTab != null) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 36.dp).padding(start = 8.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (showBranch) {
                    Row(
                        Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onOpenChanges).heightIn(min = 36.dp).padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Outlined.AccountTree, contentDescription = null, tint = c.textSecondary, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(gitLabel(ui), fontSize = 12.sp, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Spacer(Modifier.weight(1f))
                ui.activeTab?.let { tab ->
                    Text(
                        statusText(tab, ui.cursor, ui.lsp),
                        fontSize = 11.sp,
                        color = if (tab.status == SaveStatus.FAILED) c.errorText else c.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.border))
    }
}

fun gitLabel(ui: EditorUi): String {
    val count = ui.changes?.size
    return buildString {
        append(ui.branch ?: "")
        if (count != null) append(" · ").append(if (count == 0) "clean" else "$count changed")
    }
}

private fun statusText(tab: EditorTab, cursor: Pair<Int, Int>?, lsp: LspStatus = LspStatus.OFF): String {
    val status = when (tab.status) {
        SaveStatus.SAVING -> "Saving…"
        SaveStatus.SAVED -> if (tab.dirty) "Edited" else "Saved"
        SaveStatus.FAILED -> "Failed: ${tab.message ?: "unknown error"}"
        SaveStatus.IDLE -> tab.message ?: if (tab.dirty) "Edited" else "Saved"
    }
    val position = cursor?.let { " · Ln ${it.first}, Col ${it.second}" }.orEmpty()
    val server = if (tab.language in LSP_LANGUAGES) when (lsp) {
        LspStatus.READY -> " · LSP ready"
        LspStatus.STARTING -> " · LSP starting…"
        LspStatus.FAILED -> " · LSP off"
        LspStatus.OFF -> ""
    } else ""
    return "${tab.language.label}$server · $status$position"
}

/** Coding keys above the soft keyboard; horizontally scrollable when it doesn't fit. */
@Composable
private fun AccessoryKeyRow(vm: EditorViewModel) {
    val c = OcTheme.colors
    val keys: List<Pair<String, () -> Unit>> = listOf(
        "Tab" to { vm.command("insertTab") },
        "{ }" to { vm.insert("{}") },
        "( )" to { vm.insert("()") },
        "[ ]" to { vm.insert("[]") },
        "< >" to { vm.insert("<>") },
        "=" to { vm.insert("=") },
        "'" to { vm.insert("'") },
        "\"" to { vm.insert("\"") },
        ";" to { vm.insert(";") },
        "←" to { vm.command("left") },
        "→" to { vm.command("right") },
    )
    Row(
        Modifier
            .fillMaxWidth()
            .background(c.surface)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for ((label, action) in keys) {
            KeyCap(onClick = action, description = when (label) { "←" -> "Cursor left"; "→" -> "Cursor right"; else -> label }) {
                Text(label, fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = c.textPrimary)
            }
        }
        KeyCap(onClick = { vm.command("undo") }, description = "Undo") {
            Icon(Icons.AutoMirrored.Outlined.Undo, contentDescription = null, tint = c.textPrimary, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun KeyCap(onClick: () -> Unit, description: String, content: @Composable () -> Unit) {
    val c = OcTheme.colors
    // Not focusable: tapping a key must not pull focus (and the IME) away from the editor.
    Box(
        Modifier
            .heightIn(min = 44.dp)
            .widthIn(min = 48.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(c.bg)
            .border(1.dp, c.border, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = description }
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun ConflictDialog(vm: EditorViewModel, conflict: SaveConflict) {
    val c = OcTheme.colors
    AlertDialog(
        onDismissRequest = { vm.conflictDismiss() },
        title = { Text("File changed on the Mac") },
        text = {
            Column {
                Text(
                    if (conflict.remote == null) "${conflict.path} was deleted or can't be read on the Mac since you opened it."
                    else "${conflict.path} was edited on the Mac (by the agent or someone else) since you opened it. Your changes were not saved.",
                    color = c.textBody,
                    fontSize = 14.sp,
                )
                if (conflict.showDiff) {
                    Spacer(Modifier.height(10.dp))
                    Text("Mac version → your version", fontSize = 12.sp, color = c.textSecondary)
                    Spacer(Modifier.height(4.dp))
                    DiffList(conflict.diff, Modifier.heightIn(max = 360.dp))
                }
            }
        },
        confirmButton = {
            Row {
                if (!conflict.showDiff && conflict.remote != null) TextButton(onClick = { vm.conflictShowDiff() }) { Text("Show diff") }
                if (conflict.remote != null) TextButton(onClick = { vm.conflictReload() }) { Text("Reload") }
                TextButton(onClick = { vm.conflictOverwrite() }) { Text("Overwrite", color = c.red) }
            }
        },
        dismissButton = { TextButton(onClick = { vm.conflictDismiss() }) { Text("Cancel") } },
    )
}

@Composable
fun DiffList(lines: List<DiffLine>, modifier: Modifier = Modifier) {
    val c = OcTheme.colors
    LazyColumn(modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(c.bg)) {
        items(lines.size) { index ->
            val line = lines[index]
            val (bg, fg, sign) = when (line.kind) {
                DiffKind.ADD -> Triple(c.greenBg, c.textPrimary, "+")
                DiffKind.DEL -> Triple(c.errorBg, c.errorText, "-")
                DiffKind.CONTEXT -> Triple(androidx.compose.ui.graphics.Color.Transparent, c.textBody, " ")
            }
            Text(
                "$sign ${line.text}",
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = fg,
                softWrap = false,
                maxLines = 1,
                modifier = Modifier.fillMaxWidth().background(bg).padding(horizontal = 6.dp, vertical = 1.dp),
            )
        }
        if (lines.isEmpty()) item { Text("No differences", color = c.textSecondary, modifier = Modifier.padding(8.dp)) }
    }
}

/** Ctrl+P / top-bar "Go to file": fuzzy file find, recent files when the query is empty. */
@Composable
private fun GoToFileDialog(vm: EditorViewModel) {
    val c = OcTheme.colors
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<String>>(emptyList()) }
    val recent by vm.recentFiles.collectAsState(initial = emptyList())
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    LaunchedEffect(query) {
        if (query.isBlank()) { results = emptyList(); return@LaunchedEffect }
        delay(200)
        results = vm.findFiles(query.trim())
    }
    val shown = if (query.isBlank()) recent else results
    AlertDialog(
        onDismissRequest = { vm.showPalette(false) },
        title = { Text("Go to file") },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    placeholder = { Text("File name") },
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
                Spacer(Modifier.height(8.dp))
                if (query.isBlank() && recent.isNotEmpty()) SectionLabel("Recent")
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(shown, key = { it }) { path ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { vm.showPalette(false); vm.open(path) }
                                .heightIn(min = 48.dp)
                                .padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Outlined.Description, contentDescription = null, tint = c.textSecondary, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text(path.substringAfterLast('/'), fontSize = 14.sp, color = c.textPrimary, maxLines = 1)
                                Text(path, fontSize = 11.sp, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.StartEllipsis)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = { vm.showPalette(false) }) { Text("Close") } },
    )
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.96.sp,
        color = OcTheme.colors.textSecondary,
        modifier = modifier.padding(vertical = 6.dp),
    )
}

/** Git "Changes": changed files → diff, plus Commit… (`git add -A && git commit -m`). No push yet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChangesSheet(vm: EditorViewModel, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = OcTheme.colors.surface) {
        ChangesPanel(vm = vm, modifier = Modifier.fillMaxWidth().heightIn(min = 240.dp, max = 640.dp))
    }
}

@Composable
fun ChangesPanel(vm: EditorViewModel, modifier: Modifier = Modifier) {
    val ui by vm.ui.collectAsState()
    val c = OcTheme.colors
    val scope = rememberCoroutineScope()
    var diffFor by remember { mutableStateOf<GitChange?>(null) }
    var diff by remember { mutableStateOf<List<DiffLine>?>(null) }
    var committing by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var commitResult by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { vm.refreshGit() }
    LaunchedEffect(diffFor) {
        diff = null
        diffFor?.let { diff = vm.diff(it) }
    }

    Column(modifier.padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (diffFor != null) {
                IconButton(onClick = { diffFor = null }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back to changes") }
            }
            Text(
                diffFor?.path ?: gitLabel(ui).ifBlank { "Changes" },
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                color = c.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.StartEllipsis,
                modifier = Modifier.weight(1f).padding(vertical = 8.dp),
            )
            if (diffFor == null && !ui.changes.isNullOrEmpty()) {
                Button(
                    onClick = { committing = true; commitResult = null },
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = androidx.compose.ui.graphics.Color.White),
                ) { Text("Commit…") }
            }
        }
        commitResult?.let { Text(it, fontSize = 12.sp, color = c.textSecondary, modifier = Modifier.padding(bottom = 6.dp)) }
        when {
            diffFor != null -> {
                val lines = diff
                if (lines == null) CircularProgressIndicator(Modifier.padding(16.dp)) else DiffList(lines, Modifier.weight(1f, fill = false).padding(bottom = 16.dp))
            }
            ui.changes == null -> Text(
                if (ui.branch == null) "This folder isn't a git repository." else "Couldn't run git status.",
                color = c.textSecondary,
                modifier = Modifier.padding(vertical = 12.dp),
            )
            ui.changes!!.isEmpty() -> Text("Working tree clean.", color = c.textSecondary, modifier = Modifier.padding(vertical = 12.dp))
            else -> LazyColumn(Modifier.weight(1f, fill = false).padding(bottom = 16.dp)) {
                items(ui.changes!!, key = { it.path }) { change ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { diffFor = change }
                            .heightIn(min = 48.dp)
                            .padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(change.path, fontSize = 14.sp, color = c.textBody, maxLines = 1, overflow = TextOverflow.StartEllipsis, modifier = Modifier.weight(1f))
                        Text(
                            change.label,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = when (change.label) { "A", "U" -> c.green; "D" -> c.red; else -> c.warning },
                        )
                    }
                }
            }
        }
    }

    if (committing) {
        var busy by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { if (!busy) committing = false },
            title = { Text("Commit all changes") },
            text = {
                Column {
                    Text("Runs git add -A and git commit on the Mac. Nothing is pushed.", fontSize = 13.sp, color = c.textSecondary)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = message, onValueChange = { message = it }, label = { Text("Message") }, minLines = 2, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !busy && message.isNotBlank(),
                    onClick = {
                        busy = true
                        scope.launch {
                            val result = vm.commit(message)
                            busy = false
                            committing = false
                            commitResult = if (result.ok) "Committed." else "Commit failed: ${result.output.lines().lastOrNull { it.isNotBlank() } ?: "exit ${result.exitCode}"}"
                            if (result.ok) message = ""
                        }
                    },
                ) { Text(if (busy) "Committing…" else "Commit") }
            },
            dismissButton = { TextButton(enabled = !busy, onClick = { committing = false }) { Text("Cancel") } },
        )
    }
}

@Composable
fun UnsavedLeaveDialog(count: Int, onKeepEditing: () -> Unit, onDiscard: () -> Unit, onLeave: () -> Unit) {
    AlertDialog(
        onDismissRequest = onKeepEditing,
        title = { Text("Unsaved changes") },
        text = { Text("$count file${if (count == 1) " has" else "s have"} unsaved changes. Leaving keeps them open in the editor until you save or discard them.") },
        confirmButton = { TextButton(onClick = onLeave) { Text("Leave") } },
        dismissButton = {
            Row {
                TextButton(onClick = onDiscard) { Text("Discard", color = OcTheme.colors.red) }
                TextButton(onClick = onKeepEditing) { Text("Keep editing") }
            }
        },
    )
}
