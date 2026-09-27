package com.ledgernoir.tracker.ui.budget

import com.ledgernoir.tracker.data.api.BudgetResponse
import com.ledgernoir.tracker.data.api.CategoryDto

/**
 * Immutable snapshot of the Budget screen: the budget for the current month, the
 * category catalog (full list + id→name lookup), and loading/error state. Dialog
 * visibility/selection stays as local UI state in the composable.
 */
data class BudgetUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val budget: BudgetResponse? = null,
    val allCategories: List<CategoryDto> = emptyList(),
    val categoryNames: Map<Int, String> = emptyMap(),
)
