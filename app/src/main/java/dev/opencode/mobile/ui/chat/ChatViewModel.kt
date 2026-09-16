package dev.opencode.mobile.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.opencode.mobile.OpenCodeApp
import dev.opencode.mobile.data.model.MessageData
import dev.opencode.mobile.data.model.PartInput
import dev.opencode.mobile.data.model.SendMessageBody
import dev.opencode.mobile.data.model.Session
import dev.opencode.mobile.data.model.SessionStatus
import dev.opencode.mobile.data.net.ServerConfig
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import java.io.IOException

class ChatViewModel(
    app: OpenCodeApp,
    private val server: ServerConfig,
    private val sessionId: String,
    private val projectDir: () -> String?,
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
            try {
                val response = api.sendMessageAsync(
                    sessionId,
                    SendMessageBody(parts = listOf(PartInput(type = "text", text = text))),
                    projectDir(),
                )
                if (!response.isSuccessful) {
                    val body = response.errorBody()?.string().orEmpty()
                    throw IOException("Send failed: HTTP ${response.code()} $body")
                }
                _input.value = ""
            } catch (e: Exception) {
                _ui.value = _ui.value.copy(sending = false, error = e.message ?: "Send failed: unknown error")
            }
            // The server persists async; keep refreshing until the message shows up.
            repeat(7) {
                delay(500)
                refreshAll()
            }
            _ui.value = _ui.value.copy(sending = false)
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
            app.repository.events(server, projectDir())
                .filter { it.type in REFRESH_EVENT_TYPES }
                .debounce(300)
                .collect { refreshAll() }
        }
    }

    private fun startPolling() {
        // Poll fallback so the chat stays live even if SSE is down or events are missed.
        viewModelScope.launch {
            while (true) {
                delay(3000)
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
                _ui.value = UiState(messages = messages, session = session, status = status, loading = false)
            } catch (e: Exception) {
                _ui.value = _ui.value.copy(loading = false, error = "Load messages: ${e.message ?: "unknown error"}")
            }
        }
    }

    private companion object {
        val REFRESH_EVENT_TYPES = setOf(
            "session.status", "session.idle", "session.diff",
            "message.part.updated", "message.part.removed",
            "message.updated", "message.removed",
            "session.compacted", "todo.updated",
        )
    }
}