package com.ledgernoir.tracker.ui.transactions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ledgernoir.tracker.domain.usecase.GetCategoriesUseCase
import com.ledgernoir.tracker.domain.usecase.GetSpentRangeUseCase
import com.ledgernoir.tracker.domain.usecase.GetTransactionsUseCase
import com.ledgernoir.tracker.domain.usecase.SearchTransactionsUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.format.DateTimeFormatter

/**
 * Owns the Transactions screen data + filters: loads the (filtered) list, computes
 * the optional range-spent total, and derives the grouped display list. The manual
 * add path posts via the repository and refreshes the list.
 */
class TransactionsViewModel(
    private val getCategories: GetCategoriesUseCase,
    private val getTransactions: GetTransactionsUseCase,
    private val searchTransactions: SearchTransactionsUseCase,
    private val getSpentRange: GetSpentRangeUseCase,
) : ViewModel() {

    private val isoDate: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE

    private val _uiState = MutableStateFlow(TransactionsUiState())
    val uiState: StateFlow<TransactionsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            getCategories().onSuccess { list ->
                _uiState.value = _uiState.value.copy(
                    categoryNames = list.associate { it.id to it.name },
                )
                regroup()
            }
        }
        reload()
    }

    // ── Filter events ────────────────────────────────────────────────────────

    fun onQueryChange(q: String) {
        _uiState.value = _uiState.value.copy(query = q)
    }

    fun onFromDateSelected(date: java.time.LocalDate) {
        _uiState.value = _uiState.value.copy(fromDate = date)
    }

    fun onToDateSelected(date: java.time.LocalDate) {
        _uiState.value = _uiState.value.copy(toDate = date)
    }

    fun selectDirection(direction: String?) {
        _uiState.value = _uiState.value.copy(filterDirection = direction)
        reload()
    }

    fun clearFilters() {
        _uiState.value = _uiState.value.copy(
            query = "",
            fromDate = null,
            toDate = null,
            rangeSpent = null,
        )
        reload()
    }

    /** Loads either the full list (no filters) or a filtered search + range spent. */
    fun reload() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(loading = true)
            val s = _uiState.value
            val q = s.query.trim().ifBlank { null }
            val hasRange = s.fromDate != null || s.toDate != null
            if (q == null && !hasRange && s.filterDirection == null) {
                _uiState.value = _uiState.value.copy(rangeSpent = null)
                getTransactions().onSuccess { list ->
                    _uiState.value = _uiState.value.copy(
                        transactions = list.sortedByDescending { t -> t.occurredAt },
                    )
                }
            } else {
                val startIso = s.fromDate?.format(isoDate)
                val endIso = s.toDate?.format(isoDate)
                searchTransactions(
                    q = q, start = startIso, end = endIso, direction = s.filterDirection,
                ).onSuccess { list ->
                    _uiState.value = _uiState.value.copy(
                        transactions = list.sortedByDescending { t -> t.occurredAt },
                    )
                }
                // Range spent total: only when both ends are set; end EXCLUSIVE.
                if (s.fromDate != null && s.toDate != null) {
                    val endExclusive = s.toDate.plusDays(1).format(isoDate)
                    getSpentRange(s.fromDate.format(isoDate), endExclusive)
                        .onSuccess { _uiState.value = _uiState.value.copy(rangeSpent = it) }
                        .onFailure { _uiState.value = _uiState.value.copy(rangeSpent = null) }
                } else {
                    _uiState.value = _uiState.value.copy(rangeSpent = null)
                }
            }
            regroup()
            _uiState.value = _uiState.value.copy(loading = false)
        }
    }

    private fun regroup() {
        val s = _uiState.value
        _uiState.value = s.copy(items = buildGroupedItems(s.transactions, s.categoryNames))
    }
}
