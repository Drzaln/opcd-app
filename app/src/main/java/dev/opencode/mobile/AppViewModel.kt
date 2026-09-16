package dev.opencode.mobile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.opencode.mobile.data.net.NsdDiscovery
import dev.opencode.mobile.data.net.OpenCodeRepository
import dev.opencode.mobile.data.net.ServerConfig
import dev.opencode.mobile.data.net.ServerStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class AppViewModel(private val app: OpenCodeApp) : ViewModel() {

    private val store: ServerStore = app.serverStore
    private val repository: OpenCodeRepository = app.repository
    val nsd = NsdDiscovery(app)

    val servers: StateFlow<List<ServerConfig>> =
        store.servers.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val activeServer: StateFlow<ServerConfig?> =
        combine(store.activeId, store.servers) { id, list -> list.firstOrNull { it.id == id } }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _currentDirectory = MutableStateFlow<String?>(null)
    val currentDirectory: StateFlow<String?> = _currentDirectory

    fun setDirectory(directory: String?) {
        _currentDirectory.value = directory
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