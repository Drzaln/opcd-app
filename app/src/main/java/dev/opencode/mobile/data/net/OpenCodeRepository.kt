package dev.opencode.mobile.data.net

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit

data class OcEvent(
    val type: String,
    val data: JsonElement? = null,
)

class OpenCodeRepository {

    private val json = Json { ignoreUnknownKeys = true }

    private val lock = Any()
    private val apiCache = mutableMapOf<String, OpenCodeApi>()
    private val clientCache = mutableMapOf<String, OkHttpClient>()

    fun apiFor(server: ServerConfig, cache: Boolean = true): OpenCodeApi = synchronized(lock) {
        if (cache) {
            apiCache.getOrPut(server.id) { build(server) }
        } else {
            build(server)
        }
    }

    private fun build(server: ServerConfig): OpenCodeApi = Retrofit.Builder()
        .baseUrl(baseUrl(server))
        .client(httpClient(server))
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(OpenCodeApi::class.java)

    fun events(server: ServerConfig): Flow<OcEvent> = callbackFlow {
        val client = httpClient(server)
        val request = Request.Builder()
            .url("${baseUrl(server)}event")
            .header("Authorization", Credentials.basic(server.username, server.password))
            .header("Accept", "text/event-stream")
            .build()
        val factory = EventSources.createFactory(client)
        var closed = false

        val listener = ReconnectingListener(
            client = client,
            request = request,
            factory = factory,
            isClosed = { closed },
            onEvent = { trySend(it) },
        )
        factory.newEventSource(request, listener)

        awaitClose {
            closed = true
            client.dispatcher.cancelAll()
            client.connectionPool.evictAll()
        }
    }

    private class ReconnectingListener(
        private val client: OkHttpClient,
        private val request: Request,
        private val factory: EventSource.Factory,
        private val isClosed: () -> Boolean,
        private val onEvent: (OcEvent) -> Unit,
    ) : EventSourceListener() {

        private var backoff = 1000L

        override fun onOpen(eventSource: EventSource, response: Response) {
            backoff = 1000L
        }

        override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
            val json = runCatching { Json.parseToJsonElement(data) }.getOrNull()
            val eventType = if (json is JsonObject) {
                json["type"]?.jsonPrimitive?.contentOrNull
            } else {
                type
            }
            val props = if (json is JsonObject) json["properties"] else json
            onEvent(OcEvent(eventType ?: type ?: "event", props))
        }

        override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
            if (isClosed()) return
            client.dispatcher.executorService.execute {
                try {
                    Thread.sleep(backoff)
                } catch (_: InterruptedException) {
                    return@execute
                }
                if (isClosed()) return@execute
                runCatching { factory.newEventSource(request, this) }
            }
            backoff = (backoff * 2).coerceAtMost(10_000L)
        }
    }

    private fun baseUrl(server: ServerConfig): String = server.baseUrl.trimEnd('/') + "/"

    private fun httpClient(server: ServerConfig): OkHttpClient {
        val cached = synchronized(lock) { clientCache[server.id] }
        if (cached != null) return cached
        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val req = chain.request().newBuilder()
                    .header("Authorization", Credentials.basic(server.username, server.password))
                    .build()
                chain.proceed(req)
            }
            .build()
        synchronized(lock) { clientCache[server.id] = client }
        return client
    }
}