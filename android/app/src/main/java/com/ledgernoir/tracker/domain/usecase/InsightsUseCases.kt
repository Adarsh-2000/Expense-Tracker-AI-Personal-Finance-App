package com.ledgernoir.tracker.domain.usecase

import com.ledgernoir.tracker.data.api.BreakdownResponse
import com.ledgernoir.tracker.data.api.InsightSummaryResponse
import com.ledgernoir.tracker.data.api.InsightsResponse
import com.ledgernoir.tracker.data.api.RailsResponse
import com.ledgernoir.tracker.data.api.SavingsRateResponse
import com.ledgernoir.tracker.data.api.SmallLeaksResponse
import com.ledgernoir.tracker.data.api.SpendingPatternsResponse
import com.ledgernoir.tracker.data.api.SubscriptionsResponse
import com.ledgernoir.tracker.data.repository.ExpenseRepository

/** Category→sub-category spend breakdown for a range. */
class GetBreakdownUseCase(private val repo: ExpenseRepository) {
    suspend operator fun invoke(
        start: String,
        end: String,
        categoryId: Int? = null,
    ): Result<BreakdownResponse> = repo.breakdown(start, end, categoryId)
}

/** Payment-rail breakdown for a range. */
class GetRailsUseCase(private val repo: ExpenseRepository) {
    suspend operator fun invoke(start: String, end: String): Result<RailsResponse> =
        repo.rails(start, end)
}

/** Spending-pattern analysis for a range. */
class GetSpendingPatternsUseCase(private val repo: ExpenseRepository) {
    suspend operator fun invoke(start: String, end: String): Result<SpendingPatternsResponse> =
        repo.spendingPatterns(start, end)
}

/** Savings-rate trend over the trailing [months]. */
class GetSavingsRateUseCase(private val repo: ExpenseRepository) {
    suspend operator fun invoke(months: Int = 6): Result<SavingsRateResponse> =
        repo.savingsRate(months)
}

/** Spending insights / nudges. */
class GetInsightsUseCase(private val repo: ExpenseRepository) {
    suspend operator fun invoke(): Result<InsightsResponse> = repo.insights()
}

/** Per-month LLM insight summary (Spending Patterns / How To Save More / Money Radar). */
class GetInsightSummaryUseCase(private val repo: ExpenseRepository) {
    suspend operator fun invoke(month: String?): Result<InsightSummaryResponse> =
        repo.insightSummary(month)
}

/** Recurring subscriptions detected over a range. */
class GetSubscriptionsUseCase(private val repo: ExpenseRepository) {
    suspend operator fun invoke(start: String, end: String): Result<SubscriptionsResponse> =
        repo.subscriptions(start, end)
}

/** Small recurring "leaks" detected over a range. */
class GetSmallLeaksUseCase(private val repo: ExpenseRepository) {
    suspend operator fun invoke(start: String, end: String): Result<SmallLeaksResponse> =
        repo.smallLeaks(start, end)
}
