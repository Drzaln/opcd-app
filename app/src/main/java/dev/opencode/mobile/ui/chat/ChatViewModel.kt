package dev.opencode.mobile.ui.chat

import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.opencode.mobile.OpenCodeApp
import dev.opencode.mobile.data.model.Agent
import dev.opencode.mobile.data.model.Command
import dev.opencode.mobile.data.model.CommandBody
import dev.opencode.mobile.data.model.ForkBody
import dev.opencode.mobile.data.model.Message
import dev.opencode.mobile.data.model.MessageData
import dev.opencode.mobile.data.model.ModelRef
import dev.opencode.mobile.data.model.Part
import dev.opencode.mobile.data.model.PartInput
import dev.opencode.mobile.data.model.RevertBody
import dev.opencode.mobile.data.model.SendMessageBody
import dev.opencode.mobile.data.model.Session
import dev.opencode.mobile.data.model.SessionStatus
import dev.opencode.mobile.data.model.SummarizeBody
import dev.opencode.mobile.data.model.Todo
import dev.opencode.mobile.data.net.OcEvent
import dev.opencode.mobile.data.net.ServerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException

data class ModelOption(
    val providerId: String,
    val modelId: String,
    val label: String,
    val contextLimit: Long = 0,
)

data class Attachment(
    val uri: String,
    val name: String,
    val mime: String,
)

