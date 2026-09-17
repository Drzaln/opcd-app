package dev.opencode.mobile.ui.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.opencode.mobile.AppViewModel
import dev.opencode.mobile.R
import dev.opencode.mobile.OpenCodeApp
import dev.opencode.mobile.data.model.PtyInfo
import dev.opencode.mobile.terminal.Attr
import dev.opencode.mobile.terminal.COLOR_DEFAULT
import dev.opencode.mobile.terminal.Cell
import dev.opencode.mobile.terminal.TRUE_RGB_BASE
import kotlinx.coroutines.launch

private val Ansi16 = arrayOf(
    Color(0xFF000000), Color(0xFFCD3131), Color(0xFF0DBC79), Color(0xFFE5E510),
    Color(0xFF2472C8), Color(0xFFBC3FBC), Color(0xFF11A8CD), Color(0xFFE5E5E5),
    Color(0xFF666666), Color(0xFFF14C4C), Color(0xFF23D18B), Color(0xFFF5F543),
    Color(0xFF3B8EEA), Color(0xFFD670D6), Color(0xFF29B8DB), Color(0xFFFFFFFF),
)

private fun ansiColor(value: Int, default: Color): Color = when {
    value == COLOR_DEFAULT -> default
    value >= TRUE_RGB_BASE -> {
        val rgb = value - TRUE_RGB_BASE
        Color(0xFF000000L.toInt() or rgb)
    }
    value in 0..15 -> Ansi16[value]
    value in 16..231 -> {
        val i = value - 16
        val r = i / 36
        val g = (i % 36) / 6
        val b = i % 6
        fun c(v: Int) = if (v == 0) 0 else 55 + v * 40
        Color(0xFF000000L.toInt() or (c(r) shl 16) or (c(g) shl 8) or c(b))
    }
    value in 232..255 -> {
        val v = 8 + (value - 232) * 10
        Color(0xFF000000L.toInt() or (v shl 16) or (v shl 8) or v)
    }
    else -> default
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(
    appVm: AppViewModel,
    serverId: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as OpenCodeApp
    val servers by appVm.servers.collectAsState()
    val server = remember(servers, serverId) { servers.firstOrNull { it.id == serverId } }

    if (server == null) {
        Column(Modifier.fillMaxSize().padding(24.dp)) { Text("Server not found.") }
        return
    }

    val vm: TerminalViewModel = viewModel(
        key = "terminal_${serverId}",
        factory = viewModelFactory {
            initializer { TerminalViewModel(app, server, projectDir = { appVm.currentDirectory.value }) }
        },
    )
    val ui by vm.ui.collectAsState()
    var shellsMenu by remember { mutableStateOf(false) }

    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = {
            TopAppBar(
                title = { Text(ui.active?.title?.ifBlank { "Terminal" } ?: "Terminal", maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    if (ui.active != null) {
                        IconButton(onClick = { vm.kill(ui.active!!) }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Kill session")
                        }
                    }
                    Box {
                        IconButton(onClick = { shellsMenu = true }) {
                            Icon(Icons.Filled.Add, contentDescription = "New terminal")
                        }
                        DropdownMenu(expanded = shellsMenu, onDismissRequest = { shellsMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("Default shell") },
                                onClick = { shellsMenu = false; vm.create() },
                            )
                            for (shell in ui.shells) {
                                DropdownMenuItem(
                                    text = { Text(shell.name) },
                                    onClick = { shellsMenu = false; vm.create(shell.path) },
                                )
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            val active = ui.active
            if (active == null) {
                SessionList(
                    sessions = ui.sessions,
                    loading = ui.loading,
                    status = ui.status,
                    onOpen = { vm.attach(it) },
                    onKill = { vm.kill(it) },
                    onCreate = { vm.create() },
                )
            } else {
                TerminalView(vm = vm)
            }
        }
    }
}

@Composable
private fun SessionList(
    sessions: List<PtyInfo>,
    loading: Boolean,
    status: String,
    onOpen: (PtyInfo) -> Unit,
    onKill: (PtyInfo) -> Unit,
    onCreate: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(onClick = onCreate) {
                Icon(Icons.Filled.Terminal, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("New terminal")
            }
            Spacer(Modifier.width(12.dp))
            Text(status, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (loading && sessions.isEmpty()) {
            CircularProgressIndicator(Modifier.padding(24.dp))
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(sessions.size, key = { sessions[it].id }) { index ->
                val pty = sessions[index]
                ListItem(
                    modifier = Modifier.clickable { onOpen(pty) },
                    headlineContent = { Text(pty.title.ifBlank { pty.command }) },
                    supportingContent = { Text("${pty.status} · pid ${pty.pid} · ${pty.cwd}") },
                    leadingContent = { Icon(Icons.Filled.Terminal, contentDescription = null) },
                    trailingContent = {
                        IconButton(onClick = { onKill(pty) }) { Icon(Icons.Filled.Delete, contentDescription = "Kill") }
                    },
                )
            }
        }
    }
}

@Composable
private fun TerminalView(vm: TerminalViewModel) {
    val frameState = vm.frame.collectAsState()
    val frame = frameState.value
    val ui by vm.ui.collectAsState()
    val emulator = vm.emulator
    val context = LocalContext.current
    val defaultFg = MaterialTheme.colorScheme.onSurface
    val defaultBg = MaterialTheme.colorScheme.surface
    val terminalFont = remember {
        FontFamily(
            androidx.compose.ui.text.font.Font(R.font.jetbrains_mono_nerd_regular, androidx.compose.ui.text.font.FontWeight.Normal),
            androidx.compose.ui.text.font.Font(R.font.jetbrains_mono_nerd_bold, androidx.compose.ui.text.font.FontWeight.Bold),
        )
    }
    val mono = remember(terminalFont) { TextStyle(fontFamily = terminalFont, fontSize = 12.sp, lineHeight = 16.sp) }
    val measurer = rememberTextMeasurer()
    val cellWidth = remember(measurer) {
        measurer.measure(AnnotatedString("M"), mono, softWrap = false).size.width
    }
    val rowHeightPx = remember(measurer) { measurer.measure(AnnotatedString("M"), mono, softWrap = false).size.height }

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }
    val atBottom by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= listState.layoutInfo.totalItemsCount - 1
        }
    }
    var input by remember { mutableStateOf("") }
    var ctrl by remember { mutableStateOf(false) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val widthPx = with(androidx.compose.ui.platform.LocalDensity.current) { maxWidth.toPx() }
        val heightPx = with(androidx.compose.ui.platform.LocalDensity.current) { maxHeight.toPx() }
        val cell = cellWidth.coerceAtLeast(1).toFloat()
        val lineH = rowHeightPx.coerceAtLeast(1).toFloat()
        val cols = (widthPx / cell).toInt().coerceIn(10, 500)
        val rows = (heightPx / lineH).toInt().coerceIn(4, 300)
        LaunchedEffect(cols, rows, ui.active?.id) {
            if (ui.active != null) vm.resize(cols, rows)
        }

        Column(Modifier.fillMaxSize().background(defaultBg)) {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth().clickable { focusRequester.requestFocus() },
                contentPadding = PaddingValues(horizontal = 4.dp),
            ) {
                val total = emulator.totalLines()
                items(total, key = { "line_$it" }) { index ->
                    // Read the frame State inside the item scope so rows recompose on new output;
                    // the emulator is a plain object and would otherwise be skipped.
                    @Suppress("UNUSED_EXPRESSION") frameState.value
                    val cells = emulator.screenLine(index) ?: emptyList()
                    val isCursorRow = index == emulator.cursorRow
                    TerminalRow(
                        cells = cells,
                        cursorCol = if (isCursorRow) emulator.cursorCol else -1,
                        showCursor = isCursorRow && emulator.isCursorVisible,
                        defaultFg = defaultFg,
                        defaultBg = defaultBg,
                        style = mono,
                    )
                }
            }
            LaunchedEffect(frame) {
                if (atBottom) {
                    val total = emulator.totalLines()
                    if (total > 0) listState.scrollToItem((total - 1).coerceAtLeast(0))
                }
            }

            KeyRow(ctrl = ctrl, onCtrlToggle = { ctrl = !ctrl }, onKey = { vm.send(it) })

            if (!ui.connected) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        ui.status,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { ui.active?.let { vm.attach(it) } }) { Text("Reconnect") }
                }
            }

            Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 6.dp) {
                Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 8.dp, vertical = 6.dp)) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { new ->
                            when {
                                new.length > input.length -> {
                                    val added = new.substring(input.length)
                                    vm.send(if (ctrl) toCtrl(added) else added)
                                    ctrl = false
                                }
                                new.length < input.length -> vm.send("\u007f")
                            }
                            input = ""
                        },
                        placeholder = { Text(if (ui.connected) "type…" else ui.status) },
                        modifier = Modifier.weight(1f).focusRequester(focusRequester),
                        maxLines = 1,
                    )
                    Spacer(Modifier.width(6.dp))
                    TextButton(onClick = { vm.detach() }) { Text("Detach") }
                    TextButton(onClick = { focusRequester.requestFocus() }) {
                        Icon(Icons.Filled.Keyboard, contentDescription = "Show keyboard")
                    }
                }
            }
        }
        LaunchedEffect(ui.active?.id) { focusRequester.requestFocus() }
    }
}

