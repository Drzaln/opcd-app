package dev.opencode.mobile.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.net.Inet4Address
import java.net.NetworkInterface

data class AdbDevice(val serial: String, val state: String, val model: String?, val transport: String) {
    val ready: Boolean get() = state == "device"
    val label: String get() = when (transport) {
        "emulator" -> "Emulator ${serial.removePrefix("emulator-")}"
        "wifi" -> "${model ?: "Device"} · Wi-Fi"
        else -> "${model ?: serial} · USB"
    }
}

data class Avd(val name: String, val serial: String?)

/** How "Start emulator" launches an AVD (remembered for the session). */
data class EmuOptions(val headless: Boolean = true, val cold: Boolean = false, val ram: Int = 1536, val slim: Boolean = true)

enum class LogcatMode(val wire: String, val label: String) { APP("app", "App JS"), ERRORS("errors", "Errors"), ALL("all", "All") }

data class AndroidUi(
    val devices: List<AdbDevice> = emptyList(),
    val selected: String? = null,
    val adbMissing: Boolean = false,
    /** idle · running · succeeded · failed · stopped (helper's words). */
    val runState: String = "idle",
    val runCommand: String? = null,
    val runDetected: String? = null,
    val runDetectError: String? = null,
    val runLines: List<MetroLine> = emptyList(),
    val logcatMode: LogcatMode = LogcatMode.APP,
    val logcatLines: List<MetroLine> = emptyList(),
    val logcatSkipped: Int = 0,
    val unseenErrors: Int = 0,
    val busy: Boolean = false,
    val message: String? = null,
    val info: String? = null,
    /** AVDs on the Mac (name → serial when running) and whether starts go through avdslim. */
    val avds: List<Avd> = emptyList(),
    val avdslim: Boolean = false,
    /** AVD name → starting / booted / failed while an emulator start is in flight. */
    val emuStates: Map<String, String> = emptyMap(),
) {
    val device: AdbDevice? get() = devices.firstOrNull { it.serial == selected }
    val running: Boolean get() = runState == "running"
}

/**
 * The Mac's adb through the helper: devices + wireless connect/pair, "build & run" (helper-owned, per
 * project, mirrored here like Metro) and logcat (a stream this panel opens while it's on screen).
 */
