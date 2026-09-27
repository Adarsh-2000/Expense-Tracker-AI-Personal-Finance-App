package com.ledgernoir.tracker.ui.networth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ledgernoir.tracker.domain.usecase.GetNetWorthFullUseCase
import com.ledgernoir.tracker.domain.usecase.GetProjectionUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Owns the Net Worth data fetch: the full net-worth breakdown + the 10-year
 * projection. The initial load shows a spinner; refresh() (used on resume and
 * pull-to-refresh) re-fetches silently.
 */
class NetWorthViewModel(
    private val getNetWorthFull: GetNetWorthFullUseCase,
    private val getProjection: GetProjectionUseCase,
) : ViewModel() {

    private val _uiState = MutableStateFlow(NetWorthUiState())
    val uiState: StateFlow<NetWorthUiState> = _uiState.asStateFlow()

    init { load(showSpinner = true) }

    /** Re-fetch the breakdown + projection. Used on resume and pull-to-refresh. */
    fun refresh() = load(showSpinner = false)

    private fun load(showSpinner: Boolean) {
        viewModelScope.launch {
            _uiState.value =
                if (showSpinner) _uiState.value.copy(loading = true)
                else _uiState.value.copy(refreshing = true)
            getNetWorthFull().onSuccess {
                _uiState.value = _uiState.value.copy(components = it.components, total = it.netWorth)
            }
            getProjection(10).onSuccess {
                _uiState.value = _uiState.value.copy(projection = it)
            }
            _uiState.value = _uiState.value.copy(loading = false, refreshing = false)
        }
    }
}
