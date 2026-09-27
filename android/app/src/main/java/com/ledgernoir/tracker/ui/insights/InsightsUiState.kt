package com.ledgernoir.tracker.ui.insights

import com.ledgernoir.tracker.data.api.BreakdownResponse

/** Selectable preset windows for the Insights date-range filter. */
enum class RangePreset(val label: String) {
    THIS_MONTH("This Month"),
    LAST_3M("Last 3M"),
    LAST_6M("Last 6M"),
    CUSTOM("Custom"),
}

/**
 * Immutable snapshot of everything the Insights screen renders. All fetched data,
 * the resolved date range, and the user's filter/expand selections live here.
 */
data class InsightsUiState(
    val loading: Boolean = true,

    // Active preset + resolved [start, endExclusive) ISO range.
    val preset: RangePreset = RangePreset.LAST_3M,
    val startIso: String = "",
    val endIso: String = "",   // EXCLUSIVE

    // Category→sub-category breakdown for the current range (null = failed/empty).
    val breakdown: BreakdownResponse? = null,
    // Category name (lowercased/trimmed) → id, for drill-down.
    val categoryIdByName: Map<String, Int> = emptyMap(),

    // Null = All; non-null = single selected category name (as-returned by API, not lowercased).
    val selectedCategory: String? = null,
    // Which categories are expanded to reveal sub-rows.
    val expanded: Set<String> = emptySet(),

    // Whether the custom date-range picker dialog is showing.
    val showCustomPicker: Boolean = false,

    // New (unseen) Money Radar findings — drives the badge on the Money Radar hub tile.
    val radarNewCount: Int = 0,
)
