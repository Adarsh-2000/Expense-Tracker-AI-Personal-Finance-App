package com.ledgernoir.tracker.ui.transactions

import com.ledgernoir.tracker.data.api.SummaryItem
import com.ledgernoir.tracker.data.api.TransactionDto

/**
 * State for the Last 3 Months breakdown screen. [categoryTotals] is derived from
 * [transactions] (debit-only, grouped by category) and drives the donut + chips.
 * [selectedCategory] == null means "All".
 */
data class Last3BreakdownUiState(
    val loading: Boolean = true,
    val transactions: List<TransactionDto> = emptyList(),
    val categoryNames: Map<Int, String> = emptyMap(),
    val categoryTotals: List<SummaryItem> = emptyList(),
    val totalSpent: Double = 0.0,
    val selectedCategory: String? = null,
)
