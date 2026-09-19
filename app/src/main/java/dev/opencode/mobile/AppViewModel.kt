package dev.opencode.mobile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.opencode.mobile.data.model.PermissionReplyBody
import dev.opencode.mobile.data.model.PermissionReplyV2Body
import dev.opencode.mobile.data.model.PermissionRequest
import dev.opencode.mobile.data.model.QuestionReplyBody
import dev.opencode.mobile.data.model.QuestionRequest
import dev.opencode.mobile.data.net.NsdDiscovery
import dev.opencode.mobile.data.net.OpenCodeRepository
import dev.opencode.mobile.data.net.ServerConfig
import dev.opencode.mobile.data.net.ServerStore
import dev.opencode.mobile.update.UpdateState
import dev.opencode.mobile.update.Updater
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

class AppViewModel(private val app: OpenCodeApp) : ViewModel() {

    private val store: ServerStore = app.serverStore
    private val repository: OpenCodeRepository = app.repository
    val nsd = NsdDiscovery(app)
    private val updater = Updater(app)

    val servers: StateFlow<List<ServerConfig>> =
        store.servers.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val activeServer: StateFlow<ServerConfig?> =
        combine(store.activeId, store.servers) { id, list -> list.firstOrNull { it.id == id } }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _currentDirectory = MutableStateFlow<String?>(null)
    val currentDirectory: StateFlow<String?> = _currentDirectory

    data class Prompts(
        val permissions: List<PermissionRequest> = emptyList(),
        val questions: List<QuestionRequest> = emptyList(),
    )

    private val _prompts = MutableStateFlow(Prompts())
    val prompts: StateFlow<Prompts> = _prompts

    private val promptJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    val themeMode: StateFlow<dev.opencode.mobile.ui.theme.ThemeMode> =
        store.theme.map { dev.opencode.mobile.ui.theme.themeModeFrom(it) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, dev.opencode.mobile.ui.theme.ThemeMode.SYSTEM)

    fun setThemeMode(mode: dev.opencode.mobile.ui.theme.ThemeMode) {
        viewModelScope.launch { store.setTheme(mode.name.lowercase()) }
    }

    init {
        viewModelScope.launch {
            store.currentDirectory.collect { dir ->
                if (_currentDirectory.value != dir) _currentDirectory.value = dir
            }
        }
        viewModelScope.launch {
            if (store.notificationsEnabled.first()) {
                dev.opencode.mobile.notify.SessionWatchService.start(app)
                dev.opencode.mobile.notify.NotifyScheduler.enable(app)
            }
        }
        watchPrompts()
        startConnectionWatch()
    }

    val notificationsEnabled: StateFlow<Boolean> =
        store.notificationsEnabled.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _update = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val update: StateFlow<UpdateState> = _update

    private var updateChecked = false

    private val _updateMessage = MutableStateFlow<String?>(null)
    val updateMessage: StateFlow<String?> = _updateMessage

    fun checkForUpdate(force: Boolean = false) {
        if (updateChecked && !force) return
        updateChecked = true
        if (force) _update.value = UpdateState.Idle
        viewModelScope.launch {
            val latest = runCatching { updater.latestVersion() }.getOrNull()
            if (latest == null) {
                if (force) _updateMessage.value = "Could not reach GitHub"
                return@launch
            }
            if (!updater.isNewer(latest, updater.currentVersion)) {
                if (force) _updateMessage.value = "You're on the latest version (v${updater.currentVersion})"
                return@launch
            }
            if (!force && store.skippedUpdateVersion.first() == latest) return@launch
            _update.value = UpdateState.Available(latest)
        }
    }

    fun downloadUpdate(version: String) {
        if (_update.value is UpdateState.Downloading) return
        viewModelScope.launch {
            _update.value = UpdateState.Downloading(version, 0)
            try {
                val file = updater.download { percent ->
                    _update.value = UpdateState.Downloading(version, percent)
                }
                _update.value = UpdateState.Ready(version, file)
            } catch (e: Exception) {
                _update.value = UpdateState.Failed(e.message ?: "Download failed")
            }
        }
    }

