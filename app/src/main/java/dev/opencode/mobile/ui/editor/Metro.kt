package dev.opencode.mobile.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.outlined.Subway
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.opencode.mobile.data.net.HelperManager
import dev.opencode.mobile.data.net.HelperState
import dev.opencode.mobile.ui.theme.OcTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

enum class MetroLevel { INFO, WARN, ERROR }

/** [frame]: a code-frame line under an error/warning — same colour, not another problem. */
data class MetroLine(val seq: Long, val level: MetroLevel, val text: String, val frame: Boolean = false)

data class MetroUi(
    /** stopped · starting · running · external · exited (helper's words). */
    val state: String = "stopped",
    val command: String? = null,
    /** What Start would run, or why it can't ([detectError]). */
    val detected: String? = null,
    val detectError: String? = null,
    val port: Int = 8081,
    val exitCode: Int? = null,
    val lines: List<MetroLine> = emptyList(),
    /** Errors that arrived since the panel was last looked at. */
    val unseenErrors: Int = 0,
    val busy: Boolean = false,
    val message: String? = null,
) {
    val running: Boolean get() = state == "starting" || state == "running" || state == "external"
}

/**
 * Metro for the editor's project, through the Mac helper (`metro.*`). The helper owns the process, so
 * this only mirrors it: `metro.status` catches up (state + recent log), `metro.log` / `metro.status`
 * notifications keep it live.
 */
class MetroController(
    private val scope: CoroutineScope,
    private val helper: HelperManager,
    private val projectRoot: suspend () -> String?,
) {
    private val _ui = MutableStateFlow(MetroUi())
    val ui: StateFlow<MetroUi> = _ui
    @Volatile private var root: String? = null
    var panelVisible = false
        set(value) {
            field = value
            if (value) _ui.update { it.copy(unseenErrors = 0) }
        }

    init {
        scope.launch {
            helper.state.collect { state ->
                if (state is HelperState.Ready && "metro" in state.info.features) refresh()
            }
        }
        scope.launch {
            helper.notifications.collect { msg ->
                val method = (msg["method"] as? JsonPrimitive)?.content ?: return@collect
                if (!method.startsWith("metro.")) return@collect
                val params = msg["params"] as? JsonObject ?: return@collect
                if (params.str("root") != root) return@collect
                when (method) {
                    "metro.log" -> onLog(params)
                    "metro.status" -> _ui.update { ui ->
                        val next = applyStatus(ui, params)
                        // A new run: the helper started a fresh log, so drop the old one here too.
                        if (next.state == "starting" && ui.state != "starting") next.copy(lines = emptyList(), unseenErrors = 0) else next
                    }
                }
            }
        }
    }

    fun refresh() {
        scope.launch {
            val r = root ?: projectRoot()?.let(::normalize)?.also { root = it } ?: return@launch
            runCatching {
                val result = helper.request("metro.status", buildJsonObject { put("root", r); put("tail", 500) }).jsonObject
                _ui.update { applyStatus(it, result).copy(lines = result["lines"]?.let(::parseLines) ?: emptyList()) }
            }.onFailure { e -> _ui.update { it.copy(message = e.message) } }
        }
    }

    fun start() = call("metro.start", "Couldn't start Metro")
    fun stop() = call("metro.stop", "Couldn't stop Metro")
    fun reload() = call("metro.reload", "Couldn't reload")
    fun clear() = _ui.update { it.copy(lines = emptyList(), unseenErrors = 0) }
    fun dismissMessage() = _ui.update { it.copy(message = null) }

    private fun call(method: String, failure: String) {
        scope.launch {
            val r = root ?: projectRoot()?.let(::normalize)?.also { root = it }
            if (r == null) { _ui.update { it.copy(message = "Pick a project folder first") }; return@launch }
            _ui.update { it.copy(busy = true, message = null) }
            try {
                val result = helper.request(method, buildJsonObject { put("root", r) }, timeoutMs = 15_000)
                (result as? JsonObject)?.let { obj -> if (obj["state"] != null) _ui.update { applyStatus(it, obj) } }
            } catch (e: Exception) {
                _ui.update { it.copy(message = "$failure: ${e.message}") }
            } finally {
                _ui.update { it.copy(busy = false) }
            }
        }
    }

    private fun onLog(params: JsonObject) {
        val line = params["line"]?.let { parseLine(it) } ?: return
        val replace = (params["replace"] as? JsonPrimitive)?.booleanOrNull == true
        _ui.update { ui ->
            val lines = if (replace && ui.lines.lastOrNull()?.seq == line.seq) ui.lines.dropLast(1) + line else ui.lines + line
            ui.copy(
                lines = if (lines.size > MAX_LINES) lines.takeLast(MAX_LINES) else lines,
                unseenErrors = if (line.level == MetroLevel.ERROR && !line.frame && !panelVisible) ui.unseenErrors + 1 else ui.unseenErrors,
            )
        }
    }

    private fun applyStatus(ui: MetroUi, o: JsonObject) = ui.copy(
        state = o.str("state") ?: ui.state,
        command = o.str("command"),
        detected = if (o.containsKey("detected")) o.str("detected") else ui.detected,
        detectError = if (o.containsKey("detectError")) o.str("detectError") else ui.detectError,
        port = (o["port"] as? JsonPrimitive)?.intOrNull ?: ui.port,
        exitCode = (o["exitCode"] as? JsonPrimitive)?.intOrNull,
    )

    private fun parseLines(e: JsonElement) = runCatching { e.jsonArray.mapNotNull { parseLine(it) } }.getOrDefault(emptyList())

    private fun parseLine(e: JsonElement): MetroLine? = runCatching {
        val o = e.jsonObject
        MetroLine(
            seq = o["seq"]?.jsonPrimitive?.longOrNull ?: 0,
            level = when (o.str("level")) { "error" -> MetroLevel.ERROR; "warn" -> MetroLevel.WARN; else -> MetroLevel.INFO },
            text = o.str("text").orEmpty(),
            frame = (o["frame"] as? JsonPrimitive)?.booleanOrNull == true,
        )
    }.getOrNull()

    /** The helper reports roots `path.resolve`d (no trailing slash). */
    private fun normalize(path: String) = path.trimEnd('/').ifEmpty { "/" }

    private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    companion object {
        const val MAX_LINES = 2000

        fun available(state: HelperState) = "metro" in HelperManager.features(state)
    }
}

