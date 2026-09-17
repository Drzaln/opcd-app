package dev.opencode.mobile.terminal

import dev.opencode.mobile.data.net.ServerConfig
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

sealed interface PtyEvent {
    data class Data(val text: String) : PtyEvent
    data class Meta(val cursor: Long?) : PtyEvent
    data class Closed(val code: Int, val reason: String) : PtyEvent
    data class Failed(val message: String) : PtyEvent
}

/**
 * Terminals speak raw UTF-8 over a WebSocket. One control frame (first byte 0x00 + JSON) carries
 * the absolute output cursor after replay; everything else is terminal output.
 */
interface PtyTransport {
    val events: Flow<PtyEvent>
    fun send(text: String)
    fun close()
}

class OkHttpPtyTransport(
    server: ServerConfig,
    private val ptyId: String,
    directory: String?,
    cursor: Long? = null,
) : PtyTransport {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private var socket: WebSocket? = null
    private var connected = false

    private val url = buildString {
        val base = server.baseUrl.trimEnd('/')
        val ws = when {
            base.startsWith("https://") -> "wss://" + base.removePrefix("https://")
            base.startsWith("http://") -> "ws://" + base.removePrefix("http://")
            else -> "ws://$base"
        }
        append(ws)
        append("/pty/")
        append(ptyId)
        append("/connect")
        val query = mutableListOf<String>()
        if (directory != null) query.add("directory=" + java.net.URLEncoder.encode(directory, "UTF-8"))
        if (cursor != null) query.add("cursor=$cursor")
        if (query.isNotEmpty()) append("?").append(query.joinToString("&"))
    }

    override val events: Flow<PtyEvent> = callbackFlow {
        val request = Request.Builder()
            .url(url)
            .header("Authorization", Credentials.basic(server.username, server.password))
            .build()
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                socket = webSocket
                connected = true
            }

            override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
                val data = bytes.toByteArray()
                if (data.isNotEmpty() && data[0].toInt() == 0) {
                    val text = runCatching { String(data, 1, data.size - 1, Charsets.UTF_8) }.getOrNull()
                    val cursor = text?.let { Regex("\"cursor\"\\s*:\\s*(-?\\d+)").find(it)?.groupValues?.get(1)?.toLongOrNull() }
                    trySend(PtyEvent.Meta(cursor))
                } else {
                    trySend(PtyEvent.Data(String(data, Charsets.UTF_8)))
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                trySend(PtyEvent.Data(text))
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                trySend(PtyEvent.Closed(code, reason))
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                trySend(PtyEvent.Closed(code, reason))
                close()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                connected = false
                trySend(PtyEvent.Failed(t.message ?: "connection failed"))
                close()
            }
        }
        client.newWebSocket(request, listener)
        awaitClose {
            connected = false
            runCatching { socket?.close(1000, "bye") }
            socket = null
            client.dispatcher.executorService.shutdown()
        }
    }

    override fun send(text: String) {
        if (connected) socket?.send(text)
    }

    override fun close() {
        runCatching { socket?.close(1000, "bye") }
    }
}
