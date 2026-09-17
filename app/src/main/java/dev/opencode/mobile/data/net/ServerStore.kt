package dev.opencode.mobile.data.net

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.dataStore by preferencesDataStore(name = "opencode_servers")

@Serializable
data class ServerConfig(
    val id: String = "",
    val name: String = "",
    val baseUrl: String = "",
    val username: String = "opencode",
    val password: String = "",
)

class ServerStore(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }

    private val serversKey = stringPreferencesKey("servers")
    private val activeKey = stringPreferencesKey("active")
    private val selectedModelKey = stringPreferencesKey("selected_model")
    private val selectedAgentKey = stringPreferencesKey("selected_agent")
    private val notifyKey = androidx.datastore.preferences.core.booleanPreferencesKey("notifications_enabled")
    private val directoryKey = stringPreferencesKey("current_directory")
    private val skippedUpdateKey = stringPreferencesKey("skipped_update_version")
    private val themeKey = stringPreferencesKey("theme_mode")
    private val busySessionsKey = androidx.datastore.preferences.core.stringSetPreferencesKey("busy_sessions")

    val servers: Flow<List<ServerConfig>> = context.dataStore.data.map { prefs ->
        val raw = prefs[serversKey] ?: "[]"
        runCatching { json.decodeFromString<List<ServerConfig>>(raw) }.getOrDefault(emptyList())
    }

    val activeId: Flow<String?> = context.dataStore.data.map { it[activeKey] }

    val selectedModels: Flow<Map<String, String>> = context.dataStore.data.map {
        decodeStringMap(it[selectedModelKey])
    }

    val selectedAgents: Flow<Map<String, String>> = context.dataStore.data.map {
        decodeStringMap(it[selectedAgentKey])
    }

    val notificationsEnabled: Flow<Boolean> = context.dataStore.data.map { it[notifyKey] ?: false }

    val currentDirectory: Flow<String?> = context.dataStore.data.map { it[directoryKey] }

    val skippedUpdateVersion: Flow<String?> = context.dataStore.data.map { it[skippedUpdateKey] }

    val theme: Flow<String?> = context.dataStore.data.map { it[themeKey] }

    val busySessions: Flow<Set<String>> = context.dataStore.data.map { it[busySessionsKey] ?: emptySet() }

    suspend fun setBusySessions(ids: Set<String>) {
        context.dataStore.edit { it[busySessionsKey] = ids }
    }

    suspend fun setTheme(value: String?) {
        context.dataStore.edit { prefs ->
            if (value == null) prefs.remove(themeKey) else prefs[themeKey] = value
        }
    }

    suspend fun setSkippedUpdateVersion(version: String?) {
        context.dataStore.edit { prefs ->
            if (version == null) prefs.remove(skippedUpdateKey) else prefs[skippedUpdateKey] = version
        }
    }

    suspend fun setCurrentDirectory(directory: String?) {
        context.dataStore.edit { prefs ->
            if (directory == null) prefs.remove(directoryKey) else prefs[directoryKey] = directory
        }
    }

    suspend fun setNotificationsEnabled(enabled: Boolean) {
        context.dataStore.edit { it[notifyKey] = enabled }
    }

    suspend fun setSelectedModel(serverId: String, value: String?) {
        context.dataStore.edit { prefs ->
            val map = decodeStringMap(prefs[selectedModelKey]).toMutableMap()
            if (value == null) map.remove(serverId) else map[serverId] = value
            prefs[selectedModelKey] = json.encodeToString(map)
        }
    }

    suspend fun setSelectedAgent(serverId: String, value: String?) {
        context.dataStore.edit { prefs ->
            val map = decodeStringMap(prefs[selectedAgentKey]).toMutableMap()
            if (value == null) map.remove(serverId) else map[serverId] = value
            prefs[selectedAgentKey] = json.encodeToString(map)
        }
    }

    suspend fun add(config: ServerConfig) {
        context.dataStore.edit { prefs ->
            val current = decodeServers(prefs[serversKey])
            prefs[serversKey] = json.encodeToString(current.filter { it.id != config.id } + config)
        }
    }

    suspend fun remove(id: String) {
        context.dataStore.edit { prefs ->
            val current = decodeServers(prefs[serversKey])
            prefs[serversKey] = json.encodeToString(current.filter { it.id != id })
            if (prefs[activeKey] == id) prefs.remove(activeKey)
        }
    }

    suspend fun setActive(id: String) {
        context.dataStore.edit { it[activeKey] = id }
    }

    suspend fun clearActive() {
        context.dataStore.edit { it.remove(activeKey) }
    }

    suspend fun get(id: String): ServerConfig? {
        val all = servers.first()
        return all.firstOrNull { it.id == id }
    }

    private fun decodeServers(raw: String?): List<ServerConfig> =
        runCatching { json.decodeFromString<List<ServerConfig>>(raw ?: "[]") }.getOrDefault(emptyList())

    private fun decodeStringMap(raw: String?): Map<String, String> =
        runCatching { json.decodeFromString<Map<String, String>>(raw ?: "{}") }.getOrDefault(emptyMap())
}