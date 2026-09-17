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
import kotlinx.coroutines.Job
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

    private val api = app.repository.apiFor(server)
    private var transport: PtyTransport? = null
    private var connectJob: Job? = null
    private var lastCursor: Long? = null

    init {
        refresh()
        loadShells()
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
            val created = runCatching {
                api.ptyCreate(
                    PtyCreateBody(command = command, cwd = projectDir(), title = command?.substringAfterLast('/')),
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
        connectJob?.cancel()
        transport?.close()
        emulator.reset()
        lastCursor = null
        _frame.value = emulator.version
        _ui.value = _ui.value.copy(active = pty, status = "connecting…", connected = false)
        val t = OkHttpPtyTransport(server, pty.id, projectDir(), cursor = null)
        transport = t
        connectJob = viewModelScope.launch {
            t.events.collect { event ->
                when (event) {
                    is PtyEvent.Data -> {
                        emulator.write(event.text)
                        if (!_ui.value.connected) _ui.value = _ui.value.copy(connected = true, status = "connected")
                        _frame.value = emulator.version
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
        }
    }

    fun detach() {
        transport?.close()
        transport = null
        connectJob?.cancel()
        connectJob = null
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
        emulator.resize(cols, rows)
        _frame.value = emulator.version
        val pty = _ui.value.active ?: return
        viewModelScope.launch {
            runCatching { api.ptyUpdate(pty.id, PtyUpdateBody(size = PtySize(rows = rows, cols = cols)), projectDir()) }
        }
    }

    override fun onCleared() {
        transport?.close()
        super.onCleared()
    }
}