    fun installUpdate(file: File) {
        if (!updater.canRequestInstalls()) {
            updater.openInstallSettings()
            return
        }
        runCatching { updater.install(file) }
            .onFailure { _update.value = UpdateState.Failed(it.message ?: "Install failed") }
    }

    fun dismissUpdate(version: String?) {
        _update.value = UpdateState.Idle
        if (version != null) {
            viewModelScope.launch { store.setSkippedUpdateVersion(version) }
        }
    }

    // ---- Blocking prompts (permissions + questions) are app-wide so they are never missed,
    //      including when the server is the TUI on another port or the app is not in the chat.

    private fun watchPrompts() {
        viewModelScope.launch {
            combine(store.activeId, store.servers, store.currentDirectory) { id, list, dir ->
                Triple(list.firstOrNull { it.id == id }, id, dir)
            }
                .distinctUntilChanged()
                .collectLatest { (server, _, dir) ->
                    if (server == null) {
                        _prompts.value = Prompts()
                        return@collectLatest
                    }
                    // Re-fetch on every (re)subscribe so prompts raised while disconnected show up.
                    refreshPrompts(server, dir)
                    app.repository.events(server, dir).collect { event -> applyPromptEvent(event) }
        }
        watchPrompts()
    }
    }

    fun refreshPrompts() {
        viewModelScope.launch {
            val server = activeServer.value ?: return@launch
            refreshPrompts(server, currentDirectory.value)
        }
    }

    private suspend fun refreshPrompts(server: ServerConfig, dir: String?) {
        val api = repository.apiFor(server)
        val permissions = runCatching { api.pendingPermissions(dir) }.getOrNull()
        val questions = runCatching { api.pendingQuestions(dir) }.getOrNull()
        _prompts.update {
            it.copy(
                permissions = permissions ?: it.permissions,
                questions = questions ?: it.questions,
            )
        }
    }

    private fun applyPromptEvent(event: dev.opencode.mobile.data.net.OcEvent) {
        val properties = event.data as? kotlinx.serialization.json.JsonObject
        when (event.type) {
            "permission.updated", "permission.asked" -> {
                val request = properties?.let {
                    runCatching { promptJson.decodeFromJsonElement<PermissionRequest>(it) }.getOrNull()
                } ?: return
                if (request.id.isEmpty()) return
                _prompts.update { if (it.permissions.any { p -> p.id == request.id }) it else it.copy(permissions = it.permissions + request) }
            }
            "permission.replied" -> {
                val id = properties?.get("requestID")?.jsonPrimitive?.contentOrNull
                    ?: properties?.get("permissionID")?.jsonPrimitive?.contentOrNull
                _prompts.update { p ->
                    if (id == null) p.copy(permissions = emptyList()) else p.copy(permissions = p.permissions.filterNot { it.id == id })
                }
            }
            "question.asked", "question.v2.asked" -> {
                val request = properties?.let {
                    runCatching { promptJson.decodeFromJsonElement<QuestionRequest>(it) }.getOrNull()
                } ?: return
                if (request.id.isEmpty()) return
                _prompts.update { if (it.questions.any { q -> q.id == request.id }) it else it.copy(questions = it.questions + request) }
            }
            "question.replied", "question.v2.replied", "question.rejected", "question.v2.rejected" -> {
                val id = properties?.get("requestID")?.jsonPrimitive?.contentOrNull ?: return
                _prompts.update { p -> p.copy(questions = p.questions.filterNot { it.id == id }) }
            }
        }
    }

    fun respondPermission(request: PermissionRequest, response: String) {
        viewModelScope.launch {
            _prompts.update { p -> p.copy(permissions = p.permissions.filterNot { it.id == request.id }) }
            val server = activeServer.value ?: return@launch
            val dir = currentDirectory.value
            val api = repository.apiFor(server)
            val legacy = runCatching {
                api.replyPermission(request.sessionID, request.id, PermissionReplyBody(response), dir)
            }.getOrNull()
            val ok = legacy?.isSuccessful == true || runCatching {
                api.replyPermissionV2(request.id, PermissionReplyV2Body(response), dir).isSuccessful
            }.getOrDefault(false)
            if (!ok) _prompts.update { p -> p.copy(permissions = p.permissions + request) }
        }
    }

