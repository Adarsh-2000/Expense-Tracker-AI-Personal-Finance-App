package com.ledgernoir.tracker.ui.insights

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ledgernoir.tracker.domain.usecase.DismissFindingUseCase
import com.ledgernoir.tracker.domain.usecase.GetMoneyRadarUseCase
import com.ledgernoir.tracker.domain.usecase.MarkRadarSeenUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class MoneyRadarViewModel(
    private val getMoneyRadar: GetMoneyRadarUseCase,
    private val markRadarSeen: MarkRadarSeenUseCase,
    private val dismissFinding: DismissFindingUseCase,
) : ViewModel() {

    private val _uiState = MutableStateFlow(MoneyRadarUiState())
    val uiState: StateFlow<MoneyRadarUiState> = _uiState.asStateFlow()

    init { load() }

    fun load() {
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true, error = null) }
            getMoneyRadar().onSuccess { resp ->
                _uiState.update {
                    it.copy(loading = false, findings = resp.findings, newCount = resp.newCount)
                }
                // Opening the screen clears the "new" state server-side.
                markRadarSeen()
            }.onFailure { e ->
                _uiState.update { it.copy(loading = false, error = e.message ?: "Failed to load") }
            }
        }
    }

    fun dismiss(id: Int) {
        viewModelScope.launch {
            dismissFinding(id).onSuccess {
                _uiState.update { s -> s.copy(findings = s.findings.filterNot { it.id == id }) }
            }
        }
    }
}
