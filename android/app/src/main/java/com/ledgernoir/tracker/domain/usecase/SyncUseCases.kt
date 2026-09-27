package com.ledgernoir.tracker.domain.usecase

import com.ledgernoir.tracker.data.api.SyncResult
import com.ledgernoir.tracker.data.repository.ExpenseRepository

/**
 * Pulls new transactions from Gmail. Used by the Settings screen's explicit
 * "Sync email" action (the app-level auto-sync stays on [com.ledgernoir.tracker.LedgerNoirApp]).
 */
class SyncGmailUseCase(private val repo: ExpenseRepository) {
    suspend operator fun invoke(): Result<SyncResult> = repo.syncGmail()
}
