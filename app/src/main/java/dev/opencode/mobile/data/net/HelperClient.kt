package dev.opencode.mobile.data.net

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** What the app knows about the optional Mac helper for the active server (see `helper/`). */
sealed interface HelperState {
    data object Idle : HelperState
    data object Checking : HelperState
    /** Nothing answered at host:port — the helper isn't running (or isn't reachable). */
    data class Absent(val url: String) : HelperState
    /** Running but this phone isn't paired with it yet. */
    data class Unpaired(val info: HelperInfo) : HelperState
    data class Pairing(val code: String) : HelperState
    data class Connecting(val info: HelperInfo) : HelperState
    data class Ready(val info: HelperInfo) : HelperState
    data class Failed(val message: String) : HelperState
}

@Serializable
data class HelperInfo(
    val name: String = "",
    val version: String = "",
    val hostname: String = "",
    val protocol: Int = 0,
    val features: List<String> = emptyList(),
)

@Serializable
data class HelperPairing(val host: String, val port: Int, val token: String, val deviceId: String = "")

class HelperException(message: String) : Exception(message)

/**
 * Talks to the optional Mac helper: `GET /helper/info` for discovery, `/helper/pair/start|wait` for
 * pairing (the Mac shows an Allow/Deny dialog with the code the phone displays), then one WebSocket
 * (`Authorization: Bearer`) carrying `{id, method, params}` requests and `{method, params}`
 * notifications. The helper lives on the same host as the opencode server, port 4097 by default.
 */
