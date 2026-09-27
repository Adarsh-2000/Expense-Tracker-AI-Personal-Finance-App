package com.ledgernoir.tracker.ui.insights

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ledgernoir.tracker.domain.usecase.GetInsightSummaryUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Loads the per-month LLM summary; the month stepper can't advance past the current month.
 *  A failed/unavailable load leaves [AiInsightsUiState.summary] null so the screen shows the
 *  subtle placeholder (the backend returns available=false rather than erroring). */
class AiInsightsViewModel(
    private val getInsightSummary: GetInsightSummaryUseCase,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AiInsightsUiState(month = MonthStepper.current()))
    val uiState: StateFlow<AiInsightsUiState> = _uiState.asStateFlow()

    init { load() }

    private fun load() {
        val month = _uiState.value.month
        _uiState.value = _uiState.value.copy(loading = true, canGoForward = MonthStepper.canGoForward(month))
        viewModelScope.launch {
            getInsightSummary(MonthStepper.apiParam(month))
                .onSuccess { _uiState.value = _uiState.value.copy(summary = it, loading = false) }
                .onFailure { _uiState.value = _uiState.value.copy(summary = null, loading = false) }
        }
    }

    fun prevMonth() {
        _uiState.value = _uiState.value.copy(month = MonthStepper.prev(_uiState.value.month))
        load()
    }

    fun nextMonth() {
        val month = _uiState.value.month
        if (!MonthStepper.canGoForward(month)) return
        _uiState.value = _uiState.value.copy(month = MonthStepper.next(month))
        load()
    }
}
