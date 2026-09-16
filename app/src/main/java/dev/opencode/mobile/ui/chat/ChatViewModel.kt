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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class ChatViewModel(
    app: OpenCodeApp,
    private val server: ServerConfig,
    private val sessionId: String,
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
    }

    fun onInputChange(value: String) {
        _input.value = value
    }

    fun send() {
        val text = _input.value.trim()
        if (text.isEmpty() || _ui.value.busy) return
        viewModelScope.launch {
            _ui.value = _ui.value.copy(sending = true)
            try {
                api.sendMessageAsync(
                    sessionId,
                    SendMessageBody(parts = listOf(PartInput(type = "text", text = text))),
                )
                _input.value = ""
            } catch (e: Exception) {
                _ui.value = _ui.value.copy(sending = false, error = e.message ?: "Failed to send message")
            }
            refreshAll()
            _ui.value = _ui.value.copy(sending = false)
        }
    }

    fun abort() {
        viewModelScope.launch {
            runCatching { api.abort(sessionId) }
            refreshAll()
        }
    }

    fun retryOnError() {
        _ui.value = _ui.value.copy(error = null)
        refreshAll()
    }

    private fun startEvents(app: OpenCodeApp) {
        viewModelScope.launch {
            app.repository.events(server).collectLatest { event ->
                when (event.type) {
                    "session.status", "session.idle", "session.diff",
                    "message.part.updated", "message.part.removed",
                    "message.updated", "message.removed",
                    "session.compacted", "todo.updated",
                    -> {
                        delay(250)
                        refreshAll()
                    }
                }
            }
        }
    }

    private fun refreshAll() {
        viewModelScope.launch {
            try {
                val status = runCatching { api.sessionStatus()[sessionId] }.getOrNull()
                val session = runCatching { api.session(sessionId) }.getOrNull()
                val messages = api.messages(sessionId)
                _ui.value = UiState(messages = messages, session = session, status = status, loading = false)
            } catch (e: Exception) {
                _ui.value = _ui.value.copy(loading = false, error = e.message ?: "Failed to load messages")
            }
        }
    }
}