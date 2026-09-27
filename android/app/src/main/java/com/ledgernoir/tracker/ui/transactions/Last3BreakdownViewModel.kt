package com.ledgernoir.tracker.ui.transactions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ledgernoir.tracker.data.api.SummaryItem
import com.ledgernoir.tracker.domain.usecase.GetCategoriesUseCase
import com.ledgernoir.tracker.domain.usecase.SearchTransactionsUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val UNCATEGORIZED = "Uncategorized"

/**
 * Backs [Last3BreakdownScreen]. One debit-only date-bounded fetch drives the donut,
 * the category chips, and the (filterable) transaction list — single source of
 * truth, so the donut total always equals the sum of the visible rows.
 */
class Last3BreakdownViewModel(
    private val getCategories: GetCategoriesUseCase,
    private val searchTransactions: SearchTransactionsUseCase,
) : ViewModel() {

    private val _uiState = MutableStateFlow(Last3BreakdownUiState())
    val uiState: StateFlow<Last3BreakdownUiState> = _uiState.asStateFlow()

    fun load(startIso: String, endIso: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(loading = true)
            val names = getCategories().getOrNull()?.associate { it.id to it.name } ?: emptyMap()
            searchTransactions(
                start = startIso, end = endIso, direction = "debit",
            ).onSuccess { list ->
                val debits = list.filter { it.direction != "credit" }
                val totals = debits
                    .groupBy { t -> t.categoryId?.let { names[it] } ?: UNCATEGORIZED }
                    .map { (cat, txns) -> SummaryItem(category = cat, total = txns.sumOf { it.amount }) }
                    .sortedByDescending { it.total }
                _uiState.value = _uiState.value.copy(
                    transactions = list,
                    categoryNames = names,
                    categoryTotals = totals,
                    totalSpent = debits.sumOf { it.amount },
                )
            }
            _uiState.value = _uiState.value.copy(loading = false)
        }
    }

    fun selectCategory(category: String?) {
        _uiState.value = _uiState.value.copy(selectedCategory = category)
    }
}
