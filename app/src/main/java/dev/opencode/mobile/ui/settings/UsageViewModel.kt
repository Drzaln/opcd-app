package dev.opencode.mobile.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.opencode.mobile.OpenCodeApp
import dev.opencode.mobile.data.model.GoUsage
import dev.opencode.mobile.data.net.GoUsageClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class UsageUiState(
    val usage: GoUsage? = null,
    val loading: Boolean = false,
    val error: String? = null,
)

class UsageViewModel(app: OpenCodeApp) : ViewModel() {

    private val store = app.serverStore
    private val client = GoUsageClient()

    val apiKey: StateFlow<String> = store.openCodeGoApiKey
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    private val _state = MutableStateFlow(UsageUiState())
    val state: StateFlow<UsageUiState> = _state

    fun saveKey(value: String) {
        viewModelScope.launch {
            store.setOpenCodeGoApiKey(value.trim().ifBlank { null })
            refresh()
        }
    }

    fun refresh() {
        if (_state.value.loading) return
        viewModelScope.launch {
            val key = store.openCodeGoApiKey.first()
            if (key.isBlank()) {
                _state.value = UsageUiState()
                return@launch
            }
            _state.value = _state.value.copy(loading = true, error = null)
            _state.value = runCatching { client.fetch(key) }.fold(
                onSuccess = { UsageUiState(usage = it) },
                onFailure = { _state.value.copy(loading = false, error = it.message ?: "Failed to load usage") },
            )
        }
    }
}
