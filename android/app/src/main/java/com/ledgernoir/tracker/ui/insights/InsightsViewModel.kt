package com.ledgernoir.tracker.ui.insights

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ledgernoir.tracker.domain.usecase.GetBreakdownUseCase
import com.ledgernoir.tracker.domain.usecase.GetCategoriesUseCase
import com.ledgernoir.tracker.domain.usecase.GetRadarSummaryUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * Owns all Insights data-fetching + business logic: preset→range resolution, the
 * breakdown fetch, category drill-down id lookup, and the filter/expand selections.
 */
class InsightsViewModel(
    private val getCategories: GetCategoriesUseCase,
    private val getBreakdown: GetBreakdownUseCase,
    private val getRadarSummary: GetRadarSummaryUseCase,
) : ViewModel() {

    private val today: LocalDate = LocalDate.now()

    private val _uiState: MutableStateFlow<InsightsUiState>
    val uiState: StateFlow<InsightsUiState>

    init {
        val (start, end) = presetRange(RangePreset.LAST_3M, today)
        _uiState = MutableStateFlow(
            InsightsUiState(preset = RangePreset.LAST_3M, startIso = start, endIso = end),
        )
        uiState = _uiState.asStateFlow()

        // One-time category name→id map for drill-down.
        viewModelScope.launch {
            getCategories().onSuccess { list ->
                _uiState.value = _uiState.value.copy(
                    categoryIdByName = list.associate { it.name.lowercase().trim() to it.id },
                )
            }
        }

        // New-finding count for the Money Radar hub-tile badge (fetched once).
        viewModelScope.launch {
            getRadarSummary().onSuccess { summary ->
                _uiState.value = _uiState.value.copy(radarNewCount = summary.newCount)
            }
        }

        // Initial breakdown for the default range.
        loadBreakdown()
    }

    /** Apply a preset. CUSTOM opens the picker; others resolve + refetch. */
    fun selectPreset(preset: RangePreset) {
        if (preset == RangePreset.CUSTOM) {
            _uiState.value = _uiState.value.copy(preset = preset, showCustomPicker = true)
        } else {
            val (s, e) = presetRange(preset, today)
            _uiState.value = _uiState.value.copy(preset = preset, startIso = s, endIso = e)
            loadBreakdown()
        }
    }

    /** Apply a custom range chosen in the picker dialog. */
    fun applyCustomRange(startIso: String, endExclusiveIso: String) {
        _uiState.value = _uiState.value.copy(
            startIso = startIso,
            endIso = endExclusiveIso,
            preset = RangePreset.CUSTOM,
            showCustomPicker = false,
        )
        loadBreakdown()
    }

    fun dismissCustomPicker() {
        _uiState.value = _uiState.value.copy(showCustomPicker = false)
    }

    /** Select a category for the donut highlight + list filter. Null or same value = clear. */
    fun selectCategory(name: String?) {
        val current = _uiState.value.selectedCategory
        _uiState.value = _uiState.value.copy(
            selectedCategory = if (name != null && !name.equals(current, ignoreCase = true)) name else null,
        )
    }

    /** Toggle a category's expanded (sub-row) state. */
    fun toggleExpanded(category: String) {
        val current = _uiState.value.expanded
        _uiState.value = _uiState.value.copy(
            expanded = if (current.contains(category)) current - category else current + category,
        )
    }

    private fun loadBreakdown() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(loading = true)
            val s = _uiState.value.startIso
            val e = _uiState.value.endIso
            getBreakdown(s, e)
                .onSuccess { _uiState.value = _uiState.value.copy(breakdown = it) }
                .onFailure { _uiState.value = _uiState.value.copy(breakdown = null) }
            _uiState.value = _uiState.value.copy(loading = false)
        }
    }

    companion object {
        /**
         * Resolves a preset to an inclusive-start / EXCLUSIVE-end ISO pair.
         * - This Month -> [first of this month, today+1)
         * - Last 3M    -> [today - 90 days, today+1)  (rolling 90 days, incl. today)
         * - Last 6M    -> [today - 180 days, today+1)
         * Last 3M uses a rolling 90-day window so it matches the Dashboard "Last 3
         * Months" card exactly (same definition the user expects: now − 90 days).
         */
        fun presetRange(preset: RangePreset, today: LocalDate): Pair<String, String> {
            val endExclusive = today.plusDays(1)
            val start = when (preset) {
                RangePreset.THIS_MONTH -> today.withDayOfMonth(1)
                RangePreset.LAST_3M    -> today.minusDays(90)
                RangePreset.LAST_6M    -> today.minusDays(180)
                RangePreset.CUSTOM     -> today.minusDays(90)
            }
            return start.toString() to endExclusive.toString()
        }
    }
}