class AndroidController(
    private val scope: CoroutineScope,
    private val helper: HelperManager,
    private val projectRoot: suspend () -> String?,
) {
    private val _ui = MutableStateFlow(AndroidUi())
    val ui: StateFlow<AndroidUi> = _ui
    @Volatile private var root: String? = null
    @Volatile private var logcatId: String? = null
    /** Lines that arrived before `logcat.start` replied with their id (the backlog races the reply). */
    private val early = java.util.concurrent.ConcurrentHashMap<String, MutableList<MetroLine>>()
    private var logcatSeq = 0L
    var panelVisible = false
        set(value) {
            field = value
            if (value) _ui.update { it.copy(unseenErrors = 0) }
        }

    init {
        scope.launch {
            helper.state.collect { state -> if (available(state)) refresh() else logcatId = null }
        }
        scope.launch {
            helper.notifications.collect { msg ->
                val method = (msg["method"] as? JsonPrimitive)?.content ?: return@collect
                val params = msg["params"] as? JsonObject ?: return@collect
                when (method) {
                    "run.log" -> if (params.str("root") == root) params["line"]?.let(::parseLine)?.let { line ->
                        _ui.update { it.copy(runLines = (it.runLines + line).takeLast(MAX), unseenErrors = it.unseenErrors + if (line.level == MetroLevel.ERROR && !panelVisible) 1 else 0) }
                    }
                    "run.status" -> if (params.str("root") == root) _ui.update { ui ->
                        val next = applyRun(ui, params)
                        if (next.runState == "running" && ui.runState != "running") next.copy(runLines = emptyList()) else next
                    }
                    "logcat.lines" -> {
                        val id = params.str("id") ?: return@collect
                        val lines = runCatching { params["lines"]!!.jsonArray.map { e ->
                            val o = e.jsonObject
                            MetroLine(++logcatSeq, level(o.str("level")), compactLogcat(o.str("text").orEmpty()))
                        } }.getOrDefault(emptyList())
                        val skipped = (params["skipped"] as? JsonPrimitive)?.intOrNull ?: 0
                        if (id == logcatId) _ui.update { it.copy(logcatLines = (it.logcatLines + lines).takeLast(MAX), logcatSkipped = it.logcatSkipped + skipped) }
                        else if (early.size < 8) early.getOrPut(id) { mutableListOf() }.addAll(lines)
                    }
                    "emu.log" -> {
                        val avd = params.str("avd") ?: return@collect
                        val text = params.str("text") ?: return@collect
                        if (_ui.value.emuStates[avd] == "starting") _ui.update { it.copy(info = "$avd: $text") }
                    }
                    "emu.status" -> {
                        val avd = params.str("avd") ?: return@collect
                        val state = params.str("state") ?: return@collect
                        _ui.update { it.copy(
                            emuStates = it.emuStates + (avd to state),
                            info = when (state) { "booted" -> "$avd is up"; "failed" -> null; else -> it.info },
                            message = if (state == "failed") "$avd didn't start: ${params.str("message") ?: "see the Mac"}" else it.message,
                        ) }
                        if (state == "booted") { pendingSelect = avd; refresh() }
                    }
                    "logcat.exit" -> if (params.str("id") == logcatId) {
                        logcatId = null
                        _ui.update { it.copy(info = "Logcat stopped (device gone?)") }
                    }
                }
            }
        }
    }

    private suspend fun root(): String? = root ?: projectRoot()?.trimEnd('/')?.ifEmpty { "/" }?.also { root = it }

    fun refresh() {
        scope.launch {
            runCatching {
                val res = helper.request("adb.devices").jsonObject
                val devices = res["devices"]?.jsonArray?.map { e ->
                    val o = e.jsonObject
                    AdbDevice(o.str("serial").orEmpty(), o.str("state").orEmpty(), o.str("model"), o.str("transport") ?: "usb")
                } ?: emptyList()
                val emu = runCatching { helper.request("emu.list").jsonObject }.getOrNull()
                val avds = emu?.get("avds")?.jsonArray?.map { e -> val o = e.jsonObject; Avd(o.str("name").orEmpty(), o.str("serial")) } ?: emptyList()
                val booted = pendingSelect?.let { name -> avds.firstOrNull { it.name == name }?.serial }
                if (booted != null) pendingSelect = null
                _ui.update { ui ->
                    val keep = booted ?: ui.selected?.takeIf { s -> devices.any { it.serial == s } }
                    ui.copy(devices = devices, avds = avds, avdslim = (emu?.get("avdslim") as? JsonPrimitive)?.content == "true", adbMissing = res["adb"] !is JsonPrimitive || (res["adb"] as JsonPrimitive).contentOrNull == null,
                        selected = keep ?: devices.firstOrNull { it.ready }?.serial)
                }
            }.onFailure { e -> _ui.update { it.copy(message = e.message) } }
            val r = root() ?: return@launch
            runCatching {
                val res = helper.request("run.status", buildJsonObject { put("root", r); put("tail", 500) }).jsonObject
                _ui.update { applyRun(it, res).copy(runLines = res["lines"]?.let(::parseLines) ?: emptyList()) }
            }
        }
    }

    @Volatile private var pendingSelect: String? = null
    var emuOptions = EmuOptions()

    /** Starts an AVD on the Mac (avdslim when installed); selects it once it has booted. */
    fun startEmulator(avd: String, options: EmuOptions) {
        emuOptions = options
        scope.launch {
            _ui.update { it.copy(message = null, info = "Starting $avd…", emuStates = it.emuStates + (avd to "starting")) }
            try {
                helper.request("emu.start", buildJsonObject {
                    put("avd", avd); put("headless", options.headless); put("cold", options.cold); put("ram", options.ram); put("slim", options.slim)
                }, timeoutMs = 30_000)
            } catch (e: Exception) {
                _ui.update { it.copy(message = e.message, info = null, emuStates = it.emuStates - avd) }
            }
        }
    }

    fun stopEmulator(serial: String) {
        scope.launch {
            _ui.update { it.copy(message = null, info = "Stopping $serial…") }
            runCatching { helper.request("emu.stop", buildJsonObject { put("serial", serial) }, timeoutMs = 70_000) }
                .onSuccess { _ui.update { it.copy(info = "Stopped $serial") } }
                .onFailure { e -> _ui.update { it.copy(message = e.message, info = null) } }
            refresh()
        }
    }

    fun select(serial: String) {
        if (serial == _ui.value.selected) return
        _ui.update { it.copy(selected = serial, logcatLines = emptyList(), logcatSkipped = 0) }
    }

    fun run() = withRoot("Couldn't start the build") { r ->
        val serial = _ui.value.device?.takeIf { it.ready }?.serial ?: throw IllegalStateException("Pick a connected device")
        val res = helper.request("run.start", buildJsonObject { put("root", r); put("serial", serial) }, timeoutMs = 30_000).jsonObject
        _ui.update { applyRun(it, res) }
    }

    fun stopRun() = withRoot("Couldn't stop") { r ->
        (helper.request("run.stop", buildJsonObject { put("root", r) }) as? JsonObject)?.let { res -> _ui.update { applyRun(it, res) } }
    }

    fun connect(host: String, port: Int, pairPort: Int?, code: String?) {
        scope.launch {
            _ui.update { it.copy(busy = true, message = null, info = null) }
            try {
                if (pairPort != null && !code.isNullOrBlank()) {
                    helper.request("adb.pair", buildJsonObject { put("host", host); put("port", pairPort); put("code", code) }, timeoutMs = 40_000)
                }
                val out = helper.request("adb.connect", buildJsonObject { put("host", host); put("port", port) }, timeoutMs = 30_000)
                _ui.update { it.copy(info = out.jsonObject.str("output")) }
                refresh()
            } catch (e: Exception) {
                _ui.update { it.copy(message = e.message) }
            } finally {
                _ui.update { it.copy(busy = false) }
            }
        }
    }

    /** Opens a logcat stream for the selected device (the panel calls this while Logcat is on screen). */
    fun startLogcat(mode: LogcatMode) {
        val serial = _ui.value.device?.takeIf { it.ready }?.serial ?: return
        stopLogcat()
        _ui.update { it.copy(logcatLines = emptyList(), logcatSkipped = 0, info = null) }
        scope.launch {
            runCatching {
                val res = helper.request("logcat.start", buildJsonObject { put("serial", serial); put("mode", mode.wire) })
                val id = res.jsonObject.str("id")
                logcatId = id
                val waiting = id?.let { early.remove(it) }.orEmpty()
                early.clear()
                if (waiting.isNotEmpty()) _ui.update { it.copy(logcatLines = (waiting + it.logcatLines).takeLast(MAX)) }
            }.onFailure { e -> _ui.update { it.copy(message = e.message) } }
        }
    }

    /** The panel's effect restarts the stream when the mode changes. */
    fun setLogcatMode(mode: LogcatMode) = _ui.update { it.copy(logcatMode = mode) }

    fun stopLogcat() {
        val id = logcatId ?: return
        logcatId = null
        helper.send("logcat.stop", buildJsonObject { put("id", id) })
    }

    fun clearLogcat() = _ui.update { it.copy(logcatLines = emptyList(), logcatSkipped = 0) }
    fun dismiss() = _ui.update { it.copy(message = null, info = null) }

    private fun withRoot(failure: String, block: suspend (String) -> Unit) {
        scope.launch {
            val r = root()
            if (r == null) { _ui.update { it.copy(message = "Pick a project folder first") }; return@launch }
            _ui.update { it.copy(busy = true, message = null) }
            try { block(r) } catch (e: Exception) { _ui.update { it.copy(message = "$failure: ${e.message}") } } finally { _ui.update { it.copy(busy = false) } }
        }
    }

    private fun applyRun(ui: AndroidUi, o: JsonObject) = ui.copy(
        runState = o.str("state") ?: ui.runState,
        runCommand = if (o.containsKey("command")) o.str("command") else ui.runCommand,
        runDetected = if (o.containsKey("detected")) o.str("detected") else ui.runDetected,
        runDetectError = if (o.containsKey("detectError")) o.str("detectError") else ui.runDetectError,
    )

    private fun parseLines(e: JsonElement) = runCatching { e.jsonArray.mapNotNull(::parseLine) }.getOrDefault(emptyList())
    private fun parseLine(e: JsonElement): MetroLine? = runCatching {
        val o = e.jsonObject
        MetroLine(o["seq"]?.jsonPrimitive?.longOrNull ?: 0, level(o.str("level")), o.str("text").orEmpty())
    }.getOrNull()
    private fun level(s: String?) = when (s) { "error" -> MetroLevel.ERROR; "warn" -> MetroLevel.WARN; else -> MetroLevel.INFO }
    private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    companion object {
        const val MAX = 3000
        private val THREADTIME = Regex("""^\d\d-\d\d (\d\d:\d\d:\d\d)\.\d+\s+\d+\s+\d+\s+([VDIWEF])\s+(.*)$""")

        /** "11-18 17:46:29.042  4561 16184 I ReactNativeJS: hi" → "17:46:29 I ReactNativeJS: hi" (phone width). */
        fun compactLogcat(line: String): String =
            THREADTIME.matchEntire(line)?.destructured?.let { (time, level, rest) -> "$time $level $rest" } ?: line
        fun available(state: HelperState) = "adb" in HelperManager.features(state)

        /** This phone's Tailscale IPv4 (100.64.0.0/10), to prefill "connect to this phone". */
        fun tailscaleIp(): String? = runCatching {
            NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .map { it.hostAddress.orEmpty() }
                .firstOrNull { ip -> ip.split('.').let { it.size == 4 && it[0] == "100" && (it[1].toIntOrNull() ?: 0) in 64..127 } }
        }.getOrNull()
    }
}

