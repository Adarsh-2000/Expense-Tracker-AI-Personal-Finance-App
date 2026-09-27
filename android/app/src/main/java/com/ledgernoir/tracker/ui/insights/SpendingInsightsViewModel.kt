package com.ledgernoir.tracker.ui.insights

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ledgernoir.tracker.domain.usecase.GetRailsUseCase
import com.ledgernoir.tracker.domain.usecase.GetSpendingPatternsUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * Owns the "How I Pay" data + business logic: preset→range resolution, the rails +
 * spending-patterns fetch (re-run whenever the range changes), and the per-rail
 * expand/collapse selection. Default preset is Last 3M, matching the original.
 */
class SpendingInsightsViewModel(
    private val getRails: GetRailsUseCase,
    private val getSpendingPatterns: GetSpendingPatternsUseCase,
) : ViewModel() {

    private val today: LocalDate = LocalDate.now()

    private val _uiState: MutableStateFlow<SpendingInsightsUiState>
    val uiState: StateFlow<SpendingInsightsUiState>

    init {
        val (start, end) = presetRange(PayRangePreset.LAST_3M, today)
        _uiState = MutableStateFlow(
            SpendingInsightsUiState(preset = PayRangePreset.LAST_3M, startIso = start, endIso = end),
        )
        uiState = _uiState.asStateFlow()
        load()
    }

    /** Apply a preset: resolve the rolling range and refetch. */
    fun selectPreset(preset: PayRangePreset) {
        val (s, e) = presetRange(preset, today)
        _uiState.value = _uiState.value.copy(preset = preset, startIso = s, endIso = e)
        load()
    }

    /** Toggle a rail row's expanded (item-detail) state. */
    fun toggleRail(railKey: String) {
        val current = _uiState.value.expandedRails
        _uiState.value = _uiState.value.copy(
            expandedRails = if (current.contains(railKey)) current - railKey else current + railKey,
        )
    }

    private fun load() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(loading = true)
            val s = _uiState.value.startIso
            val e = _uiState.value.endIso
            getRails(s, e)
                .onSuccess { _uiState.value = _uiState.value.copy(rails = it) }
                .onFailure { _uiState.value = _uiState.value.copy(rails = null) }
            getSpendingPatterns(s, e)
                .onSuccess { _uiState.value = _uiState.value.copy(patterns = it) }
                .onFailure { _uiState.value = _uiState.value.copy(patterns = null) }
            _uiState.value = _uiState.value.copy(loading = false)
        }
    }

    companion object {
        /**
         * Resolves a preset to an inclusive-start / EXCLUSIVE-end ISO pair (matches
         * the Insights screen exactly: rolling windows, end = today + 1).
         */
        fun presetRange(preset: PayRangePreset, today: LocalDate): Pair<String, String> {
            val endExclusive = today.plusDays(1)
            val start = when (preset) {
                PayRangePreset.THIS_MONTH -> today.withDayOfMonth(1)
                PayRangePreset.LAST_3M    -> today.minusDays(90)
                PayRangePreset.LAST_6M    -> today.minusDays(180)
            }
            return start.toString() to endExclusive.toString()
        }
    }
}
