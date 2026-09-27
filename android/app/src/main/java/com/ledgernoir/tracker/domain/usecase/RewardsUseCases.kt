package com.ledgernoir.tracker.domain.usecase

import com.ledgernoir.tracker.data.api.RewardsResponse
import com.ledgernoir.tracker.data.repository.ExpenseRepository

/** Returns the per-card rewards summary. */
class GetRewardsUseCase(private val repo: ExpenseRepository) {
    suspend operator fun invoke(): Result<RewardsResponse> = repo.rewards()
}
