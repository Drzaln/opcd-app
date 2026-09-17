package dev.opencode.mobile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.opencode.mobile.data.net.NsdDiscovery
import dev.opencode.mobile.data.net.OpenCodeRepository
import dev.opencode.mobile.data.net.ServerConfig
import dev.opencode.mobile.data.net.ServerStore
import dev.opencode.mobile.update.UpdateState
import dev.opencode.mobile.update.Updater
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
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

    init {
        viewModelScope.launch {
            store.currentDirectory.collect { dir ->
                if (_currentDirectory.value != dir) _currentDirectory.value = dir
            }
        }
        viewModelScope.launch {
            if (store.notificationsEnabled.first()) {
                dev.opencode.mobile.notify.SessionWatchService.start(app)
            }
        }
    }

    val notificationsEnabled: StateFlow<Boolean> =
        store.notificationsEnabled.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _update = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val update: StateFlow<UpdateState> = _update

    private var updateChecked = false

    fun checkForUpdate() {
        if (updateChecked) return
        updateChecked = true
        viewModelScope.launch {
            val latest = runCatching { updater.latestVersion() }.getOrNull() ?: return@launch
            if (!updater.isNewer(latest, updater.currentVersion)) return@launch
            if (store.skippedUpdateVersion.first() == latest) return@launch
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

    fun setNotificationsEnabled(enabled: Boolean) {
        viewModelScope.launch { store.setNotificationsEnabled(enabled) }
    }

    fun setDirectory(directory: String?) {
        _currentDirectory.value = directory
        viewModelScope.launch { store.setCurrentDirectory(directory) }
    }

    fun apiFor(server: ServerConfig) = repository.apiFor(server)

    fun events(server: ServerConfig) = repository.events(server)

    suspend fun probe(server: ServerConfig): ServerProbeResult = try {
        val api = repository.apiFor(server.copy(id = "probe-${System.currentTimeMillis()}"), cache = false)
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