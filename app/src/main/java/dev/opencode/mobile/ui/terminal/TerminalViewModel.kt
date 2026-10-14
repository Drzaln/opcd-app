package dev.opencode.mobile.ui.terminal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.opencode.mobile.OpenCodeApp
import dev.opencode.mobile.data.model.PtyCreateBody
import dev.opencode.mobile.data.model.PtyInfo
import dev.opencode.mobile.data.model.PtyShell
import dev.opencode.mobile.data.model.PtySize
import dev.opencode.mobile.data.model.PtyUpdateBody
import dev.opencode.mobile.data.net.ServerConfig
import dev.opencode.mobile.terminal.OkHttpPtyTransport
import dev.opencode.mobile.terminal.PtyEvent
import dev.opencode.mobile.terminal.PtyTransport
import dev.opencode.mobile.terminal.TerminalEmulator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class TerminalViewModel(
    private val app: OpenCodeApp,
    private val server: ServerConfig,
    private val projectDir: () -> String?,
) : ViewModel() {

    data class UiState(
        val sessions: List<PtyInfo> = emptyList(),
        val active: PtyInfo? = null,
        val shells: List<PtyShell> = emptyList(),
        val connected: Boolean = false,
        val status: String = "idle",
        val loading: Boolean = true,
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui

    val emulator = TerminalEmulator()
    private val _frame = MutableStateFlow(0L)
    val frame: StateFlow<Long> = _frame

    // "xterm.js" renderer mode: PTY bytes are handed to the WebView instead of the Kotlin emulator.
    // The channel buffers while the WebView is still loading, so no data is lost on attach and the
    // consumer just drains backlog + live stream in order. Bounded: if it overflows we flag it and
    // the renderer re-attaches for a clean full replay.
    private val rawChannel = Channel<String>(capacity = 8192, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val rawOutput: ReceiveChannel<String> get() = rawChannel

    // Bumped on every attach: the raw renderer uses it to clear its own buffer before a replay lands
    // (the server replays the whole PTY from the start when we connect with cursor=null).
    private val _attachGeneration = MutableStateFlow(0)
    val attachGeneration: StateFlow<Int> = _attachGeneration

    @Volatile private var rawMode = false

    @Volatile private var rawDropped = false

    // PTY parsing is CPU work: keep it off the UI thread. Confined to one thread so the emulator
    // bytes are applied in order (the lock in TerminalEmulator keeps UI reads consistent).
    private val termDispatcher = Dispatchers.Default.limitedParallelism(1)

    private val api = app.repository.apiFor(server)
    @Volatile private var transport: PtyTransport? = null
    private var connectJob: Job? = null
    private var lastCursor: Long? = null

    init {
        refresh()
        loadShells()
    }

    /** Switches the output sink and re-attaches, so the new renderer gets a full replay. */
    fun setRawMode(raw: Boolean) {
        if (raw == rawMode) return
        rawMode = raw
        rawDropped = false
        drainRaw()
        _ui.value.active?.let { attach(it) }
    }

    /** True when the buffer overflowed since the last call — the renderer should re-attach. */
    fun consumeRawDropped(): Boolean {
        val dropped = rawDropped
        rawDropped = false
        return dropped
    }

    /** Reconnects the active PTY (full replay) — used when a renderer switches or lost buffered data. */
    fun reattach() {
        _ui.value.active?.let { attach(it) }
    }

    private fun drainRaw() {
        while (rawChannel.tryReceive().isSuccess) Unit
    }

    fun refresh() {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(loading = true)
            val list = runCatching { api.ptyList(projectDir()) }.getOrNull().orEmpty()
            _ui.value = _ui.value.copy(sessions = list, loading = false)
        }
    }

    private fun loadShells() {
        viewModelScope.launch {
            val shells = runCatching { api.ptyShells(projectDir()) }.getOrNull().orEmpty()
            _ui.value = _ui.value.copy(shells = shells.filter { it.acceptable })
        }
    }

    fun create(command: String? = null) {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(status = "creating…")
            // Login + interactive shell so the user's dotfiles (prompt, aliases, PATH) load.
            // No command → the server picks the default ($SHELL) itself.
            val created = runCatching {
                api.ptyCreate(
                    PtyCreateBody(
                        command = command,
                        args = if (command == null) null else listOf("-l"),
                        cwd = projectDir(),
                        title = command?.substringAfterLast('/')?.ifBlank { null },
                    ),
                    projectDir(),
                )
            }.getOrNull()
            if (created == null) {
                _ui.value = _ui.value.copy(status = "create failed")
                return@launch
            }
            refresh()
            attach(created)
        }
    }

    fun attach(pty: PtyInfo) {
        val previous = connectJob
        previous?.cancel()
        transport?.close()
        transport = null
        lastCursor = null
        _attachGeneration.value++
        _ui.value = _ui.value.copy(active = pty, status = "connecting…", connected = false)
        connectJob = viewModelScope.launch(termDispatcher) {
            // Wait out the old socket so its last chunk can't land after the reset below.
            previous?.join()
            emulator.reset()
            drainRaw()
            _frame.value = emulator.version
            try {
                val protocol = api.protocol()
                val ticket = api.ptyTicket(pty.id, projectDir())
                val t = OkHttpPtyTransport(server, pty.id, projectDir(), cursor = null, protocol = protocol, ticket = ticket)
                transport = t
                t.events.collect { event ->
                    when (event) {
                        is PtyEvent.Data -> {
                            if (rawMode) {
                                if (rawChannel.trySend(event.text).isFailure) rawDropped = true
                            } else {
                                emulator.write(event.text)
                                _frame.value = emulator.version
                            }
                            if (!_ui.value.connected) _ui.value = _ui.value.copy(connected = true, status = "connected")
                        }
                        is PtyEvent.Meta -> {
                            lastCursor = event.cursor
                        }
                        is PtyEvent.Closed -> {
                            _ui.value = _ui.value.copy(connected = false, status = "closed (${event.code})")
                        }
                        is PtyEvent.Failed -> {
                            _ui.value = _ui.value.copy(connected = false, status = event.message)
                        }
                    }
                }
            } catch (e: Exception) {
                _ui.value = _ui.value.copy(connected = false, status = e.message ?: "connection error")
            }
        }
    }

    fun detach() {
        transport?.close()
        transport = null
        connectJob?.cancel()
        connectJob = null
        drainRaw()
        _ui.value = _ui.value.copy(active = null, connected = false, status = "idle")
    }

    fun kill(pty: PtyInfo) {
        viewModelScope.launch {
            runCatching { api.ptyDelete(pty.id, projectDir()) }
            if (_ui.value.active?.id == pty.id) {
                transport?.close()
                transport = null
                _ui.value = _ui.value.copy(active = null, connected = false)
            }
            refresh()
        }
    }

    fun send(text: String) {
        transport?.send(text)
    }

    fun resize(cols: Int, rows: Int) {
        val pty = _ui.value.active ?: return
        val raw = rawMode
        viewModelScope.launch(termDispatcher) {
            if (!raw) {
                emulator.resize(cols, rows)
                _frame.value = emulator.version
            }
            runCatching { api.ptyUpdate(pty.id, PtyUpdateBody(size = PtySize(rows = rows, cols = cols)), projectDir()) }
        }
    }

    override fun onCleared() {
        transport?.close()
        super.onCleared()
    }
}
