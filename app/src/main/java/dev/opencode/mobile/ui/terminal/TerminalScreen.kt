package dev.opencode.mobile.ui.terminal

import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Constraints
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
import kotlinx.coroutines.flow.distinctUntilChanged
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
    val ui by vm.ui.collectAsState()
    val emulator = vm.emulator
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
    val density = LocalDensity.current
    val cellWidth = remember(measurer) { measurer.measure(AnnotatedString("M"), mono, softWrap = false).size.width }
    val rowHeight = remember(measurer) { measurer.measure(AnnotatedString("M"), mono, softWrap = false).size.height }

    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }
    var ctrl by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }

    val scrollState = rememberScrollState()
    // Follow the newest output only while parked at the bottom. A programmatic scrollTo runs at a
    // lower scroll priority than a drag, so it can't yank the viewport away while the user reads.
    val following by remember { derivedStateOf { scrollState.value >= scrollState.maxValue - 8 } }
    LaunchedEffect(scrollState) {
        snapshotFlow { scrollState.maxValue }.collect { max -> if (following) scrollState.scrollTo(max) }
    }
    // A freshly attached PTY starts pinned to the bottom.
    LaunchedEffect(ui.active?.id) { scrollState.scrollTo(Int.MAX_VALUE) }

    val terminalPad = 16.dp

    Column(Modifier.fillMaxSize().background(defaultBg)) {
        BoxWithConstraints(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(start = terminalPad, end = terminalPad, bottom = terminalPad),
        ) {
            // Measure the canvas viewport HERE (not the whole column) so the PTY grid matches what is
            // actually visible — counting the key/status rows below over-reported `rows` and pushed
            // the prompt off the bottom of the screen.
            val widthPx = with(density) { maxWidth.toPx() }
            val viewportPx = with(density) { maxHeight.toPx() }
            val cell = cellWidth.toFloat().coerceAtLeast(1f)
            val lineH = rowHeight.toFloat().coerceAtLeast(1f)
            val cols = (widthPx / cell).toInt().coerceIn(10, 500)
            val rows = (viewportPx / lineH).toInt().coerceIn(4, 400)
            LaunchedEffect(cols, rows, ui.active?.id) {
                if (ui.active != null) vm.resize(cols, rows)
            }

            // Reading the frame State in composition keeps totalLines in sync with the emulator.
            val totalLines = remember(frameState.value, cols, rows) { emulator.readLocked { emulator.totalLines() } }
            val canvasHeight = (totalLines * lineH).coerceAtLeast(viewportPx)
            val rowCache = remember(mono, widthPx, defaultFg, defaultBg) {
                RowLayoutCache { annotated, maxW, maxH ->
                    // Explicit positive Constraints — Size.Unspecified produced NaN and crashed the draw.
                    measurer.measure(
                        text = annotated,
                        style = mono,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Clip,
                        softWrap = false,
                        maxLines = 1,
                        constraints = Constraints(maxWidth = maxW, maxHeight = maxH),
                    )
                }
            }

            Box(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .combinedClickable(
                        onClick = {
                            focusRequester.requestFocus()
                            keyboard?.show()
                        },
                        onLongClick = { showMenu = true },
                    ),
            ) {
                Canvas(
                    Modifier
                        .fillMaxWidth()
                        .height(with(density) { canvasHeight.toDp() }),
                ) {
                    // Read the frame state so the canvas redraws on new output, and scrollState.value
                    // below so it redraws while scrolling.
                    @Suppress("UNUSED_EXPRESSION") frameState.value
                    val first = (scrollState.value / lineH).toInt().coerceAtLeast(0)
                    val last = (first + rows + 1).coerceAtMost(totalLines)
                    val maxWidthPx = widthPx.toInt().coerceAtLeast(1)
                    val maxHeightPx = lineH.toInt().coerceAtLeast(1)
                    emulator.readLocked {
                        for (index in first until last) {
                            val cells = emulator.screenLine(index) ?: continue
                            val isCursorRow = index == emulator.cursorRow
                            val layout = rowCache.layout(
                                index = index,
                                cells = cells,
                                cursorCol = if (isCursorRow) emulator.cursorCol else -1,
                                showCursor = isCursorRow && emulator.isCursorVisible,
                                defaultFg = defaultFg,
                                defaultBg = defaultBg,
                                maxWidth = maxWidthPx,
                                maxHeight = maxHeightPx,
                            )
                            drawText(
                                textLayoutResult = layout,
                                topLeft = androidx.compose.ui.geometry.Offset(0f, index * lineH),
                            )
                        }
                    }
                    rowCache.prune(first - 32, last + 32)
                }
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("Copy visible screen") },
                        onClick = {
                            showMenu = false
                            val first = (scrollState.value / lineH).toInt().coerceAtLeast(0)
                            val text = emulator.readLocked { emulator.linesText(first, rows) }
                            copyToClipboard(context, clipboard, text)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Copy all") },
                        onClick = {
                            showMenu = false
                            copyToClipboard(context, clipboard, emulator.readLocked { emulator.snapshotText() })
                        },
                    )
                }
            }

            if (!following) {
                FloatingActionButton(
                    onClick = { scope.launch { scrollState.scrollTo(Int.MAX_VALUE) } },
                    modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                ) { Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Scroll to bottom") }
            }
        }

        KeyRow(
            ctrl = ctrl,
            onCtrlToggle = { ctrl = !ctrl },
            onKey = { key -> vm.send(key); if (key.contains('\r')) input = "" },
        )

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
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (ui.connected) "tap terminal to type" else ui.status,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = {
                    val text = clipboard.getText()?.text
                    if (!text.isNullOrEmpty()) vm.send(text)
                }) { Text("Paste") }
                TextButton(onClick = {
                    focusRequester.requestFocus()
                    keyboard?.show()
                }) {
                    Icon(Icons.Filled.Keyboard, contentDescription = "Show keyboard")
                    Spacer(Modifier.width(4.dp))
                    Text("Keyboard")
                }
                TextButton(onClick = { vm.detach() }) { Text("Detach") }
            }
        }

        // Invisible capture field: keys go straight to the terminal (the shell echoes them).
        BasicTextField(
            value = input,
            onValueChange = { new ->
                when {
                    new.length > input.length -> {
                        val added = new.substring(input.length)
                        vm.send(if (ctrl) toCtrl(added) else added)
                        ctrl = false
                    }
                    new.length < input.length -> vm.send("\u007f".repeat(input.length - new.length))
                }
                input = new
            },
            keyboardOptions = KeyboardOptions(
                imeAction = ImeAction.Send,
                keyboardType = KeyboardType.Ascii,
                autoCorrectEnabled = false,
            ),
            keyboardActions = KeyboardActions(onSend = { vm.send("\r"); input = "" }),
            singleLine = true,
            cursorBrush = androidx.compose.ui.graphics.SolidColor(Color.Transparent),
            modifier = Modifier
                .size(1.dp)
                .alpha(0f)
                .focusRequester(focusRequester),
        )
    }
}

