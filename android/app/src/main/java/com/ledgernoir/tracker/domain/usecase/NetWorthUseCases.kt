package com.ledgernoir.tracker.domain.usecase

import com.ledgernoir.tracker.data.api.NetWorthResponse
import com.ledgernoir.tracker.data.api.ProjectionResponse
import com.ledgernoir.tracker.data.repository.ExpenseRepository

/** Returns the full net-worth breakdown (components + total). */
class GetNetWorthFullUseCase(private val repo: ExpenseRepository) {
    suspend operator fun invoke(): Result<NetWorthResponse> = repo.netWorthFull()
}

/** Returns the multi-year net-worth projection. */
class GetProjectionUseCase(private val repo: ExpenseRepository) {
    suspend operator fun invoke(years: Int = 10): Result<ProjectionResponse> =
        repo.projection(years)
}