private enum class AndroidTab { RUN, LOGCAT, SCREEN }

/** Device row, then Run (build & install output) or Logcat. */
@Composable
fun AndroidPanel(
    android: AndroidController,
    modifier: Modifier = Modifier,
    /** The live screen, when the helper offers it and no other pane is already showing it. */
    screen: ScreenController? = null,
) {
    val ui by android.ui.collectAsState()
    val c = OcTheme.colors
    var tab by rememberSaveable { mutableStateOf(AndroidTab.RUN) }
    var connectOpen by remember { mutableStateOf(false) }
    var startAvd by remember { mutableStateOf<String?>(null) }
    if (screen == null && tab == AndroidTab.SCREEN) tab = AndroidTab.RUN
    var filter by rememberSaveable { mutableStateOf("") }
    DisposableEffect(Unit) {
        android.panelVisible = true
        onDispose { android.panelVisible = false; android.stopLogcat() }
    }
    LaunchedEffect(Unit) { android.refresh() }
    // Logcat runs only while it's on screen, for the selected device and mode.
    val logcatSerial = ui.device?.takeIf { it.ready }?.serial
    DisposableEffect(tab, logcatSerial, ui.logcatMode) {
        if (tab == AndroidTab.LOGCAT && logcatSerial != null) android.startLogcat(ui.logcatMode)
        onDispose { android.stopLogcat() }
    }

    BoxWithConstraints(modifier) {
        val wide = maxWidth >= 640.dp
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                DevicePicker(ui, onSelect = android::select, onStartAvd = { startAvd = it }, onStopEmulator = android::stopEmulator, modifier = Modifier.weight(1f, fill = false))
                IconButton(onClick = { android.refresh() }) { Icon(Icons.Outlined.Refresh, contentDescription = "Refresh devices", tint = c.textSecondary) }
                TextButton(onClick = { connectOpen = true }) { Text("Connect…") }
                Spacer(Modifier.weight(1f))
                if (wide) Tabs(tab, screen != null) { tab = it }
            }
            if (!wide) Row(Modifier.padding(horizontal = 8.dp)) { Tabs(tab, screen != null) { tab = it } }
            (ui.message ?: ui.info)?.let { text ->
                Text(
                    text,
                    color = if (ui.message != null) c.errorText else c.textBody,
                    fontSize = 12.sp,
                    modifier = Modifier.fillMaxWidth().background(if (ui.message != null) c.errorBg else c.surface).clickable { android.dismiss() }.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
            when {
                ui.adbMissing -> Empty("adb isn't installed on the Mac. Install Android platform-tools (Android Studio → SDK Manager), then refresh.")
                tab == AndroidTab.RUN -> RunView(android, ui)
                tab == AndroidTab.SCREEN && screen != null -> DeviceScreen(
                    screen = screen,
                    serial = ui.device?.takeIf { it.ready }?.serial,
                    deviceLabel = ui.device?.label,
                    isThisPhone = ui.device?.let { isThisPhone(it) } == true,
                    modifier = Modifier.fillMaxSize(),
                )
                else -> LogcatView(android, ui, filter) { filter = it }
            }
        }
    }
    startAvd?.let { avd ->
        StartEmulatorDialog(avd, ui.avdslim, android.emuOptions, onDismiss = { startAvd = null }, onStart = { opts -> startAvd = null; android.startEmulator(avd, opts) })
    }
    if (connectOpen) ConnectDialog(onDismiss = { connectOpen = false }, onConnect = { host, port, pairPort, code ->
        connectOpen = false
        android.connect(host, port, pairPort, code)
    })
}