@Composable
private fun KeyRow(ctrl: Boolean, onCtrlToggle: () -> Unit, onKey: (String) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 6.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (ctrl) {
            AssistChip(onClick = onCtrlToggle, label = { Text("CTRL") })
        } else {
            AssistChip(onClick = onCtrlToggle, label = { Text("ctrl") })
        }
        TerminalKey("esc") { onKey("\u001b") }
        TerminalKey("tab") { onKey("\t") }
        TerminalKey("↑") { onKey("\u001b[A") }
        TerminalKey("↓") { onKey("\u001b[B") }
        TerminalKey("←") { onKey("\u001b[D") }
        TerminalKey("→") { onKey("\u001b[C") }
        TerminalKey("^C") { onKey("\u0003") }
        TerminalKey("^D") { onKey("\u0004") }
        TerminalKey("^Z") { onKey("\u001a") }
        TerminalKey("|") { onKey("|") }
        TerminalKey("-") { onKey("-") }
        TerminalKey("/") { onKey("/") }
        TerminalKey("↵") { onKey("\r") }
    }
}

@Composable
private fun TerminalKey(label: String, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}

private fun toCtrl(text: String): String = buildString {
    for (c in text) {
        append(if (c in 'a'..'z') ('\u0001' + (c - 'a')) else if (c in 'A'..'Z') ('\u0001' + (c - 'A')) else c)
    }
}

