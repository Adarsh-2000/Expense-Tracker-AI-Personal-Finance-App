package com.ledgernoir.tracker.ui.dashboard

import com.ledgernoir.tracker.data.api.FindingDto
import com.ledgernoir.tracker.data.api.StatsResponse
import com.ledgernoir.tracker.data.api.SummaryItem

/**
 * Immutable snapshot of everything the Dashboard renders. The ViewModel is the
 * single source of truth; the composable reads purely from this state and emits
 * events back via VM function calls.
 */
data class DashboardUiState(
    val loading: Boolean = true,
    val error: String? = null,

    // Per-category breakdown for the selected month.
    val summary: List<SummaryItem> = emptyList(),
    // Net worth (null = not yet loaded / non-fatal failure → card hidden).
    val netWorth: Double? = null,
    // Spend for the selected month.
    val spentThisMonth: Double = 0.0,
    // Previous calendar month spend, for the MoM chip (null = no prior data).
    val prevMonthSpent: Double? = null,
    // Source ingestion stats (SMS + email parsed/total).
    val stats: StatsResponse? = null,

    // Unread notification count for the header bell badge.
    val unreadNotifications: Int = 0,

    // Money Radar: new finding count + top findings for the dashboard strip.
    val radarNewCount: Int = 0,
    val radarTop: List<FindingDto> = emptyList(),

    // Trailing rolling-90-day spend total (null = not yet loaded).
    val last3Total: Double? = null,
    // ISO bounds for the trailing-90-day window (start inclusive, end exclusive).
    val last3StartIso: String = "",
    val last3EndIso: String = "",
    // Category name (lowercased/trimmed) → id, for drill-down.
    val categoryIdByName: Map<String, Int> = emptyMap(),

    // Months that have transaction data (DESC, latest first) + current selection.
    val months: List<String> = emptyList(),
    val selectedMonth: String? = null,

    // Rewards expiry strip: soonest expiring-points card, if any.
    val rewardsExpiringPoints: Int = 0,
    val rewardsExpiringLabel: String? = null,
)
