package com.ledgernoir.tracker.ui.bills

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ledgernoir.tracker.data.api.BillDto
import com.ledgernoir.tracker.domain.usecase.GetBillsUseCase
import com.ledgernoir.tracker.domain.usecase.GetCardsUseCase
import com.ledgernoir.tracker.domain.usecase.MarkBillPaidUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * Owns the Bills data + the "mark paid" action. The filter logic (unpaid bills
 * due this calendar month or later, sorted by due date) lives here, matching the
 * original screen. Marking a bill paid re-fetches both bills and cards.
 */
class BillsViewModel(
    private val getBills: GetBillsUseCase,
    private val getCards: GetCardsUseCase,
    private val markBillPaidUseCase: MarkBillPaidUseCase,
    private val getCardUtilization: com.ledgernoir.tracker.domain.usecase.GetCardUtilizationUseCase,
    private val setCardLimitUseCase: com.ledgernoir.tracker.domain.usecase.SetCardLimitUseCase,
) : ViewModel() {

    // First day of the current calendar month — the lower bound for "shown" bills.
    private val monthStart: LocalDate = LocalDate.now().withDayOfMonth(1)

    private val _uiState = MutableStateFlow(BillsUiState())
    val uiState: StateFlow<BillsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(loading = true)
            getBills()
                .onSuccess { _uiState.value = _uiState.value.copy(bills = applyFilter(it)) }
                .onFailure { /* non-fatal */ }
            getCards()
                .onSuccess { _uiState.value = _uiState.value.copy(cards = it) }
                .onFailure { /* non-fatal */ }
            getCardUtilization()
                .onSuccess { resp ->
                    _uiState.value = _uiState.value.copy(
                        utilizationByAccount = resp.cards.associateBy { it.accountId })
                }
                .onFailure { /* non-fatal */ }
            _uiState.value = _uiState.value.copy(loading = false)
        }
    }

    fun markBillPaid(id: Int) {
        viewModelScope.launch {
            markBillPaidUseCase(id).onSuccess {
                getBills().onSuccess { list ->
                    _uiState.value = _uiState.value.copy(bills = applyFilter(list))
                }
                getCards().onSuccess { _uiState.value = _uiState.value.copy(cards = it) }
            }
        }
    }

    /**
     * Posts a manual credit limit and/or card network, then re-fetches utilization
     * and cards so the UI reflects the new limit and the network mark on the face.
     * Either field may be null (e.g. set just the network without touching the limit).
     */
    fun setCardDetails(accountId: Int, creditLimit: Double? = null, network: String? = null) {
        viewModelScope.launch {
            setCardLimitUseCase(accountId, creditLimit, network).onSuccess {
                getCardUtilization().onSuccess { resp ->
                    _uiState.value = _uiState.value.copy(
                        utilizationByAccount = resp.cards.associateBy { it.accountId })
                }
                getCards().onSuccess { _uiState.value = _uiState.value.copy(cards = it) }
            }
        }
    }

    /** Unpaid bills with a due date this calendar month or later, ascending by due date. */
    private fun applyFilter(list: List<BillDto>): List<BillDto> =
        list.filter { bill ->
            if (bill.paid) return@filter false
            val due = try {
                LocalDate.parse(bill.dueDate)
            } catch (e: Exception) {
                null
            }
            due != null && !due.isBefore(monthStart)
        }.sortedBy { it.dueDate }
}
