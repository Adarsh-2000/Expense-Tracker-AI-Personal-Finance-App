package com.ledgernoir.tracker.domain.usecase

import com.ledgernoir.tracker.data.api.NotificationsResponse
import com.ledgernoir.tracker.data.repository.ExpenseRepository

/** Fetches the current notification feed (bills, goals, budgets, spending, digest). */
class GetNotificationsUseCase(private val repo: ExpenseRepository) {
    suspend operator fun invoke(): Result<NotificationsResponse> = repo.notifications()
}
