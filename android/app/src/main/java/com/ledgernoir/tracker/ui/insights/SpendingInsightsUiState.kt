package com.ledgernoir.tracker.ui.insights

import com.ledgernoir.tracker.data.api.RailsResponse
import com.ledgernoir.tracker.data.api.SpendingPatternsResponse

/** Selectable preset windows for the "How I Pay" date-range filter. */
enum class PayRangePreset(val label: String) {
    THIS_MONTH("This Month"),
    LAST_3M("Last 3M"),
    LAST_6M("Last 6M"),
}

/**
 * Immutable snapshot of the "How I Pay" (Spending Insights) screen: the active
 * preset + resolved [start, endExclusive) range, the fetched rails + patterns, the
 * loading flag, and the set of expanded rail rows.
 */
data class SpendingInsightsUiState(
    val loading: Boolean = true,
    val preset: PayRangePreset = PayRangePreset.LAST_3M,
    val startIso: String = "",
    val endIso: String = "",   // EXCLUSIVE
    val rails: RailsResponse? = null,
    val patterns: SpendingPatternsResponse? = null,
    val expandedRails: Set<String> = emptySet(),
)
