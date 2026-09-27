package com.ledgernoir.tracker.ui.transactions

import com.ledgernoir.tracker.data.api.TransactionDto
import java.time.LocalDate

/**
 * Immutable snapshot of the Transactions screen's data + filter state. The grouped
 * display list ([items]) is derived in the ViewModel so the composable renders it
 * directly. Pure-presentation modal/picker visibility stays local to the composable.
 */
data class TransactionsUiState(
    val loading: Boolean = true,

    // Raw fetched data.
    val transactions: List<TransactionDto> = emptyList(),
    val categoryNames: Map<Int, String> = emptyMap(),

    // Filters.
    val query: String = "",
    val fromDate: LocalDate? = null,
    val toDate: LocalDate? = null,
    // null = All, "debit" = Spending, "credit" = Income.
    val filterDirection: String? = null,

    // Range spent total (only when both ends are set), else null.
    val rangeSpent: Double? = null,

    // Derived: grouped + flattened display list (day headers + txn rows).
    val items: List<TxnListItem> = emptyList(),
)
