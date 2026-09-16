package dev.opencode.mobile.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.opencode.mobile.OpenCodeApp
import dev.opencode.mobile.data.model.Message
import dev.opencode.mobile.data.model.MessageData
import dev.opencode.mobile.data.model.Part
import dev.opencode.mobile.data.model.PartInput
import dev.opencode.mobile.data.model.SendMessageBody
import dev.opencode.mobile.data.model.Session
import dev.opencode.mobile.data.model.SessionStatus
import dev.opencode.mobile.data.net.OcEvent
import dev.opencode.mobile.data.net.ServerConfig
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException

class ChatViewModel(
    app: OpenCodeApp,
    private val server: ServerConfig,
    private val sessionId: String,
    private val projectDir: () -> String?,
    private val isForeground: () -> Boolean = { true },
) : ViewModel() {

    data class UiState(
        val messages: List<MessageData> = emptyList(),
        val session: Session? = null,
        val status: SessionStatus? = null,
        val loading: Boolean = true,
        val error: String? = null,
        val sending: Boolean = false,
    ) {
        val busy: Boolean get() = status?.type == "busy" || status?.type == "retry"
    }

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui

    private val _input = MutableStateFlow("")
    val input: StateFlow<String> = _input

    private val api = app.repository.apiFor(server)
    private val json = Json { ignoreUnknownKeys = true }

    private var refreshJob: Job? = null

    init {
        refreshAll()
        startEvents(app)
        startPolling()
    }

    fun onInputChange(value: String) {
        _input.value = value
    }

    fun send() {
        val text = _input.value.trim()
        if (text.isEmpty()) return
        viewModelScope.launch {
            _ui.value = _ui.value.copy(sending = true)
            var ok = true
            try {
                val response = api.sendMessageAsync(
                    sessionId,
                    SendMessageBody(parts = listOf(PartInput(type = "text", text = text))),
                    projectDir(),
                )
                if (!response.isSuccessful) {
                    ok = false
                    val body = response.errorBody()?.string().orEmpty()
                    throw IOException("Send failed: HTTP ${response.code()} $body")
                }
                _input.value = ""
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

    fun dismissError() {
        _ui.value = _ui.value.copy(error = null)
    }

    private fun startEvents(app: OpenCodeApp) {
        viewModelScope.launch {
            app.repository.events(server, projectDir()).collect { event ->
                when (event.type) {
                    "message.part.updated" -> applyPartUpdated(event)
                    "message.updated" -> applyMessageUpdated(event)
                    "message.part.removed" -> applyPartRemoved(event)
                    "message.removed" -> applyMessageRemoved(event)
                    "session.status" -> applyStatus(event)
                    "session.idle" -> _ui.update { it.copy(status = SessionStatus(type = "idle")) }
                    "server.connected" -> scheduleFullRefresh()
                    "session.updated", "session.diff", "session.compacted", "todo.updated" -> scheduleFullRefresh()
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
        _ui.update { it.copy(status = status) }
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
                refreshAll()
            }
        }
    }

    private fun refreshAll() {
        viewModelScope.launch {
            try {
                val status = runCatching { api.sessionStatus(projectDir())[sessionId] }.getOrNull()
                val session = runCatching { api.session(sessionId, projectDir()) }.getOrNull()
                val messages = api.messages(sessionId, directory = projectDir())
                // Preserve existing error so the banner stays visible until dismissed or a send succeeds.
                _ui.value = _ui.value.copy(messages = messages, session = session, status = status, loading = false)
            } catch (e: Exception) {
                _ui.value = _ui.value.copy(loading = false, error = "Load messages: ${e.message ?: "unknown error"}")
            }
        }
    }
}