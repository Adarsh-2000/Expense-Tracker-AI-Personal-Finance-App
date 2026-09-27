package com.ledgernoir.tracker.domain.usecase

import com.ledgernoir.tracker.data.api.FixedDepositPayload
import com.ledgernoir.tracker.data.api.SavingsResponse
import com.ledgernoir.tracker.data.repository.ExpenseRepository

/** Returns the savings overview (accounts + fixed deposits). */
class GetSavingsUseCase(private val repo: ExpenseRepository) {
    suspend operator fun invoke(): Result<SavingsResponse> = repo.savings()
}

/** Sets the manual balance for an account. */
class SetBalanceUseCase(private val repo: ExpenseRepository) {
    suspend operator fun invoke(accountId: Int, balance: Double, asOf: String?): Result<Unit> =
        repo.setBalance(accountId, balance, asOf)
}

/** Adds a fixed deposit. */
class AddFixedDepositUseCase(private val repo: ExpenseRepository) {
    suspend operator fun invoke(fd: FixedDepositPayload): Result<Unit> = repo.addFixedDeposit(fd)
}

/** Deletes a fixed deposit. */
class DeleteFixedDepositUseCase(private val repo: ExpenseRepository) {
    suspend operator fun invoke(id: Int): Result<Unit> = repo.deleteFixedDeposit(id)
}
