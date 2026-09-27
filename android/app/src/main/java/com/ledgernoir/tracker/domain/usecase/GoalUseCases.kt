package com.ledgernoir.tracker.domain.usecase

import com.ledgernoir.tracker.data.api.GoalsResponse
import com.ledgernoir.tracker.data.repository.ExpenseRepository

/** Returns the savings goals. */
class GetGoalsUseCase(private val repo: ExpenseRepository) {
    suspend operator fun invoke(): Result<GoalsResponse> = repo.goals()
}

/** Adds a savings goal. */
class AddGoalUseCase(private val repo: ExpenseRepository) {
    suspend operator fun invoke(name: String, target: Double, targetDate: String?): Result<Unit> =
        repo.addGoal(name, target, targetDate)
}

/** Deletes a savings goal. */
class DeleteGoalUseCase(private val repo: ExpenseRepository) {
    suspend operator fun invoke(id: Int): Result<Unit> = repo.deleteGoal(id)
}
