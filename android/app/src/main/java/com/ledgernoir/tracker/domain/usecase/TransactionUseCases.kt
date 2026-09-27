package com.ledgernoir.tracker.domain.usecase

import com.ledgernoir.tracker.data.api.CategoryDto
import com.ledgernoir.tracker.data.api.TransactionDto
import com.ledgernoir.tracker.data.repository.ExpenseRepository

/** Returns the full transaction list. */
class GetTransactionsUseCase(private val repo: ExpenseRepository) {
    suspend operator fun invoke(): Result<List<TransactionDto>> = repo.listTransactions()
}

/** Filtered transaction search (query, scope, date range, direction). */
class SearchTransactionsUseCase(private val repo: ExpenseRepository) {
    suspend operator fun invoke(
        q: String? = null,
        merchantAny: String? = null,
        accountId: Int? = null,
        categoryId: Int? = null,
        start: String? = null,
        end: String? = null,
        minAmount: Double? = null,
        maxAmount: Double? = null,
        direction: String? = null,
    ): Result<List<TransactionDto>> =
        repo.searchTransactions(q, merchantAny, accountId, categoryId, start, end, minAmount, maxAmount, direction)
}

/** Returns the category catalog. */
class GetCategoriesUseCase(private val repo: ExpenseRepository) {
    suspend operator fun invoke(): Result<List<CategoryDto>> = repo.categories()
}

/** Total debit spend over an [start, end) range (end exclusive). */
class GetSpentRangeUseCase(private val repo: ExpenseRepository) {
    suspend operator fun invoke(start: String, end: String): Result<Double> =
        repo.spentRange(start, end)
}
