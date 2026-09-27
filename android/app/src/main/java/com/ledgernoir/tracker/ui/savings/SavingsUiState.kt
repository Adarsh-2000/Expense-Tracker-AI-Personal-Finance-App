package com.ledgernoir.tracker.ui.savings

import com.ledgernoir.tracker.data.api.SavingsResponse

/**
 * Immutable snapshot of the Savings screen: the fetched savings payload (accounts,
 * fixed deposits, totals) and the loading flag. Dialog visibility/selection stays
 * as local UI state in the composable.
 */
data class SavingsUiState(
    val loading: Boolean = true,
    val data: SavingsResponse? = null,
)
