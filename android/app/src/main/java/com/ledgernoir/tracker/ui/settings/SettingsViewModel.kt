package com.ledgernoir.tracker.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ledgernoir.tracker.data.TokenStore
import com.ledgernoir.tracker.domain.usecase.SyncGmailUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Owns the Settings screen: tracks the persisted backend URL (collected from the
 * [TokenStore] flow), the editable draft, save state, the Gmail-sync action, and
 * the lock-now (clear-token) action.
 */
class SettingsViewModel(
    private val tokenStore: TokenStore,
    private val syncGmail: SyncGmailUseCase,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        // Mirror the original `collectAsState` on baseUrl: when the persisted URL
        // changes, re-seed the draft (matching `remember(savedUrl)`).
        viewModelScope.launch {
            tokenStore.baseUrl.collect { url ->
                _uiState.value = _uiState.value.copy(savedUrl = url, draftUrl = url)
            }
        }
    }

    fun onDraftUrlChange(url: String) {
        _uiState.value = _uiState.value.copy(draftUrl = url, saved = false)
    }

    fun saveUrl() {
        viewModelScope.launch {
            tokenStore.setBaseUrl(_uiState.value.draftUrl)
            _uiState.value = _uiState.value.copy(saved = true)
        }
    }

    fun lockNow() {
        viewModelScope.launch { tokenStore.clearToken() }
    }

    fun syncEmail() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(syncing = true, syncStatus = null)
            syncGmail()
                .onSuccess { sync ->
                    val status = if (sync.detail != null) {
                        sync.detail
                    } else {
                        "Imported ${sync.ingested} transactions from ${sync.fetched} emails"
                    }
                    _uiState.value = _uiState.value.copy(syncStatus = status)
                }
                .onFailure { error ->
                    _uiState.value = _uiState.value.copy(syncStatus = "Sync failed: ${error.message}")
                }
            _uiState.value = _uiState.value.copy(syncing = false)
        }
    }
}
