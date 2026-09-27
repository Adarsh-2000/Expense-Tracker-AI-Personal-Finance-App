package com.ledgernoir.tracker.ui.budget

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ledgernoir.tracker.domain.usecase.AssignBudgetUseCase
import com.ledgernoir.tracker.domain.usecase.GetBudgetUseCase
import com.ledgernoir.tracker.domain.usecase.GetCategoriesUseCase
import com.ledgernoir.tracker.domain.usecase.SetIncomeUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Owns the Budget data + mutations for the current calendar month: fetches the
 * category catalog and the month's budget, sets income, assigns category caps,
 * and reloads after each mutation — mirroring the original screen exactly.
 */
class BudgetViewModel(
    private val getCategories: GetCategoriesUseCase,
    private val getBudget: GetBudgetUseCase,
    private val setIncomeUseCase: SetIncomeUseCase,
    private val assignBudget: AssignBudgetUseCase,
) : ViewModel() {

    val currentMonth: String =
        LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM"))

    private val _uiState = MutableStateFlow(BudgetUiState())
    val uiState: StateFlow<BudgetUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(loading = true)
            load()
            _uiState.value = _uiState.value.copy(loading = false)
        }
    }

    fun setIncome(amount: Double) {
        viewModelScope.launch {
            setIncomeUseCase(currentMonth, amount)
            load()
        }
    }

    fun assign(categoryId: Int, amount: Double) {
        viewModelScope.launch {
            assignBudget(categoryId, currentMonth, amount)
            load()
        }
    }

    private suspend fun load() {
        getCategories().onSuccess { list ->
            _uiState.value = _uiState.value.copy(
                allCategories = list,
                categoryNames = list.associate { it.id to it.name },
            )
        }
        getBudget(currentMonth)
            .onSuccess { _uiState.value = _uiState.value.copy(budget = it, error = null) }
            .onFailure { _uiState.value = _uiState.value.copy(error = it.message) }
    }
}
