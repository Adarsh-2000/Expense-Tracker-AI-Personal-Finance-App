package com.ledgernoir.tracker.domain.usecase

import com.ledgernoir.tracker.data.api.MoneyRadarResponse
import com.ledgernoir.tracker.data.api.RadarActionResponse
import com.ledgernoir.tracker.data.api.RadarSummary
import com.ledgernoir.tracker.data.repository.ExpenseRepository

/** Full Money Radar feed (all active findings + new-count). */
class GetMoneyRadarUseCase(private val repo: ExpenseRepository) {
    suspend operator fun invoke(): Result<MoneyRadarResponse> = repo.moneyRadar()
}

/** Lightweight summary for the Dashboard strip (new-count + top 3). */
class GetRadarSummaryUseCase(private val repo: ExpenseRepository) {
    suspend operator fun invoke(): Result<RadarSummary> = repo.radarSummary()
}

/** Marks all NEW findings as seen (called when the screen opens). */
class MarkRadarSeenUseCase(private val repo: ExpenseRepository) {
    suspend operator fun invoke(): Result<RadarActionResponse> = repo.markRadarSeen()
}

/** Dismisses a single finding. */
class DismissFindingUseCase(private val repo: ExpenseRepository) {
    suspend operator fun invoke(id: Int): Result<RadarActionResponse> = repo.dismissFinding(id)
}
