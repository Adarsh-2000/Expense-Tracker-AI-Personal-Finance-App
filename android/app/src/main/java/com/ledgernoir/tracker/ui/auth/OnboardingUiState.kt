package com.ledgernoir.tracker.ui.auth

/**
 * Immutable snapshot of the Onboarding form: the PAN/DOB fields, validation flags,
 * date-picker visibility, and the save-in-progress + error state. Derived
 * validation (panValid/panError) is computed in the ViewModel.
 */
data class OnboardingUiState(
    val pan: String = "",
    val dob: String? = null, // "DD-MM-YYYY"
    val showDatePicker: Boolean = false,
    val saving: Boolean = false,
    val errorMsg: String? = null,
    val panTouched: Boolean = false,
    val panValid: Boolean = false,
    val panError: Boolean = false,
)