private fun copyToClipboard(
    context: android.content.Context,
    clipboard: androidx.compose.ui.platform.ClipboardManager,
    text: String,
) {
    if (text.isBlank()) return
    clipboard.setText(AnnotatedString(text))
    Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
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
        TerminalKey("⌫") { onKey("\u007f") }
        TerminalKey("␣") { onKey(" ") }
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

private fun rowAnnotated(
    cells: List<Cell>,
    cursorCol: Int,
    showCursor: Boolean,
    defaultFg: Color,
    defaultBg: Color,
): AnnotatedString = buildAnnotatedString {
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

/**
 * Per-row layout cache: rebuilding a styled [AnnotatedString] and re-measuring it for every visible
 * row on every frame was the main source of terminal jank. A row is only re-laid-out when its cells
 * or cursor state actually change; the check compares the raw cell attributes (no allocation).
 */
private class RowLayoutCache(
    private val measure: (AnnotatedString, Int, Int) -> TextLayoutResult,
) {
    private class Entry(
        val ch: CharArray,
        val fg: IntArray,
        val bg: IntArray,
        val attr: IntArray,
    ) {
        var cursorCol = Int.MIN_VALUE
        var showCursor = false
        var layout: TextLayoutResult? = null

        fun matches(cells: List<Cell>): Boolean {
            val size = ch.size
            if (cells.size != size) return false
            for (i in 0 until size) {
                val c = cells[i]
                if (ch[i] != c.ch || fg[i] != c.fg || bg[i] != c.bg || attr[i] != c.attr) return false
            }
            return true
        }

        fun copyFrom(cells: List<Cell>) {
            for (i in ch.indices) {
                val c = cells[i]
                ch[i] = c.ch; fg[i] = c.fg; bg[i] = c.bg; attr[i] = c.attr
            }
        }
    }

    private val entries = HashMap<Int, Entry>()

    fun layout(
        index: Int,
        cells: List<Cell>,
        cursorCol: Int,
        showCursor: Boolean,
        defaultFg: Color,
        defaultBg: Color,
        maxWidth: Int,
        maxHeight: Int,
    ): TextLayoutResult {
        val cached = entries[index]
        if (cached?.layout != null && cached.cursorCol == cursorCol &&
            cached.showCursor == showCursor && cached.matches(cells)
        ) {
            return cached.layout!!
        }
        val entry = cached?.takeIf { it.ch.size == cells.size }
            ?: Entry(CharArray(cells.size), IntArray(cells.size), IntArray(cells.size), IntArray(cells.size))
        entry.copyFrom(cells)
        entry.cursorCol = cursorCol
        entry.showCursor = showCursor
        entry.layout = measure(rowAnnotated(cells, cursorCol, showCursor, defaultFg, defaultBg), maxWidth, maxHeight)
        entries[index] = entry
        return entry.layout!!
    }

    /** Drop rows that scrolled far out of the viewport so the cache can't grow without bound. */
    fun prune(lo: Int, hi: Int) {
        if (entries.size <= 128) return
        entries.entries.removeAll { it.key < lo || it.key > hi }
    }
}