/** Metro bottom-sheet for phones (⋮ → Metro). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MetroSheet(metro: MetroController, onDismiss: () -> Unit) {
    // Fully expanded: half-open, the log's last rows (where it follows the tail) sit below the screen.
    val state = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state, containerColor = OcTheme.colors.surface) {
        MetroPanel(metro = metro, modifier = Modifier.fillMaxWidth().heightIn(min = 320.dp, max = 640.dp))
    }
}

/** Status + Start/Stop/Reload, then the log with errors highlighted. */
@Composable
fun MetroPanel(metro: MetroController, modifier: Modifier = Modifier) {
    val ui by metro.ui.collectAsState()
    val c = OcTheme.colors
    var errorsOnly by rememberSaveable { mutableStateOf(false) }
    androidx.compose.runtime.DisposableEffect(Unit) {
        metro.panelVisible = true
        onDispose { metro.panelVisible = false }
    }
    LaunchedEffect(Unit) { metro.refresh() }
    val shown = remember(ui.lines, errorsOnly) { if (errorsOnly) ui.lines.filter { it.level != MetroLevel.INFO } else ui.lines }
    val list = rememberLazyListState()
    // Follow the tail until the user scrolls up; scrolling back to the bottom resumes following.
    var follow by remember { mutableStateOf(true) }
    LaunchedEffect(list) {
        // drop(1): the initial "not scrolling" isn't the user leaving the bottom.
        androidx.compose.runtime.snapshotFlow { list.isScrollInProgress }.drop(1).collect { scrolling ->
            if (!scrolling) {
                val info = list.layoutInfo
                follow = info.visibleItemsInfo.lastOrNull()?.let { it.index >= info.totalItemsCount - 1 } ?: true
            }
        }
    }
    LaunchedEffect(shown.size, shown.lastOrNull()?.text, follow) { if (follow && shown.isNotEmpty()) list.scrollToItem(shown.size - 1) }

    androidx.compose.foundation.layout.BoxWithConstraints(modifier) {
    val oneRow = maxWidth >= 640.dp
    val filters: @Composable () -> Unit = {
        FilterChipText("All", !errorsOnly) { errorsOnly = false }
        val problems = ui.lines.count { it.level != MetroLevel.INFO && !it.frame }
        FilterChipText(if (problems > 0) "Problems ($problems)" else "Problems", errorsOnly) { errorsOnly = true }
        Text("Clear", fontSize = 12.sp, color = c.textSecondary, modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { metro.clear() }.padding(horizontal = 8.dp, vertical = 10.dp))
    }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val (dot, label) = when (ui.state) {
                "running" -> c.green to "Running · :${ui.port}"
                "starting" -> c.warning to "Starting · :${ui.port}"
                "external" -> c.green to "Running outside the app · :${ui.port}"
                "exited" -> c.errorText to (ui.exitCode?.let { "Exited ($it)" } ?: "Exited")
                else -> c.textSecondary to "Stopped"
            }
            Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
            Column(Modifier.weight(1f)) {
                Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = c.textPrimary, maxLines = 1)
                val sub = ui.command ?: ui.detected ?: ui.detectError
                if (sub != null) Text(sub, fontSize = 11.sp, color = if (ui.detected == null && ui.command == null) c.errorText else c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis, fontFamily = FontFamily.Monospace)
            }
            if (oneRow) filters()
            if (ui.running) {
                TextButton(onClick = { metro.reload() }, enabled = !ui.busy) { Text("Reload") }
                TextButton(onClick = { metro.stop() }, enabled = !ui.busy && ui.state != "external") { Text("Stop") }
            } else {
                TextButton(onClick = { metro.start() }, enabled = !ui.busy && ui.detected != null) { Text("Start") }
            }
        }
        if (!oneRow) Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            filters()
        }
        ui.message?.let { message ->
            Text(
                message,
                color = c.errorText,
                fontSize = 12.sp,
                modifier = Modifier.fillMaxWidth().background(c.errorBg).clickable { metro.dismissMessage() }.padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
        Box(Modifier.weight(1f).fillMaxWidth().background(c.bg)) {
            if (shown.isEmpty()) {
                Text(
                    when {
                        errorsOnly -> "No warnings or errors."
                        ui.running -> "Waiting for Metro output…"
                        ui.detectError != null -> "Metro needs a React Native or Expo project. Pick that folder (Sessions → folder) to start it from here."
                        else -> "Metro isn't running. Start runs ${ui.detected ?: "the bundler"} in the project folder on the Mac."
                    },
                    fontSize = 12.sp,
                    color = c.textSecondary,
                    modifier = Modifier.padding(12.dp),
                )
            } else {
                LazyColumn(state = list, modifier = Modifier.fillMaxSize()) {
                    items(shown, key = { it.seq }) { line -> LogLineRow(line) }
                }
            }
        }
    }
    }
}

