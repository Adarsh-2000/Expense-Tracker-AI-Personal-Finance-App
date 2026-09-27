package com.ledgernoir.tracker.ui.portfolio

import com.ledgernoir.tracker.data.api.PortfolioResponse

/** Holdings list filter for the Portfolio screen. */
enum class HoldingFilter { ALL, STOCKS, FUNDS }

/**
 * Immutable snapshot of the Portfolio screen: the fetched portfolio, loading +
 * syncing flags, the transient status line, and the active holdings filter.
 */
data class PortfolioUiState(
    val loading: Boolean = true,
    val syncing: Boolean = false,
    val portfolio: PortfolioResponse? = null,
    val statusLine: String? = null,
    val filter: HoldingFilter = HoldingFilter.ALL,
)
