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

    val servers: Flow<List<ServerConfig>> = context.dataStore.data.map { prefs ->
        val raw = prefs[serversKey] ?: "[]"
        runCatching { json.decodeFromString<List<ServerConfig>>(raw) }.getOrDefault(emptyList())
    }

    val activeId: Flow<String?> = context.dataStore.data.map { it[activeKey] }

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
}