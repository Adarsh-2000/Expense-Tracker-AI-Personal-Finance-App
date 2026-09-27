package com.ledgernoir.tracker.domain.usecase

import com.ledgernoir.tracker.data.api.ProfileResponse
import com.ledgernoir.tracker.data.repository.ExpenseRepository

/** Reads the stored profile (PAN + DOB). */
class GetProfileUseCase(private val repo: ExpenseRepository) {
    suspend operator fun invoke(): Result<ProfileResponse> = repo.getProfile()
}

/** Saves the profile (PAN + DOB). */
class PutProfileUseCase(private val repo: ExpenseRepository) {
    suspend operator fun invoke(pan: String?, dob: String?): Result<Unit> =
        repo.putProfile(pan, dob)
}
