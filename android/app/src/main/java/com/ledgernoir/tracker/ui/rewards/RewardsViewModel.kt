package com.ledgernoir.tracker.ui.rewards

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ledgernoir.tracker.domain.usecase.GetRewardsUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Owns the rewards fetch. Initial load shows a spinner; refresh() (resume +
 * pull-to-refresh) re-fetches silently. Mirrors NetWorthViewModel.
 */
class RewardsViewModel(
    private val getRewards: GetRewardsUseCase,
) : ViewModel() {

    private val _uiState = MutableStateFlow(RewardsUiState())
    val uiState: StateFlow<RewardsUiState> = _uiState.asStateFlow()

    init { load(showSpinner = true) }

    fun refresh() = load(showSpinner = false)

    private fun load(showSpinner: Boolean) {
        viewModelScope.launch {
            _uiState.value =
                if (showSpinner) _uiState.value.copy(loading = true)
                else _uiState.value.copy(refreshing = true)
            getRewards().onSuccess {
                _uiState.value = _uiState.value.copy(
                    totalValueInr = it.totalValueInr,
                    totalPoints = it.totalPoints,
                    cards = it.cards,
                )
            }
            _uiState.value = _uiState.value.copy(loading = false, refreshing = false)
        }
    }
}
