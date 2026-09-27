package com.ledgernoir.tracker.ui.insights

import com.ledgernoir.tracker.data.api.InsightSummaryResponse
import java.time.YearMonth

/** Immutable snapshot of the AI Insights screen. */
data class AiInsightsUiState(
    val month: YearMonth,
    val loading: Boolean = true,
    val summary: InsightSummaryResponse? = null,
    val canGoForward: Boolean = false,
)
