package com.ledgernoir.tracker.ui.transactions

import com.ledgernoir.tracker.data.api.TransactionDto

/**
 * Immutable snapshot of the reusable transaction drill-down: the (debit-only)
 * date-bounded list, the category-name lookup, the derived total spent, and the
 * loading flag. Sort selection is local UI state in the composable.
 */
data class TransactionListUiState(
    val loading: Boolean = true,
    val transactions: List<TransactionDto> = emptyList(),
    val categoryNames: Map<Int, String> = emptyMap(),
    val totalSpent: Double = 0.0,
)
