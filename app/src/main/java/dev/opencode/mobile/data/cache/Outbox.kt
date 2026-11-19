package dev.opencode.mobile.data.cache

import dev.opencode.mobile.data.model.SendMessageBody
import dev.opencode.mobile.data.net.OpenCodeRepository
import dev.opencode.mobile.data.net.ServerConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import retrofit2.HttpException
import java.io.IOException
import java.util.UUID

/** A prompt typed while the server was unreachable, waiting to be sent. */
@Serializable
data class OutboxItem(
    val id: String = UUID.randomUUID().toString(),
    val serverId: String,
    val sessionId: String,
    val directory: String? = null,
    val body: SendMessageBody,
    val text: String,
    val createdAt: Long = System.currentTimeMillis(),
    /** Set when the server answered but refused it (shown in the chat, never retried automatically). */
    val error: String? = null,
)

/**
 * Prompts queued while offline. Kept on disk (survives the app being killed) and sent in order the
 * next time the server answers — the connection watch and the chat's stream reconnect both call
 * [flush]. A network failure stops the flush and leaves the rest queued; an HTTP error marks that
 * item failed so it isn't resent blindly.
 */
class Outbox(private val cache: CacheStore, private val repository: OpenCodeRepository) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private val _items = MutableStateFlow<List<OutboxItem>>(emptyList())
    val items: StateFlow<List<OutboxItem>> = _items

    init {
        scope.launch {
            val saved = cache.get(KEY)?.let { runCatching { json.decodeFromString<List<OutboxItem>>(it) }.getOrNull() }.orEmpty()
            _items.update { current -> (saved + current).distinctBy { it.id } }
        }
    }

    fun add(item: OutboxItem) {
        _items.update { it + item }
        save()
    }

    fun remove(id: String) {
        _items.update { list -> list.filterNot { it.id == id } }
        save()
    }

    /** Clears an item's error so the next flush tries it again. */
    fun retry(id: String, server: ServerConfig) {
        _items.update { list -> list.map { if (it.id == id) it.copy(error = null) else it } }
        save()
        flush(server)
    }

    fun flush(server: ServerConfig) {
        if (_items.value.none { it.serverId == server.id && it.error == null }) return
        scope.launch {
            lock.withLock {
                val api = repository.apiFor(server)
                for (item in _items.value.filter { it.serverId == server.id && it.error == null }) {
                    try {
                        val response = api.sendMessageAsync(item.sessionId, item.body, item.directory)
                        if (response.isSuccessful) {
                            remove(item.id)
                        } else {
                            val body = runCatching { response.errorBody()?.string() }.getOrNull().orEmpty().take(200)
                            markFailed(item.id, "HTTP ${response.code()} $body".trim())
                        }
                    } catch (e: HttpException) {
                        markFailed(item.id, "HTTP ${e.code()}")
                    } catch (e: IOException) {
                        return@withLock // still unreachable: keep the rest for next time
                    } catch (e: Exception) {
                        markFailed(item.id, e.message ?: e.javaClass.simpleName)
                    }
                }
            }
        }
    }

    private fun markFailed(id: String, error: String) {
        _items.update { list -> list.map { if (it.id == id) it.copy(error = error) else it } }
        save()
    }

    private fun save() {
        val snapshot = _items.value
        scope.launch { cache.put(KEY, json.encodeToString(snapshot)) }
    }

    private companion object {
        const val KEY = "outbox"
    }
}
