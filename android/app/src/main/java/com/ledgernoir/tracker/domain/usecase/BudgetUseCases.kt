package com.ledgernoir.tracker.domain.usecase

import com.ledgernoir.tracker.data.api.BudgetResponse
import com.ledgernoir.tracker.data.repository.ExpenseRepository

/** Returns the budget for a month. */
class GetBudgetUseCase(private val repo: ExpenseRepository) {
    suspend operator fun invoke(month: String): Result<BudgetResponse> = repo.budget(month)
}

/** Sets the income for a month. */
class SetIncomeUseCase(private val repo: ExpenseRepository) {
    suspend operator fun invoke(month: String, amount: Double): Result<Unit> =
        repo.setIncome(month, amount)
}

/** Assigns a budget cap to a category for a month. */
class AssignBudgetUseCase(private val repo: ExpenseRepository) {
    suspend operator fun invoke(categoryId: Int, month: String, assignedAmount: Double): Result<Unit> =
        repo.assign(categoryId, month, assignedAmount)
}
