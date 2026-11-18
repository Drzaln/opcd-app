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
    private val sessionModelKey = stringPreferencesKey("session_selected_model")
    private val sessionAgentKey = stringPreferencesKey("session_selected_agent")
    private val notifyKey = androidx.datastore.preferences.core.booleanPreferencesKey("notifications_enabled")
    private val directoryKey = stringPreferencesKey("current_directory")
    private val skippedUpdateKey = stringPreferencesKey("skipped_update_version")
    private val themeKey = stringPreferencesKey("theme_mode")
    private val busySessionsKey = androidx.datastore.preferences.core.stringSetPreferencesKey("busy_sessions")
    private val goApiKey = stringPreferencesKey("opencode_go_api_key")
    private val terminalRendererKey = stringPreferencesKey("terminal_renderer")
    private val recentFilesKey = stringPreferencesKey("recent_files")
    private val editorWrapKey = androidx.datastore.preferences.core.booleanPreferencesKey("editor_soft_wrap")

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

    // Per-session overrides, keyed "<serverId>|<sessionId>". Selecting in a session also updates the
    // per-server value, which stays the default for new sessions.
    private val sessionModels: Flow<Map<String, String>> = context.dataStore.data.map {
        decodeStringMap(it[sessionModelKey])
    }

    private val sessionAgents: Flow<Map<String, String>> = context.dataStore.data.map {
        decodeStringMap(it[sessionAgentKey])
    }

    suspend fun sessionModel(serverId: String, sessionId: String): String? =
        sessionModels.first()["$serverId|$sessionId"]

    suspend fun sessionAgent(serverId: String, sessionId: String): String? =
        sessionAgents.first()["$serverId|$sessionId"]

    val notificationsEnabled: Flow<Boolean> = context.dataStore.data.map { it[notifyKey] ?: false }

    val currentDirectory: Flow<String?> = context.dataStore.data.map { it[directoryKey] }

    val skippedUpdateVersion: Flow<String?> = context.dataStore.data.map { it[skippedUpdateKey] }

    val theme: Flow<String?> = context.dataStore.data.map { it[themeKey] }

    val busySessions: Flow<Set<String>> = context.dataStore.data.map { it[busySessionsKey] ?: emptySet() }

    val openCodeGoApiKey: Flow<String> = context.dataStore.data.map { it[goApiKey] ?: "" }

    /** "xterm" (WebView + vendored xterm.js, default) or "native" (the Compose canvas renderer). */
    val terminalRenderer: Flow<String> = context.dataStore.data.map { it[terminalRendererKey] ?: "xterm" }

    suspend fun setTerminalRenderer(value: String) {
        context.dataStore.edit { it[terminalRendererKey] = value }
    }

    suspend fun setOpenCodeGoApiKey(value: String?) {
        context.dataStore.edit { prefs ->
            if (value.isNullOrBlank()) prefs.remove(goApiKey) else prefs[goApiKey] = value.trim()
        }
    }

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

    suspend fun setSessionModel(serverId: String, sessionId: String, value: String?) {
        context.dataStore.edit { prefs ->
            val map = decodeStringMap(prefs[sessionModelKey]).toMutableMap()
            val key = "$serverId|$sessionId"
            if (value == null) map.remove(key) else map[key] = value
            prefs[sessionModelKey] = json.encodeToString(map)
        }
    }

    suspend fun setSessionAgent(serverId: String, sessionId: String, value: String?) {
        context.dataStore.edit { prefs ->
            val map = decodeStringMap(prefs[sessionAgentKey]).toMutableMap()
            val key = "$serverId|$sessionId"
            if (value == null) map.remove(key) else map[key] = value
            prefs[sessionAgentKey] = json.encodeToString(map)
        }
    }

    /** Recently opened editor files, newest first, keyed "<serverId>|<project dir>". */
    fun recentFiles(serverId: String, dir: String?): Flow<List<String>> = context.dataStore.data.map { prefs ->
        decodeListMap(prefs[recentFilesKey])["$serverId|${dir.orEmpty()}"].orEmpty()
    }

    suspend fun addRecentFile(serverId: String, dir: String?, path: String) {
        context.dataStore.edit { prefs ->
            val map = decodeListMap(prefs[recentFilesKey]).toMutableMap()
            val key = "$serverId|${dir.orEmpty()}"
            map[key] = (listOf(path) + map[key].orEmpty().filter { it != path }).take(12)
            prefs[recentFilesKey] = json.encodeToString(map)
        }
    }

    val editorSoftWrap: Flow<Boolean> = context.dataStore.data.map { it[editorWrapKey] ?: false }

    suspend fun setEditorSoftWrap(value: Boolean) {
        context.dataStore.edit { it[editorWrapKey] = value }
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

    private fun decodeListMap(raw: String?): Map<String, List<String>> =
        runCatching { json.decodeFromString<Map<String, List<String>>>(raw ?: "{}") }.getOrDefault(emptyMap())

    private fun decodeStringMap(raw: String?): Map<String, String> =
        runCatching { json.decodeFromString<Map<String, String>>(raw ?: "{}") }.getOrDefault(emptyMap())
}