    fun answerQuestion(request: QuestionRequest, answers: List<List<String>>) {
        viewModelScope.launch {
            _prompts.update { p -> p.copy(questions = p.questions.filterNot { it.id == request.id }) }
            val server = activeServer.value ?: return@launch
            val ok = runCatching {
                repository.apiFor(server)
                    .replyQuestion(request.id, QuestionReplyBody(answers), currentDirectory.value)
                    .isSuccessful
            }.getOrDefault(false)
            if (!ok) _prompts.update { p -> p.copy(questions = p.questions + request) }
        }
    }

    fun rejectQuestion(request: QuestionRequest) {
        viewModelScope.launch {
            _prompts.update { p -> p.copy(questions = p.questions.filterNot { it.id == request.id }) }
            val server = activeServer.value ?: return@launch
            runCatching {
                repository.apiFor(server).rejectQuestion(request.id, currentDirectory.value)
            }
        }
    }

    fun setNotificationsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            store.setNotificationsEnabled(enabled)
            if (enabled) {
                dev.opencode.mobile.notify.NotifyScheduler.enable(app)
            } else {
                dev.opencode.mobile.notify.NotifyScheduler.disable(app)
            }
        }
    }

    fun setDirectory(directory: String?) {
        _currentDirectory.value = directory
        viewModelScope.launch { store.setCurrentDirectory(directory) }
    }

    fun apiFor(server: ServerConfig) = repository.apiFor(server)

    fun events(server: ServerConfig) = repository.events(server)

    private val _connection = MutableStateFlow(ConnectionState())
    val connection: StateFlow<ConnectionState> = _connection

    private fun startConnectionWatch() {
        viewModelScope.launch {
            activeServer.collectLatest { server ->
                if (server == null) {
                    _connection.value = ConnectionState(status = ConnectionStatus.OFFLINE, error = "No server selected")
                    return@collectLatest
                }
                _connection.value = ConnectionState(status = ConnectionStatus.CHECKING)
                while (true) {
                    refreshConnection(server)
                    kotlinx.coroutines.delay(30_000)
                }
            }
        }
    }

    fun refreshConnection(server: ServerConfig? = activeServer.value) {
        val target = server ?: return
        viewModelScope.launch {
            val start = System.currentTimeMillis()
            val result = probe(target)
            val latency = System.currentTimeMillis() - start
            if (activeServer.value?.id != target.id) return@launch
            _connection.value = if (result.ok) {
                ConnectionState(
                    status = ConnectionStatus.CONNECTED,
                    version = result.version,
                    latencyMs = latency,
                    checkedAt = System.currentTimeMillis(),
                )
            } else {
                ConnectionState(
                    status = ConnectionStatus.OFFLINE,
                    error = result.error,
                    latencyMs = latency,
                    checkedAt = System.currentTimeMillis(),
                )
            }
        }
    }

    suspend fun probe(server: ServerConfig): ServerProbeResult = try {
        val api = repository.apiFor(server.copy(id = "probe-${server.id}"), cache = true)
        val health = api.health()
        ServerProbeResult(ok = health.healthy, version = health.version)
    } catch (e: Exception) {
        ServerProbeResult(ok = false, error = e.message ?: e.javaClass.simpleName)
    }

    fun setActive(id: String) {
        viewModelScope.launch { store.setActive(id) }
    }

    fun addServer(config: ServerConfig) {
        viewModelScope.launch { store.add(config) }
    }

    suspend fun addServerSync(config: ServerConfig) {
        store.add(config)
    }

    suspend fun setActiveSync(id: String) {
        store.setActive(id)
    }

    fun removeServer(id: String) {
        viewModelScope.launch { store.remove(id) }
    }

    fun clearActive() {
        viewModelScope.launch { store.clearActive() }
    }
}

data class ServerProbeResult(
    val ok: Boolean,
    val version: String = "",
    val error: String = "",
)

enum class ConnectionStatus { CHECKING, CONNECTED, OFFLINE }

data class ConnectionState(
    val status: ConnectionStatus = ConnectionStatus.CHECKING,
    val version: String? = null,
    val error: String? = null,
    val latencyMs: Long? = null,
    val checkedAt: Long = 0L,
)