/** One log line (Metro, build output, logcat): errors on errorBg, warnings in the warning colour. */
@Composable
fun LogLineRow(line: MetroLine) {
    val c = OcTheme.colors
    val (fg, bg) = when (line.level) {
        MetroLevel.ERROR -> c.errorText to c.errorBg
        MetroLevel.WARN -> c.warning to androidx.compose.ui.graphics.Color.Transparent
        MetroLevel.INFO -> c.textBody to androidx.compose.ui.graphics.Color.Transparent
    }
    Text(
        line.text,
        color = fg,
        fontSize = 11.sp,
        lineHeight = 15.sp,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier.fillMaxWidth().background(bg).padding(horizontal = 12.dp, vertical = 1.dp),
    )
}

@Composable
internal fun FilterChipText(label: String, selected: Boolean, onClick: () -> Unit) {
    val c = OcTheme.colors
    Text(
        label,
        fontSize = 12.sp,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        color = if (selected) c.textPrimary else c.textSecondary,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) c.surface else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 10.dp),
    )
}

/** Running dot, or the count of errors not yet seen, next to the Metro tab label / menu item. */
@Composable
fun MetroTabBadge(ui: MetroUi) {
    val c = OcTheme.colors
    when {
        ui.unseenErrors > 0 -> Text(
            if (ui.unseenErrors > 99) "99+" else ui.unseenErrors.toString(),
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = c.errorText,
            modifier = Modifier.padding(start = 6.dp).clip(RoundedCornerShape(8.dp)).background(c.errorBg).padding(horizontal = 5.dp, vertical = 1.dp),
        )
        ui.running -> Box(Modifier.padding(start = 6.dp).size(6.dp).clip(CircleShape).background(if (ui.state == "starting") c.warning else c.green))
    }
}

/** Phone app-bar button that opens the Metro sheet; only there while the helper offers Metro. */
@Composable
fun MetroAction(appVm: dev.opencode.mobile.AppViewModel, vm: EditorViewModel) {
    val helperState by appVm.helper.collectAsState()
    if (!MetroController.available(helperState)) return
    val ui by vm.metro.ui.collectAsState()
    var open by rememberSaveable { mutableStateOf(false) }
    androidx.compose.material3.IconButton(onClick = { open = true }) {
        Box {
            androidx.compose.material3.Icon(
                androidx.compose.material.icons.Icons.Outlined.Subway,
                contentDescription = "Metro",
                tint = if (ui.unseenErrors > 0) OcTheme.colors.errorText else androidx.compose.material3.LocalContentColor.current,
            )
            if (ui.unseenErrors > 0 || ui.running) {
                Box(
                    Modifier.align(Alignment.TopEnd).size(8.dp).clip(CircleShape)
                        .background(if (ui.unseenErrors > 0) OcTheme.colors.errorText else if (ui.state == "starting") OcTheme.colors.warning else OcTheme.colors.green),
                )
            }
        }
    }
    if (open) MetroSheet(metro = vm.metro, onDismiss = { open = false })
}