class ChatViewModel(
    private val app: OpenCodeApp,
    private val server: ServerConfig,
    private val sessionId: String,
    private val projectDir: () -> String?,
    private val isForeground: () -> Boolean = { true },
) : ViewModel() {

    data class UiState(
        val messages: List<MessageData> = emptyList(),
        val session: Session? = null,
        val status: SessionStatus? = null,
        val todos: List<Todo> = emptyList(),
        val agents: List<Agent> = emptyList(),
        val models: List<ModelOption> = emptyList(),
        val commands: List<Command> = emptyList(),
        val selectedAgent: String? = null,
        val selectedModel: ModelOption? = null,
        val queued: Int = 0,
        val loading: Boolean = true,
        val loadingOlder: Boolean = false,
        val hasMore: Boolean = false,
        val error: String? = null,
        val sending: Boolean = false,
    ) {
        val busy: Boolean get() = status?.type == "busy" || status?.type == "retry"
    }

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui

    private val _input = MutableStateFlow("")
    val input: StateFlow<String> = _input

    private val _attachments = MutableStateFlow<List<Attachment>>(emptyList())
    val attachments: StateFlow<List<Attachment>> = _attachments

    private val api = app.repository.apiFor(server)
    private val json = Json { ignoreUnknownKeys = true }

    private var refreshJob: Job? = null
    private var nextCursor: String? = null
    @Volatile private var lastEventAt = 0L
    @Volatile private var lastRefreshAt = 0L

    init {
        loadCached()
        refreshAll()
        loadMeta()
        startEvents(app)
        startPolling()
    }

    private val messagesKey: String get() = "messages:${server.id}:$sessionId"

    private fun loadCached() {
        viewModelScope.launch {
            val cached = app.cacheStore.get(messagesKey) ?: return@launch
            val messages = runCatching { json.decodeFromString<List<MessageData>>(cached) }.getOrNull() ?: return@launch
            if (messages.isNotEmpty()) {
                _ui.update { it.copy(messages = messages, loading = false) }
            }
        }
    }

    fun selectAgent(name: String?) {
        _ui.update { it.copy(selectedAgent = name) }
        viewModelScope.launch { app.serverStore.setSelectedAgent(server.id, name) }
    }

    fun selectModel(model: ModelOption?) {
        _ui.update { it.copy(selectedModel = model) }
        viewModelScope.launch {
            app.serverStore.setSelectedModel(server.id, model?.let { "${it.providerId}/${it.modelId}" })
        }
    }

    private fun loadMeta() {
        viewModelScope.launch {
            val agents = runCatching { api.agents() }.getOrDefault(emptyList())
                .filter { it.mode == "primary" || it.mode == "all" }
            val commands = runCatching { api.commands() }.getOrDefault(emptyList())
            val providers = runCatching { api.providers() }.getOrNull()
            val connected = providers?.connected ?: emptyList()
            val models = providers?.all.orEmpty()
                .filter { it.id in connected }
                .flatMap { provider ->
                    provider.models.values.map { model ->
                        ModelOption(
                            provider.id,
                            model.id,
                            "${provider.name} · ${model.name}",
                            model.limit?.context ?: 0L,
                        )
                    }
                }
            val storedModel = app.serverStore.selectedModels.first()[server.id]
            val storedAgent = app.serverStore.selectedAgents.first()[server.id]
            val selectedModel = storedModel?.let { ref ->
                val parts = ref.split("/", limit = 2)
                if (parts.size == 2) {
                    models.firstOrNull { it.providerId == parts[0] && it.modelId == parts[1] }
                } else null
            }
            _ui.update {
                it.copy(agents = agents, models = models, commands = commands, selectedAgent = storedAgent, selectedModel = selectedModel)
            }
        }
    }

    fun onInputChange(value: String) {
        _input.value = value
    }

    fun addAttachment(uri: String) {
        if (_attachments.value.any { it.uri == uri }) return
        viewModelScope.launch {
            val resolved = withContext(Dispatchers.IO) { describe(uri) } ?: return@launch
            _attachments.update { it + resolved }
        }
    }

    fun removeAttachment(uri: String) {
        _attachments.update { list -> list.filterNot { it.uri == uri } }
    }

    private fun describe(uri: String): Attachment? {
        val parsed = runCatching { Uri.parse(uri) }.getOrNull() ?: return null
        val resolver = app.contentResolver
        val mime = resolver.getType(parsed) ?: "application/octet-stream"
        var name = parsed.lastPathSegment?.substringAfterLast('/') ?: "file"
        runCatching {
            resolver.query(parsed, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) cursor.getString(idx)?.let { name = it }
                }
            }
        }
        return Attachment(uri = uri, name = name, mime = mime)
    }

    private fun attachmentPart(attachment: Attachment): PartInput {
        val bytes = app.contentResolver.openInputStream(Uri.parse(attachment.uri))?.use { it.readBytes() }
            ?: throw IOException("Could not read ${attachment.name}")
        if (bytes.size > MAX_ATTACHMENT_BYTES) {
            throw IOException("${attachment.name} is larger than ${MAX_ATTACHMENT_BYTES / (1024 * 1024)} MB")
        }
        val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
        return PartInput(
            type = "file",
            mime = attachment.mime,
            filename = attachment.name,
            url = "data:${attachment.mime};base64,$encoded",
        )
    }

    fun loadOlder() {
        val cursor = nextCursor ?: return
        if (_ui.value.loadingOlder) return
        _ui.update { it.copy(loadingOlder = true) }
        viewModelScope.launch {
            try {
                val response = api.messagesPage(sessionId, PAGE_SIZE, cursor, projectDir())
                val body = response.body() ?: throw IOException("HTTP ${response.code()}")
                val older = body.mapNotNull { element ->
                    runCatching { json.decodeFromJsonElement<MessageData>(element) }.getOrNull()
                }
                nextCursor = response.headers()["X-Next-Cursor"]
                _ui.update { state ->
                    val existingIds = state.messages.mapTo(HashSet()) { it.info.id }
                    state.copy(
                        messages = older.filterNot { it.info.id in existingIds } + state.messages,
                        loadingOlder = false,
                        hasMore = nextCursor != null,
                    )
                }
            } catch (e: Exception) {
                _ui.update { it.copy(loadingOlder = false, error = "Load older messages: ${e.message ?: "unknown error"}") }
            }
        }
    }

    fun send() {
        val text = _input.value.trim()
        val pending = _attachments.value
        if (text.isEmpty() && pending.isEmpty()) return
        val wasBusy = _ui.value.busy
        val match = Regex("^/([A-Za-z0-9_-]+)\\s*(.*)$", RegexOption.DOT_MATCHES_ALL).find(text)
        val commandName = match?.groupValues?.get(1)
        val commandArgs = match?.groupValues?.get(2)?.trim().orEmpty()
        val isCommand = pending.isEmpty() && commandName != null && _ui.value.commands.any { it.name == commandName }
        viewModelScope.launch {
            _ui.update { it.copy(sending = true, queued = if (wasBusy) it.queued + 1 else it.queued) }
            var ok = true
            try {
                if (isCommand && commandName != null) {
                    api.command(
                        sessionId,
                        CommandBody(
                            agent = _ui.value.selectedAgent,
                            model = _ui.value.selectedModel?.modelId,
                            command = commandName,
                            arguments = commandArgs,
                        ),
                        projectDir(),
                    )
                } else {
                    val parts = buildList {
                        if (text.isNotEmpty()) add(PartInput(type = "text", text = text))
                        for (attachment in pending) {
                            add(withContext(Dispatchers.IO) { attachmentPart(attachment) })
                        }
                    }
                    val response = api.sendMessageAsync(
                        sessionId,
                        SendMessageBody(
                            agent = _ui.value.selectedAgent,
                            model = _ui.value.selectedModel?.let { ModelRef(it.providerId, it.modelId) },
                            parts = parts,
                        ),
                        projectDir(),
                    )
                    if (!response.isSuccessful) {
                        ok = false
                        val body = response.errorBody()?.string().orEmpty()
                        throw IOException("Send failed: HTTP ${response.code()} $body")
                    }
                }
                _input.value = ""
                _attachments.value = emptyList()
            } catch (e: Exception) {
                ok = false
                _ui.value = _ui.value.copy(sending = false, error = e.message ?: "Send failed: unknown error")
            }
            // The user message is persisted async; reconcile once, then SSE patches the rest.
            delay(400)
            refreshAll()
            _ui.value = if (ok) _ui.value.copy(sending = false, error = null) else _ui.value.copy(sending = false)
        }
    }

    fun abort() {
        viewModelScope.launch {
            runCatching { api.abort(sessionId, projectDir()) }
            refreshAll()
        }
    }

    fun retryOnError() {
        _ui.value = _ui.value.copy(error = null)
        refreshAll()
    }

    fun deleteMessage(messageId: String) {
        viewModelScope.launch {
            runCatching { api.deleteMessage(sessionId, messageId, projectDir()) }
            refreshAll()
        }
    }

    fun revertTo(messageId: String) {
        viewModelScope.launch {
            runCatching { api.revertSession(sessionId, RevertBody(messageId), projectDir()) }
            refreshAll()
        }
    }

    fun unrevert() {
        viewModelScope.launch {
            runCatching { api.unrevertSession(sessionId, projectDir()) }
            refreshAll()
        }
    }

    fun forkFrom(messageId: String?, onForked: (String) -> Unit) {
        viewModelScope.launch {
            val forked = runCatching { api.forkSession(sessionId, ForkBody(messageId), projectDir()) }.getOrNull()
            if (forked == null) {
                _ui.update { it.copy(error = "Fork failed") }
            } else {
                onForked(forked.id)
            }
        }
    }

    fun summarize() {
        val selected = _ui.value.selectedModel
        val last = _ui.value.messages.lastOrNull { it.info.role == "assistant" }?.info
        val provider = selected?.providerId ?: last?.providerID
        val model = selected?.modelId ?: last?.modelID
        if (provider.isNullOrBlank() || model.isNullOrBlank()) {
            _ui.update { it.copy(error = "Select a model before summarizing") }
            return
        }
        viewModelScope.launch {
            val response = runCatching {
                api.summarizeSession(sessionId, SummarizeBody(provider, model), projectDir())
            }.getOrNull()
            if (response != true) {
                _ui.update { it.copy(error = "Summarize failed") }
            }
            refreshAll()
        }
    }

    // Messages typed while the session is busy are queued server-side; aborting clears that queue.
    fun cancelQueued() {
        viewModelScope.launch {
            runCatching { api.abort(sessionId, projectDir()) }
            _ui.update { it.copy(queued = 0) }
            refreshAll()
        }
    }

    fun refresh() {
        refreshAll()
    }

    fun dismissError() {
        _ui.value = _ui.value.copy(error = null)
    }

    private fun startEvents(app: OpenCodeApp) {
        viewModelScope.launch {
            // Resubscribe when the project folder changes: the SSE stream is scoped by directory,
            // so capturing it once (possibly before it loads) would silently miss every event.
            app.serverStore.currentDirectory
                .distinctUntilChanged()
                .collectLatest { dir ->
                    app.repository.events(server, dir).collect { event ->
                        // Heartbeats alone must not suppress polling — only chat-relevant traffic proves
                        // this stream is actually delivering for our instance.
                        if (event.type != "server.heartbeat") lastEventAt = System.currentTimeMillis()
                        when (event.type) {
                            "message.part.updated" -> applyPartUpdated(event)
                            "message.updated" -> applyMessageUpdated(event)
                            "message.part.removed" -> applyPartRemoved(event)
                            "message.removed" -> applyMessageRemoved(event)
                            "session.status" -> applyStatus(event)
                            "session.idle" -> _ui.update { it.copy(status = SessionStatus(type = "idle"), queued = 0) }
                            "todo.updated" -> applyTodos(event)
                            "server.connected" -> scheduleFullRefresh()
                            "session.updated", "session.diff", "session.compacted" -> scheduleFullRefresh()
                        }
                    }
                }
        }
    }

    // ---- Incremental patching (no network) ----

    private fun applyPartUpdated(event: OcEvent) {
        val properties = event.data as? JsonObject ?: return
        val partJson = properties["part"] ?: return
        val part = runCatching { json.decodeFromJsonElement<Part>(partJson) }.getOrNull() ?: return
        val delta = properties["delta"]?.jsonPrimitive?.contentOrNull
        val messageId = part.messageID
        _ui.update { state ->
            val idx = state.messages.indexOfFirst { it.info.id == messageId }
            if (idx == -1) return@update state
            val message = state.messages[idx]
            val existingIdx = message.parts.indexOfFirst { it.id == part.id }
            val newParts = if (delta != null) {
                val base = if (existingIdx == -1) Part(type = "text", text = "") else message.parts[existingIdx]
                val updated = if (base.type == "text") base.copy(text = base.text + delta) else part
                if (existingIdx == -1) message.parts + updated else message.parts.toMutableList().also { it[existingIdx] = updated }
            } else {
                if (existingIdx == -1) message.parts + part else message.parts.toMutableList().also { it[existingIdx] = part }
            }
            val list = state.messages.toMutableList()
            list[idx] = message.copy(parts = newParts)
            state.copy(messages = list)
        }
    }

    private fun applyMessageUpdated(event: OcEvent) {
        val properties = event.data as? JsonObject ?: return
        val info = properties["info"] ?: return
        val message = runCatching { json.decodeFromJsonElement<Message>(info) }.getOrNull() ?: return
        _ui.update { state ->
            val idx = state.messages.indexOfFirst { it.info.id == message.id }
            val list = state.messages.toMutableList()
            if (idx == -1) {
                list.add(MessageData(info = message))
            } else {
                list[idx] = list[idx].copy(info = message)
            }
            state.copy(messages = list)
        }
    }

    private fun applyPartRemoved(event: OcEvent) {
        val properties = event.data as? JsonObject ?: return
        val messageId = properties["messageID"]?.jsonPrimitive?.contentOrNull ?: return
        val partId = properties["partID"]?.jsonPrimitive?.contentOrNull ?: return
        _ui.update { state ->
            val idx = state.messages.indexOfFirst { it.info.id == messageId }
            if (idx == -1) return@update state
            val message = state.messages[idx]
            val list = state.messages.toMutableList()
            list[idx] = message.copy(parts = message.parts.filterNot { it.id == partId })
            state.copy(messages = list)
        }
    }

    private fun applyMessageRemoved(event: OcEvent) {
        val properties = event.data as? JsonObject ?: return
        val messageId = properties["messageID"]?.jsonPrimitive?.contentOrNull ?: return
        _ui.update { state -> state.copy(messages = state.messages.filterNot { it.info.id == messageId }) }
    }

    private fun applyStatus(event: OcEvent) {
        val properties = event.data as? JsonObject ?: return
        val statusJson = properties["status"] ?: return
        val status = runCatching { json.decodeFromJsonElement<SessionStatus>(statusJson) }.getOrNull() ?: return
        _ui.update { it.copy(status = status, queued = if (status.type == "idle") 0 else it.queued) }
    }

    private fun applyTodos(event: OcEvent) {
        val properties = event.data as? JsonObject ?: return
        val todosJson = properties["todos"] as? JsonArray ?: return
        val todos = runCatching { json.decodeFromJsonElement<List<Todo>>(todosJson) }.getOrNull() ?: return
        _ui.update { it.copy(todos = todos) }
    }

    private fun scheduleFullRefresh() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            delay(400)
            refreshAll()
        }
    }

    // ---- Polling: adaptive + power aware ----

    private fun startPolling() {
        viewModelScope.launch {
            while (true) {
                val interval = when {
                    _ui.value.busy -> 3000L
                    isForeground() -> 15_000L
                    else -> 60_000L
                }
                delay(interval)
                // The SSE stream (including server.heartbeat) already keeps us live — skip the
                // network round-trip while it is healthy. Resume polling after a heartbeat gap.
                if (sseLive()) continue
                refreshAll()
            }
        }
    }

    private fun sseLive(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastEventAt > SSE_TIMEOUT_MS) return false
        // Safety net: reconcile once every few minutes even while connected.
        if (now - lastRefreshAt > 5 * 60_000L) return false
        return true
    }

    private fun refreshAll() {
        viewModelScope.launch {
            lastRefreshAt = System.currentTimeMillis()
            try {
                val status = runCatching { api.sessionStatus(projectDir())[sessionId] }.getOrNull()
                val session = runCatching { api.session(sessionId, projectDir()) }.getOrNull()
                val page = api.messagesPage(sessionId, PAGE_SIZE, null, projectDir())
                val body = page.body() ?: throw IOException("HTTP ${page.code()}")
                val messages = body.mapNotNull { element ->
                    runCatching { json.decodeFromJsonElement<MessageData>(element) }.getOrNull()
                }
                val cursor = page.headers()["X-Next-Cursor"]
                if (nextCursor == null) nextCursor = cursor
                val todos = runCatching { api.todos(sessionId, projectDir()) }.getOrNull()
                runCatching { app.cacheStore.put(messagesKey, json.encodeToString(messages)) }
                // Preserve existing error so the banner stays visible until dismissed or a send succeeds.
                _ui.value = _ui.value.copy(
                    messages = mergePage(_ui.value.messages, messages),
                    session = session,
                    status = status,
                    todos = todos ?: _ui.value.todos,
                    hasMore = nextCursor != null,
                    loading = false,
                )
                updateWidget(session, status, _ui.value.messages)
            } catch (e: Exception) {
                _ui.value = _ui.value.copy(loading = false, error = "Load messages: ${e.message ?: "unknown error"}")
            }
        }
    }

    private fun updateWidget(session: Session?, status: SessionStatus?, messages: List<MessageData>) {
        val snippet = messages.lastOrNull { it.info.role == "assistant" }
            ?.parts?.firstOrNull { it.type == "text" }?.text?.trim()?.take(160)
        val widget = dev.opencode.mobile.notify.StatusWidget
        try {
            widget.update(
                app,
                session?.title.orEmpty(),
                status?.type ?: "idle",
                snippet ?: "No messages yet",
            )
        } catch (_: Throwable) {
        }
    }

    // The latest page is fetched on every refresh; anything already loaded above it is older and kept.
    private fun mergePage(existing: List<MessageData>, page: List<MessageData>): List<MessageData> {
        if (existing.isEmpty() || page.isEmpty()) return page.ifEmpty { existing }
        val pageIds = page.mapTo(HashSet()) { it.info.id }
        val pageFirstTime = page.first().info.time?.created ?: Long.MIN_VALUE
        val older = existing.filter { it.info.id !in pageIds && (it.info.time?.created ?: Long.MIN_VALUE) < pageFirstTime }
        return older + page
    }

    companion object {
        private const val PAGE_SIZE = 50
        private const val MAX_ATTACHMENT_BYTES = 16 * 1024 * 1024
        private const val SSE_TIMEOUT_MS = 45_000L
    }
}