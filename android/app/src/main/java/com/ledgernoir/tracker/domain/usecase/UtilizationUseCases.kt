package com.ledgernoir.tracker.domain.usecase

import com.ledgernoir.tracker.data.api.CardUtilizationResponse
import com.ledgernoir.tracker.data.repository.ExpenseRepository

/** Per-card credit utilization. */
class GetCardUtilizationUseCase(private val repo: ExpenseRepository) {
    suspend operator fun invoke(): Result<CardUtilizationResponse> = repo.cardUtilization()
}

/**
 * Set a manual sanctioned-limit override and/or the card network for a card
 * account. Either field may be omitted; the backend updates whichever is provided.
 */
class SetCardLimitUseCase(private val repo: ExpenseRepository) {
    suspend operator fun invoke(
        accountId: Int,
        creditLimit: Double? = null,
        network: String? = null,
    ): Result<Unit> =
        repo.setCardLimit(accountId, creditLimit, network)
}
