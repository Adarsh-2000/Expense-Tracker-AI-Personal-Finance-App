package com.ledgernoir.tracker.ui.bills

import com.ledgernoir.tracker.data.api.BillDto
import com.ledgernoir.tracker.data.api.CardsResponse

/**
 * Immutable snapshot of the Bills screen: the already-filtered pending bills
 * (unpaid + due this calendar month or later, sorted by due date) plus the live
 * cards summary, and the loading flag.
 */
data class BillsUiState(
    val loading: Boolean = true,
    val bills: List<BillDto> = emptyList(),
    val cards: CardsResponse? = null,
    val utilizationByAccount: Map<Int, com.ledgernoir.tracker.data.api.CardUtilizationDto> = emptyMap(),
)
