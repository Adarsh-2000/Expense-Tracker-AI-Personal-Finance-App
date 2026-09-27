package com.ledgernoir.tracker.domain.usecase

import com.ledgernoir.tracker.data.api.BillDto
import com.ledgernoir.tracker.data.api.CardsResponse
import com.ledgernoir.tracker.data.repository.ExpenseRepository

/** Returns the bills list. */
class GetBillsUseCase(private val repo: ExpenseRepository) {
    suspend operator fun invoke(): Result<List<BillDto>> = repo.bills()
}

/** Returns the credit-card statements. */
class GetCardsUseCase(private val repo: ExpenseRepository) {
    suspend operator fun invoke(): Result<CardsResponse> = repo.cards()
}

/** Marks a bill as paid. */
class MarkBillPaidUseCase(private val repo: ExpenseRepository) {
    suspend operator fun invoke(id: Int): Result<Unit> = repo.markBillPaid(id)
}
