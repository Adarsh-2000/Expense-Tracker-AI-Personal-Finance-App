package com.ledgernoir.tracker.ui.portfolio

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ledgernoir.tracker.domain.usecase.GetPortfolioUseCase
import com.ledgernoir.tracker.domain.usecase.SyncPortfolioUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Owns the Portfolio data + sync. On construction it fetches the portfolio and,
 * if it's empty/zero-valued, auto-syncs once and re-fetches so the first visit
 * populates — mirroring the original LaunchedEffect(Unit). The manual "Sync now"
 * action runs the same sync with the syncing flag + status line.
 */
class PortfolioViewModel(
    private val getPortfolio: GetPortfolioUseCase,
    private val syncPortfolio: SyncPortfolioUseCase,
) : ViewModel() {

    private val _uiState = MutableStateFlow(PortfolioUiState())
    val uiState: StateFlow<PortfolioUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(loading = true)
            reload()
            val current = _uiState.value.portfolio
            if (current == null || current.holdings.isEmpty() || current.totalValue <= 0.0) {
                runSync()
            }
            _uiState.value = _uiState.value.copy(loading = false)
        }
    }

    fun setFilter(filter: HoldingFilter) {
        _uiState.value = _uiState.value.copy(filter = filter)
    }

    /** Manual sync triggered from the UI; guards against re-entry while syncing. */
    fun sync() {
        if (_uiState.value.syncing) return
        _uiState.value = _uiState.value.copy(syncing = true, statusLine = "Syncing…")
        viewModelScope.launch {
            runSync()
            _uiState.value = _uiState.value.copy(syncing = false)
        }
    }

    private suspend fun reload() {
        getPortfolio().onSuccess { _uiState.value = _uiState.value.copy(portfolio = it) }
    }

    private suspend fun runSync() {
        syncPortfolio()
            .onSuccess { result ->
                val count = result.holdings
                val line = when {
                    result.detail != null -> result.detail
                    count != null -> "Updated · $count holdings"
                    else -> "Synced"
                }
                _uiState.value = _uiState.value.copy(statusLine = line)
            }
            .onFailure {
                _uiState.value = _uiState.value.copy(
                    statusLine = "Sync failed. Set your PAN in profile if you haven't.",
                )
            }
        reload()
    }
}
