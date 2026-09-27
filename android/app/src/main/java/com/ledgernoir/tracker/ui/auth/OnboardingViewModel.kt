package com.ledgernoir.tracker.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ledgernoir.tracker.domain.usecase.PutProfileUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Owns the Onboarding form state + validation (client-side PAN check) and the
 * profile PUT. Behaviour mirrors the original screen exactly: PAN is uppercased
 * and capped to 10 chars, DOB stored as "DD-MM-YYYY", Continue enabled only on a
 * valid PAN, and a save failure surfaces a generic message.
 */
class OnboardingViewModel(
    private val putProfile: PutProfileUseCase,
) : ViewModel() {

    private val _uiState = MutableStateFlow(OnboardingUiState())
    val uiState: StateFlow<OnboardingUiState> = _uiState.asStateFlow()

    fun onPanChange(raw: String) {
        val pan = raw.uppercase().take(10)
        _uiState.value = _uiState.value.copy(pan = pan, panTouched = true).withValidation()
    }

    fun onDobSelected(dob: String) {
        _uiState.value = _uiState.value.copy(dob = dob)
    }

    fun showDatePicker() {
        _uiState.value = _uiState.value.copy(showDatePicker = true)
    }

    fun dismissDatePicker() {
        _uiState.value = _uiState.value.copy(showDatePicker = false)
    }

    /**
     * Save the profile (PAN + DOB). On success invokes [onDone]; on failure shows
     * a generic error and clears the saving flag.
     */
    fun save(onDone: () -> Unit) {
        if (!_uiState.value.panValid || _uiState.value.saving) return
        _uiState.value = _uiState.value.copy(saving = true, errorMsg = null)
        viewModelScope.launch {
            putProfile(_uiState.value.pan, _uiState.value.dob)
                .onSuccess {
                    _uiState.value = _uiState.value.copy(saving = false)
                    onDone()
                }
                .onFailure {
                    _uiState.value = _uiState.value.copy(
                        saving = false,
                        errorMsg = "Couldn't save your profile. Check your PAN and try again.",
                    )
                }
        }
    }

    private fun OnboardingUiState.withValidation(): OnboardingUiState {
        val valid = PAN_REGEX.matches(pan)
        return copy(
            panValid = valid,
            panError = panTouched && pan.isNotEmpty() && !valid,
        )
    }

    companion object {
        private val PAN_REGEX = Regex("^[A-Z]{5}[0-9]{4}[A-Z]$")
    }
}
