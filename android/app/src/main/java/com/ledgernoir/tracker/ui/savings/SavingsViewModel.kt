package com.ledgernoir.tracker.ui.savings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ledgernoir.tracker.data.api.FixedDepositPayload
import com.ledgernoir.tracker.domain.usecase.AddFixedDepositUseCase
import com.ledgernoir.tracker.domain.usecase.DeleteFixedDepositUseCase
import com.ledgernoir.tracker.domain.usecase.GetSavingsUseCase
import com.ledgernoir.tracker.domain.usecase.SetBalanceUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Owns the Savings data + mutations: the initial fetch, manual balance edits,
 * adding/deleting fixed deposits, and the reload-after-action. Each mutation
 * re-fetches savings so the UI reflects the new state — matching the original.
 */
class SavingsViewModel(
    private val getSavings: GetSavingsUseCase,
    private val setBalanceUseCase: SetBalanceUseCase,
    private val addFixedDepositUseCase: AddFixedDepositUseCase,
    private val deleteFixedDepositUseCase: DeleteFixedDepositUseCase,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SavingsUiState())
    val uiState: StateFlow<SavingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(loading = true)
            reload()
            _uiState.value = _uiState.value.copy(loading = false)
        }
    }

    fun setBalance(accountId: Int, balance: Double) {
        viewModelScope.launch {
            setBalanceUseCase(accountId, balance, null)
            reload()
        }
    }

    fun addFixedDeposit(fd: FixedDepositPayload) {
        viewModelScope.launch {
            addFixedDepositUseCase(fd)
            reload()
        }
    }

    fun deleteFixedDeposit(id: Int) {
        viewModelScope.launch {
            deleteFixedDepositUseCase(id)
            reload()
        }
    }

    private suspend fun reload() {
        getSavings().onSuccess { _uiState.value = _uiState.value.copy(data = it) }
    }
}
