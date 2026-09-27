package com.ledgernoir.tracker.ui.transactions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ledgernoir.tracker.domain.usecase.GetCategoriesUseCase
import com.ledgernoir.tracker.domain.usecase.SearchTransactionsUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Backing ViewModel for the reusable [TransactionListScreen] drill-down. The
 * query args (range + optional category/account scope) are not known at
 * construction, so they're passed in via [load], called from a keyed
 * LaunchedEffect in the composable — re-running the fetch whenever they change,
 * exactly like the original LaunchedEffect(startIso, endIso, categoryId, accountId).
 */
class TransactionListViewModel(
    private val getCategories: GetCategoriesUseCase,
    private val searchTransactions: SearchTransactionsUseCase,
) : ViewModel() {

    private val _uiState = MutableStateFlow(TransactionListUiState())
    val uiState: StateFlow<TransactionListUiState> = _uiState.asStateFlow()

    /**
     * Fetch the category lookup + the transaction list.
     * - Category/account drill-downs (query == null): debit-only and date-bounded,
     *   so the list matches the SPENT total.
     * - Merchant drill-down (query != null, from a Money Radar finding): all of that
     *   merchant's activity across all time and both directions (incl. refunds).
     */
    fun load(startIso: String, endIso: String, categoryId: Int?, accountId: Int?, query: String? = null) {
        val isMerchant = query != null
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(loading = true)
            getCategories().onSuccess { list ->
                _uiState.value = _uiState.value.copy(
                    categoryNames = list.associate { it.id to it.name },
                )
            }
            searchTransactions(
                // Merchant drill-downs use token-OR matching (merchant_any) so a finding
                // named "Disney Hotstar" / "NETFLIX COM" still surfaces debits tagged
                // "Hotstar" / "netflixupi...". Category/account drill-downs use plain q=null.
                merchantAny = query,
                start = if (isMerchant) null else startIso,
                end = if (isMerchant) null else endIso,
                categoryId = categoryId,
                accountId = accountId,
                direction = if (isMerchant) null else "debit",
            ).onSuccess { list ->
                _uiState.value = _uiState.value.copy(
                    transactions = list,
                    totalSpent = list.filter { it.direction != "credit" }.sumOf { it.amount },
                )
            }
            _uiState.value = _uiState.value.copy(loading = false)
        }
    }
}
