package com.ledgernoir.tracker.domain.usecase

import com.ledgernoir.tracker.data.api.PortfolioResponse
import com.ledgernoir.tracker.data.api.PortfolioSyncResult
import com.ledgernoir.tracker.data.repository.ExpenseRepository

/** Returns the current portfolio holdings. */
class GetPortfolioUseCase(private val repo: ExpenseRepository) {
    suspend operator fun invoke(): Result<PortfolioResponse> = repo.portfolio()
}

/** Triggers a portfolio re-sync from the broker. */
class SyncPortfolioUseCase(private val repo: ExpenseRepository) {
    suspend operator fun invoke(): Result<PortfolioSyncResult> = repo.syncPortfolio()
}
