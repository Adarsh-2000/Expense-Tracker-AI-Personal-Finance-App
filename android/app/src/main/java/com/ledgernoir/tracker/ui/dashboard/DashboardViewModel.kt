package com.ledgernoir.tracker.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ledgernoir.tracker.LedgerNoirApp
import com.ledgernoir.tracker.SyncPhase
import com.ledgernoir.tracker.domain.usecase.GetCategoriesUseCase
import com.ledgernoir.tracker.domain.usecase.GetMonthSummaryUseCase
import com.ledgernoir.tracker.domain.usecase.GetMonthsUseCase
import com.ledgernoir.tracker.domain.usecase.GetNetWorthUseCase
import com.ledgernoir.tracker.domain.usecase.GetNotificationsUseCase
import com.ledgernoir.tracker.domain.usecase.GetRadarSummaryUseCase
import com.ledgernoir.tracker.domain.usecase.GetSpentRangeUseCase
import com.ledgernoir.tracker.domain.usecase.GetSpentUseCase
import com.ledgernoir.tracker.domain.usecase.GetStatsUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth

/**
 * Owns all Dashboard data-fetching + business logic (month selection, range
 * computation, MoM, sync-triggered refresh). Constructed with its use-cases and
 * the [LedgerNoirApp] (the screen needs syncPhase + the manual sync functions).
 */