@Composable
private fun Tabs(tab: AndroidTab, withScreen: Boolean, onTab: (AndroidTab) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        FilterChipText("Run", tab == AndroidTab.RUN) { onTab(AndroidTab.RUN) }
        FilterChipText("Logcat", tab == AndroidTab.LOGCAT) { onTab(AndroidTab.LOGCAT) }
        if (withScreen) FilterChipText("Screen", tab == AndroidTab.SCREEN) { onTab(AndroidTab.SCREEN) }
    }
}

@Composable
private fun DevicePicker(ui: AndroidUi, onSelect: (String) -> Unit, onStartAvd: (String) -> Unit, onStopEmulator: (String) -> Unit, modifier: Modifier = Modifier) {
    val c = OcTheme.colors
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        Row(
            Modifier.clip(RoundedCornerShape(8.dp)).clickable { open = true }.heightIn(min = 40.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val d = ui.device
            Box(Modifier.size(8.dp).clip(CircleShape).background(when { d == null -> c.textSecondary; d.ready -> c.green; else -> c.warning }))
            Spacer(Modifier.width(8.dp))
            Text(
                d?.let { if (it.ready) it.label else "${it.label} (${it.state})" } ?: if (ui.devices.isEmpty()) "No devices" else "Pick a device",
                fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Icon(Icons.Outlined.ArrowDropDown, contentDescription = null, tint = c.textSecondary)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (ui.devices.isEmpty()) DropdownMenuItem(text = { Text("No devices — plug one in or use Connect…") }, onClick = { open = false }, enabled = false)
            for (d in ui.devices) {
                DropdownMenuItem(
                    text = { Text(if (d.ready) d.label else "${d.label} (${d.state})") },
                    onClick = { open = false; onSelect(d.serial) },
                    enabled = d.ready,
                )
            }
            if (ui.avds.isNotEmpty()) {
                androidx.compose.material3.HorizontalDivider()
                Text(
                    if (ui.avdslim) "Emulators on the Mac · avdslim" else "Emulators on the Mac",
                    fontSize = 11.sp, color = c.textSecondary, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
                for (avd in ui.avds) {
                    val starting = ui.emuStates[avd.name] == "starting"
                    if (avd.serial != null) {
                        DropdownMenuItem(text = { Text("Stop ${avd.name}") }, onClick = { open = false; onStopEmulator(avd.serial) })
                    } else {
                        DropdownMenuItem(
                            text = { Text(if (starting) "${avd.name} — starting…" else "Start ${avd.name}…") },
                            onClick = { open = false; onStartAvd(avd.name) },
                            enabled = !starting,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RunView(android: AndroidController, ui: AndroidUi) {
    val c = OcTheme.colors
    Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        val (dot, label) = when (ui.runState) {
            "running" -> c.warning to "Building & installing…"
            "succeeded" -> c.green to "Installed and launched"
            "failed" -> c.errorText to "Build failed"
            "stopped" -> c.textSecondary to "Stopped"
            else -> c.textSecondary to "Build & run"
        }
        Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = c.textPrimary, maxLines = 1)
            val sub = ui.runCommand ?: ui.runDetected?.replace("<device>", ui.selected ?: "<device>") ?: ui.runDetectError
            if (sub != null) Text(sub, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = if (ui.runDetected == null && ui.runCommand == null) c.errorText else c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (ui.running) TextButton(onClick = { android.stopRun() }, enabled = !ui.busy) { Text("Stop") }
        else TextButton(onClick = { android.run() }, enabled = !ui.busy && ui.device?.ready == true && ui.runDetected != null) { Text("Run") }
    }
    LogList(
        lines = ui.runLines,
        empty = when {
            ui.runDetectError != null -> ui.runDetectError
            ui.device?.ready != true -> "Connect a device (USB on the Mac, an emulator, or Connect… for this phone over Wi-Fi), then Run builds the app and installs it."
            else -> "Run builds the app on the Mac and installs it on ${ui.device?.label}. Start Metro first for React Native / Expo."
        },
    )
}

@Composable
private fun LogcatView(android: AndroidController, ui: AndroidUi, filter: String, onFilter: (String) -> Unit) {
    val c = OcTheme.colors
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for (m in LogcatMode.values()) FilterChipText(m.label, ui.logcatMode == m) { android.setLogcatMode(m) }
        androidx.compose.foundation.text.BasicTextField(
            value = filter,
            onValueChange = onFilter,
            singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(color = c.textPrimary, fontSize = 12.sp),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(c.accent),
            modifier = Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(c.surface).padding(horizontal = 10.dp, vertical = 10.dp),
            decorationBox = { inner -> Box { if (filter.isEmpty()) Text("Filter", fontSize = 12.sp, color = c.textSecondary); inner() } },
        )
        Text("Clear", fontSize = 12.sp, color = c.textSecondary, modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { android.clearLogcat() }.padding(horizontal = 8.dp, vertical = 10.dp))
    }
    val shown = remember(ui.logcatLines, filter) { if (filter.isBlank()) ui.logcatLines else ui.logcatLines.filter { it.text.contains(filter, ignoreCase = true) } }
    if (ui.logcatSkipped > 0) Text("${ui.logcatSkipped} lines skipped (too fast) — try App JS or Errors", fontSize = 11.sp, color = c.warning, modifier = Modifier.padding(horizontal = 12.dp))
    LogList(lines = shown, empty = if (ui.device?.ready != true) "Pick a connected device to see its log." else "Waiting for log lines…")
}

@Composable
private fun LogList(lines: List<MetroLine>, empty: String) {
    val c = OcTheme.colors
    val list = rememberLazyListState()
    var follow by remember { mutableStateOf(true) }
    LaunchedEffect(list) {
        snapshotFlow { list.isScrollInProgress }.drop(1).collect { scrolling ->
            if (!scrolling) {
                val info = list.layoutInfo
                follow = info.visibleItemsInfo.lastOrNull()?.let { it.index >= info.totalItemsCount - 1 } ?: true
            }
        }
    }
    LaunchedEffect(lines.size, lines.lastOrNull()?.seq, follow) { if (follow && lines.isNotEmpty()) list.scrollToItem(lines.size - 1) }
    Box(Modifier.fillMaxSize().background(c.bg)) {
        if (lines.isEmpty()) Empty(empty)
        else LazyColumn(state = list, modifier = Modifier.fillMaxSize()) { items(lines, key = { it.seq }) { LogLineRow(it) } }
    }
}

@Composable
private fun Empty(text: String) = Text(text, fontSize = 12.sp, color = OcTheme.colors.textSecondary, modifier = Modifier.padding(12.dp))

/** adb connect (and optionally Android 11+ "pair with code" first). Host defaults to this phone. */
@Composable
private fun ConnectDialog(onDismiss: () -> Unit, onConnect: (String, Int, Int?, String?) -> Unit) {
    val mine = remember { AndroidController.tailscaleIp() }
    var host by rememberSaveable { mutableStateOf(mine.orEmpty()) }
    var port by rememberSaveable { mutableStateOf("5555") }
    var pairPort by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Connect a device") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (mine != null) "Host is this phone's Tailscale address. On the phone: Developer options → Wireless debugging (needs Wi-Fi). " +
                        "First time: tap \"Pair device with pairing code\" and enter its port and code below; then use the IP & port shown on the Wireless debugging screen."
                    else "Enter the device's IP. For Android 11+ wireless debugging, pair first with the code from Developer options → Wireless debugging.",
                    fontSize = 12.sp, color = OcTheme.colors.textSecondary,
                )
                OutlinedTextField(host, { host = it.trim() }, label = { Text("Host") }, singleLine = true)
                OutlinedTextField(port, { port = it.filter(Char::isDigit) }, label = { Text("Port") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(pairPort, { pairPort = it.filter(Char::isDigit) }, label = { Text("Pairing port") }, singleLine = true, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    OutlinedTextField(code, { code = it.filter(Char::isDigit).take(6) }, label = { Text("Code") }, singleLine = true, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConnect(host, port.toIntOrNull() ?: 5555, pairPort.toIntOrNull(), code.takeIf { it.length == 6 }) },
                enabled = host.isNotBlank() && port.isNotBlank(),
            ) { Text(if (code.length == 6 && pairPort.isNotBlank()) "Pair & connect" else "Connect") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Phone sheet (app-bar button). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AndroidSheet(android: AndroidController, onDismiss: () -> Unit, screen: ScreenController? = null) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state, containerColor = OcTheme.colors.surface) {
        AndroidPanel(android = android, screen = screen, modifier = Modifier.fillMaxWidth().heightIn(min = 360.dp, max = 680.dp))
    }
}

/** Phone app-bar button: build state dot / unseen error count. Only while the helper offers adb. */
@Composable
fun AndroidAction(appVm: dev.opencode.mobile.AppViewModel, vm: EditorViewModel) {
    val helperState by appVm.helper.collectAsState()
    if (!AndroidController.available(helperState)) return
    val ui by vm.android.ui.collectAsState()
    var open by rememberSaveable { mutableStateOf(false) }
    IconButton(onClick = { open = true }) {
        Box {
            Icon(Icons.Outlined.PhoneAndroid, contentDescription = "Android", tint = if (ui.unseenErrors > 0) OcTheme.colors.errorText else LocalContentColor.current)
            if (ui.unseenErrors > 0 || ui.running) {
                Box(Modifier.align(Alignment.TopEnd).size(8.dp).clip(CircleShape).background(if (ui.unseenErrors > 0) OcTheme.colors.errorText else OcTheme.colors.warning))
            }
        }
    }
    val screen = vm.screen.takeIf { ScreenController.available(helperState) }
    if (open) AndroidSheet(android = vm.android, onDismiss = { open = false }, screen = screen)
}

/** Badge next to the wide layout's Android tab. */
@Composable
fun AndroidTabBadge(ui: AndroidUi) {
    val c = OcTheme.colors
    when {
        ui.unseenErrors > 0 -> Text(
            if (ui.unseenErrors > 99) "99+" else ui.unseenErrors.toString(),
            fontSize = 10.sp, fontWeight = FontWeight.Bold, color = c.errorText,
            modifier = Modifier.padding(start = 6.dp).clip(RoundedCornerShape(8.dp)).background(c.errorBg).padding(horizontal = 5.dp, vertical = 1.dp),
        )
        ui.running -> Box(Modifier.padding(start = 6.dp).size(6.dp).clip(CircleShape).background(c.warning))
    }
}

/** adb reports the model with underscores; Build.MODEL has spaces. A USB/Wi-Fi device with our model is us. */
internal fun isThisPhone(d: AdbDevice): Boolean =
    d.transport != "emulator" && d.model != null && d.model.replace(' ', '_').equals(android.os.Build.MODEL.replace(' ', '_'), ignoreCase = true)

@Composable
private fun StartEmulatorDialog(avd: String, avdslim: Boolean, initial: EmuOptions, onDismiss: () -> Unit, onStart: (EmuOptions) -> Unit) {
    var headless by rememberSaveable { mutableStateOf(initial.headless) }
    var cold by rememberSaveable { mutableStateOf(initial.cold) }
    var slim by rememberSaveable { mutableStateOf(initial.slim) }
    var ram by rememberSaveable { mutableStateOf(initial.ram.toString()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Start $avd") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    if (avdslim) "Runs avdslim on the Mac: low-memory host flags, then slims the emulator after boot."
                    else "Runs the Android emulator on the Mac. (Install avdslim for a lighter emulator.)",
                    fontSize = 12.sp, color = OcTheme.colors.textSecondary,
                )
                SwitchRow("Headless (no window on the Mac)", headless) { headless = it }
                SwitchRow("Cold boot", cold) { cold = it }
                if (avdslim) SwitchRow("Slim after boot", slim) { slim = it }
                OutlinedTextField(ram, { ram = it.filter(Char::isDigit).take(5) }, label = { Text("RAM (MB)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            }
        },
        confirmButton = { TextButton(onClick = { onStart(EmuOptions(headless, cold, (ram.toIntOrNull() ?: 1536).coerceIn(1024, 8192), slim)) }) { Text("Start") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 14.sp, modifier = Modifier.weight(1f))
        androidx.compose.material3.Switch(checked = checked, onCheckedChange = onChange)
    }
}
