package com.ledgernoir.tracker.ui.insights

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ledgernoir.tracker.domain.usecase.AddGoalUseCase
import com.ledgernoir.tracker.domain.usecase.DeleteGoalUseCase
import com.ledgernoir.tracker.domain.usecase.GetGoalsUseCase
import com.ledgernoir.tracker.domain.usecase.GetInsightsUseCase
import com.ledgernoir.tracker.domain.usecase.GetSavingsRateUseCase
import com.ledgernoir.tracker.domain.usecase.GetSmallLeaksUseCase
import com.ledgernoir.tracker.domain.usecase.GetSubscriptionsUseCase
import com.ledgernoir.tracker.domain.usecase.SearchTransactionsUseCase
import com.ledgernoir.tracker.data.api.InsightQuery
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * Owns the Save More data + goal mutations. The initial load fetches savings rate,
 * insights, subscriptions and small leaks (over a rolling 90-day window) plus
 * goals. Adding/deleting a goal reloads only the goals list — matching the
 * original screen exactly.
 */
class SaveMoreViewModel(
    private val getSavingsRate: GetSavingsRateUseCase,
    private val getInsights: GetInsightsUseCase,
    private val getSubscriptions: GetSubscriptionsUseCase,
    private val getSmallLeaks: GetSmallLeaksUseCase,
    private val getGoals: GetGoalsUseCase,
    private val addGoalUseCase: AddGoalUseCase,
    private val deleteGoalUseCase: DeleteGoalUseCase,
    private val searchTransactions: SearchTransactionsUseCase,
) : ViewModel() {

    private val today: LocalDate = LocalDate.now()
    private val start: String = today.minusDays(90).toString()
    private val end: String = today.plusDays(1).toString()

    private val _uiState = MutableStateFlow(SaveMoreUiState())
    val uiState: StateFlow<SaveMoreUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(loading = true)
            getSavingsRate().onSuccess { _uiState.value = _uiState.value.copy(savingsRate = it) }
            getInsights().onSuccess { _uiState.value = _uiState.value.copy(insights = it) }
            getSubscriptions(start, end).onSuccess { _uiState.value = _uiState.value.copy(subscriptions = it) }
            getSmallLeaks(start, end).onSuccess { _uiState.value = _uiState.value.copy(leaks = it) }
            reloadGoals()
            _uiState.value = _uiState.value.copy(loading = false)
        }
    }

    fun addGoal(name: String, target: Double, date: String?) {
        viewModelScope.launch {
            addGoalUseCase(name, target, date)
            reloadGoals()
        }
    }

    fun deleteGoal(id: Int) {
        viewModelScope.launch {
            deleteGoalUseCase(id)
            reloadGoals()
        }
    }

    private suspend fun reloadGoals() {
        getGoals().onSuccess { _uiState.value = _uiState.value.copy(goals = it) }
    }

    /**
     * Toggle an insight card open/closed. On first open of a card that has a
     * drill-down [query], fetch its underlying transactions (debit, date-scoped)
     * and cache them by index. Tapping the open card collapses it.
     */
    fun toggleInsight(index: Int, query: InsightQuery?) {
        val state = _uiState.value
        if (state.expandedInsight == index) {
            _uiState.value = state.copy(expandedInsight = null)
            return
        }
        _uiState.value = state.copy(expandedInsight = index)
        if (query == null || state.insightTxns.containsKey(index)) return
        _uiState.value = _uiState.value.copy(insightLoading = index)
        viewModelScope.launch {
            val txns = searchTransactions(
                q = query.merchant,
                categoryId = query.categoryId,
                start = query.start,
                end = query.end,
                direction = "debit",
            ).getOrDefault(emptyList()).sortedByDescending { it.occurredAt }
            _uiState.value = _uiState.value.copy(
                insightTxns = _uiState.value.insightTxns + (index to txns),
                insightLoading = null,
            )
        }
    }
}
