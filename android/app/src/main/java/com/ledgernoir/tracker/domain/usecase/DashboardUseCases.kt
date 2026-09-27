package com.ledgernoir.tracker.domain.usecase

import com.ledgernoir.tracker.data.api.StatsResponse
import com.ledgernoir.tracker.data.api.SummaryItem
import com.ledgernoir.tracker.data.repository.ExpenseRepository

/** Months that have transaction data. */
class GetMonthsUseCase(private val repo: ExpenseRepository) {
    suspend operator fun invoke(): Result<List<String>> = repo.months()
}

/** Category summary for a given month. */
class GetMonthSummaryUseCase(private val repo: ExpenseRepository) {
    suspend operator fun invoke(month: String): Result<List<SummaryItem>> = repo.summary(month)
}

/** Total debit spend for a given month. */
class GetSpentUseCase(private val repo: ExpenseRepository) {
    suspend operator fun invoke(month: String): Result<Double> = repo.spent(month)
}

/** Current net worth headline figure. */
class GetNetWorthUseCase(private val repo: ExpenseRepository) {
    suspend operator fun invoke(): Result<Double> = repo.netWorth()
}

/** Aggregate stats card data. */
class GetStatsUseCase(private val repo: ExpenseRepository) {
    suspend operator fun invoke(): Result<StatsResponse> = repo.stats()
}