@Composable
private fun TerminalRow(
    cells: List<Cell>,
    cursorCol: Int,
    showCursor: Boolean,
    defaultFg: Color,
    defaultBg: Color,
    style: TextStyle,
) {
    val text = buildAnnotatedString {
        for ((index, cell) in cells.withIndex()) {
            val inverse = cell.attr and Attr.INVERSE != 0
            var fg = ansiColor(cell.fg, defaultFg)
            var bg = ansiColor(cell.bg, defaultBg)
            if (inverse) {
                val t = fg; fg = bg; bg = t
            }
            val isCursor = showCursor && index == cursorCol
            if (isCursor) {
                val t = fg; fg = bg; bg = t
            }
            val span = SpanStyle(
                color = fg,
                background = if (bg == defaultBg) Color.Unspecified else bg,
                fontWeight = if (cell.attr and Attr.BOLD != 0) FontWeight.Bold else null,
                fontStyle = if (cell.attr and Attr.ITALIC != 0) FontStyle.Italic else null,
                textDecoration = if (cell.attr and Attr.UNDERLINE != 0) TextDecoration.Underline else null,
            )
            withStyle(span) { append(if (cell.ch == '\u0000') ' ' else cell.ch) }
        }
    }
    Text(
        text = text,
        style = style,
        softWrap = false,
        maxLines = 1,
        modifier = Modifier.fillMaxWidth(),
    )
}