class DashboardViewModel(
    private val getSpentRange: GetSpentRangeUseCase,
    private val getCategories: GetCategoriesUseCase,
    private val getMonths: GetMonthsUseCase,
    private val getMonthSummary: GetMonthSummaryUseCase,
    private val getSpent: GetSpentUseCase,
    private val getNetWorth: GetNetWorthUseCase,
    private val getStats: GetStatsUseCase,
    private val getNotifications: GetNotificationsUseCase,
    private val getRadarSummary: GetRadarSummaryUseCase,
    private val getRewards: com.ledgernoir.tracker.domain.usecase.GetRewardsUseCase,
    private val app: LedgerNoirApp,
) : ViewModel() {

    // "Last 3 months" = rolling 90-day window ending today (today − 90 … today,
    // inclusive). Backend treats `end` as exclusive, so end = today + 1. Must
    // match the Insights "Last 3M" preset exactly so the two screens agree.
    private val today: LocalDate = LocalDate.now()
    private val last3StartIso: String = today.minusDays(90).toString()
    private val last3EndIso: String = today.plusDays(1).toString()

    private val _uiState = MutableStateFlow(
        DashboardUiState(
            last3StartIso = last3StartIso,
            last3EndIso = last3EndIso,
        ),
    )
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    /** Observable SMS-sync phase, surfaced straight from the application. */
    val syncPhase: StateFlow<SyncPhase> = app.syncPhase

    init {
        // Bell badge: fetch the notification feed once, then track the locally
        // persisted read-set reactively so the count drops the instant the user
        // reads something in the notification center.
        viewModelScope.launch {
            val feedIds = getNotifications().getOrNull()?.notifications?.map { it.id } ?: emptyList()
            app.tokenStore.readNotificationIds.collect { readIds ->
                _uiState.value = _uiState.value.copy(
                    unreadNotifications = feedIds.count { it !in readIds },
                )
            }
        }
    }

    init {
        // Radar strip: one-shot fetch of the summary (new count + top findings).
        viewModelScope.launch {
            getRadarSummary().onSuccess { s ->
                _uiState.value = _uiState.value.copy(radarNewCount = s.newCount, radarTop = s.top)
            }
        }
    }

    init {
        // Rewards-expiry strip: surface the soonest card expiry, if any.
        viewModelScope.launch {
            getRewards().onSuccess { resp ->
                val soon = resp.cards.firstOrNull { it.expiringPoints > 0 && it.expiringDate != null }
                if (soon != null) {
                    _uiState.value = _uiState.value.copy(
                        rewardsExpiringPoints = soon.expiringPoints,
                        rewardsExpiringLabel =
                            "${soon.expiringPoints} ${soon.bank} pts expire ${soon.expiringDate}",
                    )
                }
            }
        }
    }

    init {
        // Initial months load (mirrors the original LaunchedEffect(Unit)).
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(loading = true)
            refreshMonths()
            _uiState.value = _uiState.value.copy(loading = false)
            // Month-scoped data follows the initial selection.
            if (_uiState.value.selectedMonth != null) {
                reloadData()
            }
        }

        // One-time fetch: trailing-3-month spend total + category name→id lookup.
        viewModelScope.launch {
            getSpentRange(last3StartIso, last3EndIso)
                .onSuccess { _uiState.value = _uiState.value.copy(last3Total = it) }
                .onFailure { /* non-fatal */ }
            getCategories().onSuccess { list ->
                _uiState.value = _uiState.value.copy(
                    categoryIdByName = list.associate { it.name.lowercase().trim() to it.id },
                )
            }
        }
    }

    /** Kick off the once-per-process automatic SMS sync when permission is granted. */
    fun startSyncIfPermitted(smsPermissionGranted: Boolean) {
        if (smsPermissionGranted) {
            app.startSmsSyncOnce()
        }
    }

    /** Called when the sync phase reaches Done: refresh months + month data. */
    fun onSyncCompleted() {
        viewModelScope.launch {
            refreshMonths()
            reloadData()
        }
    }

    /** Lightweight net-worth-only refresh, e.g. after returning from Savings/
     *  Portfolio where an FD/balance/investment may have changed. */
    fun refreshNetWorth() {
        viewModelScope.launch {
            getNetWorth()
                .onSuccess { _uiState.value = _uiState.value.copy(netWorth = it) }
                .onFailure { /* non-fatal */ }
        }
    }

    /** Switch the browsed month and re-fetch its data. */
    fun selectMonth(month: String) {
        if (month == _uiState.value.selectedMonth) return
        _uiState.value = _uiState.value.copy(selectedMonth = month)
        viewModelScope.launch { reloadData() }
    }

    fun syncSms() = app.syncSmsNow()

    fun syncEmail() = app.syncEmailNow()

    /**
     * Fetches the months-with-data list and, if no month is selected yet, defaults
     * to the current calendar month when it has data, otherwise the latest month
     * that does, otherwise the current calendar month.
     */
    private suspend fun refreshMonths() {
        getMonths().onSuccess { list ->
            val current = _uiState.value
            val selected = if (current.selectedMonth == null) {
                val currentMonth = LocalDate.now().toString().take(7)
                when {
                    list.contains(currentMonth) -> currentMonth
                    list.isNotEmpty() -> list.first()
                    else -> currentMonth
                }
            } else {
                current.selectedMonth
            }
            _uiState.value = current.copy(months = list, selectedMonth = selected)
        }
    }

    private suspend fun reloadData() {
        val m = _uiState.value.selectedMonth ?: LocalDate.now().toString().take(7)
        getMonthSummary(m)
            .onSuccess { _uiState.value = _uiState.value.copy(summary = it) }
            .onFailure { _uiState.value = _uiState.value.copy(error = it.message) }
        getSpent(m)
            .onSuccess { _uiState.value = _uiState.value.copy(spentThisMonth = it) }
            .onFailure { _uiState.value = _uiState.value.copy(error = it.message) }
        getNetWorth()
            .onSuccess { _uiState.value = _uiState.value.copy(netWorth = it) }
            .onFailure { /* non-fatal */ }
        getStats()
            .onSuccess { _uiState.value = _uiState.value.copy(stats = it) }
            .onFailure { /* non-fatal */ }
        // Month-over-month: fetch the previous calendar month's spend for comparison.
        val prevMonth = try {
            YearMonth.parse(m).minusMonths(1).toString()
        } catch (e: Exception) {
            null
        }
        if (prevMonth != null) {
            getSpent(prevMonth)
                .onSuccess { _uiState.value = _uiState.value.copy(prevMonthSpent = it) }
                .onFailure { _uiState.value = _uiState.value.copy(prevMonthSpent = null) }
        } else {
            _uiState.value = _uiState.value.copy(prevMonthSpent = null)
        }
    }
}
