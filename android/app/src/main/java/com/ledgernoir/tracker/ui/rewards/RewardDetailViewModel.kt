package com.ledgernoir.tracker.ui.rewards

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ledgernoir.tracker.domain.usecase.GetRewardsUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Backs [RewardDetailScreen]. Fetches /rewards and exposes the single card matching
 * [bank]/[last4]. Reuses GetRewardsUseCase (no dedicated endpoint — only a couple of
 * cards). refresh() re-fetches on resume.
 */
class RewardDetailViewModel(
    private val getRewards: GetRewardsUseCase,
    private val bank: String,
    private val last4: String,
) : ViewModel() {

    private val _uiState = MutableStateFlow(RewardDetailUiState())
    val uiState: StateFlow<RewardDetailUiState> = _uiState.asStateFlow()

    init { load() }

    fun refresh() = load()

    private fun load() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(loading = true)
            getRewards().onSuccess { resp ->
                val match = resp.cards.firstOrNull {
                    it.bank.equals(bank, ignoreCase = true) && it.last4 == last4
                }
                _uiState.value = _uiState.value.copy(card = match)
            }
            _uiState.value = _uiState.value.copy(loading = false)
        }
    }
}
