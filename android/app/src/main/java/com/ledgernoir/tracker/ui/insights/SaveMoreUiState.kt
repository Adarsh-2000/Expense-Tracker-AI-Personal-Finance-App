package com.ledgernoir.tracker.ui.insights

import com.ledgernoir.tracker.data.api.GoalsResponse
import com.ledgernoir.tracker.data.api.InsightsResponse
import com.ledgernoir.tracker.data.api.SavingsRateResponse
import com.ledgernoir.tracker.data.api.SmallLeaksResponse
import com.ledgernoir.tracker.data.api.SubscriptionsResponse
import com.ledgernoir.tracker.data.api.TransactionDto

/**
 * Immutable snapshot of the Save More screen: the savings-rate hero, the insights
 * feed, subscriptions, small leaks, goals, and the loading flag. Add-goal dialog
 * visibility stays as local UI state in the composable.
 *
 * [expandedInsight] is the index of the currently-expanded insight card (or null);
 * [insightTxns] caches the drill-down transactions per insight index, and
 * [insightLoading] is the index currently fetching its transactions.
 */
data class SaveMoreUiState(
    val loading: Boolean = true,
    val insights: InsightsResponse? = null,
    val savingsRate: SavingsRateResponse? = null,
    val subscriptions: SubscriptionsResponse? = null,
    val leaks: SmallLeaksResponse? = null,
    val goals: GoalsResponse? = null,
    val expandedInsight: Int? = null,
    val insightTxns: Map<Int, List<TransactionDto>> = emptyMap(),
    val insightLoading: Int? = null,
)