class HelperManager(private val store: ServerStore) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }
    private val http = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(80, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private val _state = MutableStateFlow<HelperState>(HelperState.Idle)
    val state: StateFlow<HelperState> = _state

    private val _notifications = MutableSharedFlow<JsonObject>(extraBufferCapacity = 512, onBufferOverflow = BufferOverflow.SUSPEND)
    /** Server → app notifications (`lsp.message`, `lsp.exit`, `metro.log`, …), in arrival order. */
    val notifications: SharedFlow<JsonObject> = _notifications

    // One queue + one forwarder keeps socket order. (A `launch { emit() }` per message does not: on a
    // multi-threaded dispatcher those coroutines race, which shuffled Metro log lines.)
    private val incoming = kotlinx.coroutines.channels.Channel<JsonObject>(kotlinx.coroutines.channels.Channel.UNLIMITED)

    init {
        scope.launch { for (msg in incoming) _notifications.emit(msg) }
    }

    /**
     * Binary frames (the live device screen's H.264 packets), called on OkHttp's reader thread in
     * arrival order — the decoder needs every packet, in order, without a coroutine hop per frame.
     */
    @Volatile var binaryListener: ((ByteArray) -> Unit)? = null

    private var server: ServerConfig? = null
    private var loop: Job? = null
    @Volatile private var socket: WebSocket? = null
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<JsonElement>>()
    private val ids = AtomicInteger(1)

    /** Follow the active opencode server: probe, connect when paired, and keep retrying. */
    fun attach(target: ServerConfig?) {
        if (target?.id == server?.id && target?.baseUrl == server?.baseUrl && loop?.isActive == true) return
        server = target
        loop?.cancel()
        closeSocket()
        if (target == null) {
            _state.value = HelperState.Idle
            return
        }
        loop = scope.launch { runLoop(target) }
    }

    fun refresh() {
        val target = server ?: return
        if (_state.value is HelperState.Ready || _state.value is HelperState.Pairing) return
        loop?.cancel()
        loop = scope.launch { runLoop(target) }
    }

    private suspend fun runLoop(target: ServerConfig) {
        var backoff = 5_000L
        while (true) {
            if (_state.value !is HelperState.Pairing) step(target)
            val state = _state.value
            if (state is HelperState.Ready) {
                awaitDisconnect()
                backoff = 5_000L
                continue
            }
            delay(if (state is HelperState.Unpaired) 20_000L else backoff)
            backoff = (backoff * 2).coerceAtMost(60_000L)
        }
    }

    private suspend fun step(target: ServerConfig) {
        _state.value = HelperState.Checking
        val pairing = store.helperPairing(target.id)
        val host = pairing?.host ?: hostOf(target)
        val port = pairing?.port ?: (store.helperPort(target.id) ?: DEFAULT_PORT)
        val base = "http://${bracket(host)}:$port"
        val info = try {
            info(base)
        } catch (e: Exception) {
            _state.value = HelperState.Absent(base)
            return
        }
        if (pairing == null) {
            _state.value = HelperState.Unpaired(info)
            return
        }
        _state.value = HelperState.Connecting(info)
        connect(base, pairing.token, info)
    }

    private suspend fun info(base: String): HelperInfo = withContext(Dispatchers.IO) {
        http.newCall(Request.Builder().url("$base/helper/info").build()).execute().use { r ->
            if (!r.isSuccessful) throw HelperException("HTTP ${r.code}")
            val info = json.decodeFromString<HelperInfo>(r.body?.string().orEmpty())
            if (info.name != "opencode-mobile-helper") throw HelperException("Not an OpenCode helper")
            info
        }
    }

    private val disconnected = MutableStateFlow(0)

    private suspend fun awaitDisconnect() {
        val start = disconnected.value
        while (disconnected.value == start && socket != null) delay(500)
    }

    private suspend fun connect(base: String, token: String, info: HelperInfo) {
        val opened = CompletableDeferred<Boolean>()
        val wsUrl = base.replaceFirst("http://", "ws://") + "/helper/ws"
        val request = Request.Builder().url(wsUrl).header("Authorization", "Bearer $token").build()
        http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                socket = webSocket
                _state.value = HelperState.Ready(info)
                opened.complete(true)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val msg = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
                val id = (msg["id"] as? JsonPrimitive)?.intOrNull
                if (id != null) {
                    pending.remove(id)?.complete(msg)
                } else {
                    incoming.trySend(msg)
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
                binaryListener?.invoke(bytes.toByteArray())
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (response?.code == 401) {
                    // The Mac revoked us (or forgot us): pair again.
                    server?.let { s -> scope.launch { store.setHelperPairing(s.id, null) } }
                }
                onGone(webSocket, if (response?.code == 401) "Pairing was revoked on the Mac" else t.message ?: "connection failed")
                opened.complete(false)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                onGone(webSocket, reason.ifBlank { "closed" })
                opened.complete(false)
            }
        })
        if (!opened.await() && _state.value !is HelperState.Ready) {
            if (_state.value !is HelperState.Failed) _state.value = HelperState.Failed("Couldn't connect to the helper")
        }
    }

    private fun onGone(webSocket: WebSocket, reason: String) {
        if (socket !== webSocket && socket != null) return
        socket = null
        pending.values.forEach { it.completeExceptionally(HelperException("Helper disconnected: $reason")) }
        pending.clear()
        if (_state.value is HelperState.Ready) _state.value = HelperState.Failed("Helper disconnected: $reason")
        disconnected.value++
    }

    private fun closeSocket() {
        socket?.close(1000, "bye")
        socket = null
    }

    /** Sends a request and waits for its reply's `result` (throws with the helper's error message). */
    suspend fun request(method: String, params: JsonObject = JsonObject(emptyMap()), timeoutMs: Long = 20_000): JsonElement {
        val ws = socket ?: throw HelperException("Helper not connected")
        val id = ids.getAndIncrement()
        val reply = CompletableDeferred<JsonElement>()
        pending[id] = reply
        val text = buildJsonObject { put("id", id); put("method", method); put("params", params) }.toString()
        if (!ws.send(text)) {
            pending.remove(id)
            throw HelperException("Helper connection is busy")
        }
        val msg = try {
            withTimeout(timeoutMs) { reply.await() }.jsonObject
        } finally {
            pending.remove(id)
        }
        (msg["error"] as? JsonObject)?.let { throw HelperException(it["message"]?.jsonPrimitive?.contentOrNull ?: "helper error") }
        return msg["result"] ?: JsonObject(emptyMap())
    }

    /** Fire-and-forget (LSP traffic): order is preserved by the socket. */
    fun send(method: String, params: JsonObject): Boolean {
        val ws = socket ?: return false
        return ws.send(buildJsonObject { put("method", method); put("params", params) }.toString())
    }

    /**
     * Pairs this phone: asks the helper for a code ([onCode] shows it), then waits while the person at
     * the Mac compares it and presses Allow. Stores the token for this server.
     */
    suspend fun pair(deviceName: String, onCode: (String) -> Unit) {
        val target = server ?: throw HelperException("No server selected")
        val host = hostOf(target)
        val port = store.helperPort(target.id) ?: DEFAULT_PORT
        val base = "http://${bracket(host)}:$port"
        val jsonType = "application/json".toMediaType()
        val start = withContext(Dispatchers.IO) {
            val body = buildJsonObject { put("device", deviceName) }.toString().toRequestBody(jsonType)
            http.newCall(Request.Builder().url("$base/helper/pair/start").post(body).build()).execute().use { r ->
                val text = r.body?.string().orEmpty()
                if (!r.isSuccessful) throw HelperException(errorOf(text) ?: "Pairing failed (HTTP ${r.code})")
                json.parseToJsonElement(text).jsonObject
            }
        }
        val code = start["code"]?.jsonPrimitive?.contentOrNull ?: throw HelperException("No pairing code")
        val requestId = start["requestId"]?.jsonPrimitive?.contentOrNull ?: throw HelperException("No pairing request")
        _state.value = HelperState.Pairing(code)
        onCode(code)
        try {
            val result = withContext(Dispatchers.IO) {
                val body = buildJsonObject { put("requestId", requestId) }.toString().toRequestBody(jsonType)
                http.newCall(Request.Builder().url("$base/helper/pair/wait").post(body).build()).execute().use { r ->
                    val text = r.body?.string().orEmpty()
                    if (!r.isSuccessful) throw HelperException(errorOf(text) ?: "Pairing failed (HTTP ${r.code})")
                    json.parseToJsonElement(text).jsonObject
                }
            }
            val token = result["token"]?.jsonPrimitive?.contentOrNull ?: throw HelperException("No token")
            store.setHelperPairing(target.id, HelperPairing(host, port, token, result["deviceId"]?.jsonPrimitive?.contentOrNull.orEmpty()))
        } finally {
            _state.value = HelperState.Checking
            loop?.cancel()
            loop = scope.launch { runLoop(target) }
        }
    }

    suspend fun unpair() {
        val target = server ?: return
        store.setHelperPairing(target.id, null)
        closeSocket()
        loop?.cancel()
        loop = scope.launch { runLoop(target) }
    }

    private fun errorOf(text: String): String? =
        runCatching { json.parseToJsonElement(text).jsonObject["error"]?.jsonPrimitive?.contentOrNull }.getOrNull()

    companion object {
        const val DEFAULT_PORT = 4097

        /** The opencode server's host — the helper runs on the same Mac. */
        fun hostOf(server: ServerConfig): String =
            runCatching { URI(server.baseUrl.trim()).host }.getOrNull()?.trim('[', ']') ?: "127.0.0.1"

        private fun bracket(host: String) = if (host.contains(':')) "[$host]" else host

        fun features(state: HelperState): List<String> = when (state) {
            is HelperState.Ready -> state.info.features
            else -> emptyList()
        }

        /** `["a","b"]` array helper for callers building params. */
        fun JsonElement.strings(): List<String> = runCatching { jsonArray.mapNotNull { it.jsonPrimitive.contentOrNull } }.getOrDefault(emptyList())
    }
}
