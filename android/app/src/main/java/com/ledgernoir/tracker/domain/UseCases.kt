package com.ledgernoir.tracker.domain

import com.ledgernoir.tracker.data.repository.ExpenseRepository
import com.ledgernoir.tracker.domain.usecase.AddFixedDepositUseCase
import com.ledgernoir.tracker.domain.usecase.AddGoalUseCase
import com.ledgernoir.tracker.domain.usecase.AssignBudgetUseCase
import com.ledgernoir.tracker.domain.usecase.DeleteFixedDepositUseCase
import com.ledgernoir.tracker.domain.usecase.DeleteGoalUseCase
import com.ledgernoir.tracker.domain.usecase.GetBillsUseCase
import com.ledgernoir.tracker.domain.usecase.GetBreakdownUseCase
import com.ledgernoir.tracker.domain.usecase.GetBudgetUseCase
import com.ledgernoir.tracker.domain.usecase.GetCardsUseCase
import com.ledgernoir.tracker.domain.usecase.GetCategoriesUseCase
import com.ledgernoir.tracker.domain.usecase.GetGoalsUseCase
import com.ledgernoir.tracker.domain.usecase.GetInsightSummaryUseCase
import com.ledgernoir.tracker.domain.usecase.GetInsightsUseCase
import com.ledgernoir.tracker.domain.usecase.GetMonthSummaryUseCase
import com.ledgernoir.tracker.domain.usecase.GetMonthsUseCase
import com.ledgernoir.tracker.domain.usecase.GetNetWorthFullUseCase
import com.ledgernoir.tracker.domain.usecase.GetNetWorthUseCase
import com.ledgernoir.tracker.domain.usecase.GetNotificationsUseCase
import com.ledgernoir.tracker.domain.usecase.GetPortfolioUseCase
import com.ledgernoir.tracker.domain.usecase.GetProfileUseCase
import com.ledgernoir.tracker.domain.usecase.GetProjectionUseCase
import com.ledgernoir.tracker.domain.usecase.GetRailsUseCase
import com.ledgernoir.tracker.domain.usecase.GetSavingsRateUseCase
import com.ledgernoir.tracker.domain.usecase.GetSavingsUseCase
import com.ledgernoir.tracker.domain.usecase.GetSmallLeaksUseCase
import com.ledgernoir.tracker.domain.usecase.GetSpendingPatternsUseCase
import com.ledgernoir.tracker.domain.usecase.GetSpentRangeUseCase
import com.ledgernoir.tracker.domain.usecase.GetSpentUseCase
import com.ledgernoir.tracker.domain.usecase.GetStatsUseCase
import com.ledgernoir.tracker.domain.usecase.GetSubscriptionsUseCase
import com.ledgernoir.tracker.domain.usecase.GetTransactionsUseCase
import com.ledgernoir.tracker.domain.usecase.MarkBillPaidUseCase
import com.ledgernoir.tracker.domain.usecase.PutProfileUseCase
import com.ledgernoir.tracker.domain.usecase.SearchTransactionsUseCase
import com.ledgernoir.tracker.domain.usecase.SetBalanceUseCase
import com.ledgernoir.tracker.domain.usecase.SetIncomeUseCase
import com.ledgernoir.tracker.domain.usecase.SyncGmailUseCase
import com.ledgernoir.tracker.domain.usecase.SyncPortfolioUseCase
import com.ledgernoir.tracker.domain.usecase.DismissFindingUseCase
import com.ledgernoir.tracker.domain.usecase.GetMoneyRadarUseCase
import com.ledgernoir.tracker.domain.usecase.GetRadarSummaryUseCase
import com.ledgernoir.tracker.domain.usecase.MarkRadarSeenUseCase
import com.ledgernoir.tracker.domain.usecase.GetCardUtilizationUseCase
import com.ledgernoir.tracker.domain.usecase.GetRewardsUseCase
import com.ledgernoir.tracker.domain.usecase.SetCardLimitUseCase

/**
 * Lightweight, no-DI provider of every use-case, each constructed once over the
 * shared [ExpenseRepository]. Exposed off [com.ledgernoir.tracker.LedgerNoirApp]
 * so the ViewModel factory can hand individual use-cases to each ViewModel.
 */
class UseCases(repo: ExpenseRepository) {
    // Transactions
    val getTransactions = GetTransactionsUseCase(repo)
    val searchTransactions = SearchTransactionsUseCase(repo)
    val getCategories = GetCategoriesUseCase(repo)
    val getSpentRange = GetSpentRangeUseCase(repo)

    // Dashboard
    val getMonths = GetMonthsUseCase(repo)
    val getMonthSummary = GetMonthSummaryUseCase(repo)
    val getSpent = GetSpentUseCase(repo)
    val getNetWorth = GetNetWorthUseCase(repo)
    val getStats = GetStatsUseCase(repo)

    // Insights / How I Pay / Save More
    val getBreakdown = GetBreakdownUseCase(repo)
    val getRails = GetRailsUseCase(repo)
    val getSpendingPatterns = GetSpendingPatternsUseCase(repo)
    val getSavingsRate = GetSavingsRateUseCase(repo)
    val getInsights = GetInsightsUseCase(repo)
    val getInsightSummary = GetInsightSummaryUseCase(repo)
    val getSubscriptions = GetSubscriptionsUseCase(repo)
    val getSmallLeaks = GetSmallLeaksUseCase(repo)

    // Goals
    val getGoals = GetGoalsUseCase(repo)
    val addGoal = AddGoalUseCase(repo)
    val deleteGoal = DeleteGoalUseCase(repo)

    // Portfolio
    val getPortfolio = GetPortfolioUseCase(repo)
    val syncPortfolio = SyncPortfolioUseCase(repo)

    // Savings
    val getSavings = GetSavingsUseCase(repo)
    val setBalance = SetBalanceUseCase(repo)
    val addFixedDeposit = AddFixedDepositUseCase(repo)
    val deleteFixedDeposit = DeleteFixedDepositUseCase(repo)

    // Bills
    val getBills = GetBillsUseCase(repo)
    val getCards = GetCardsUseCase(repo)
    val markBillPaid = MarkBillPaidUseCase(repo)

    // Budget
    val getBudget = GetBudgetUseCase(repo)
    val setIncome = SetIncomeUseCase(repo)
    val assignBudget = AssignBudgetUseCase(repo)

    // Net worth
    val getNetWorthFull = GetNetWorthFullUseCase(repo)
    val getProjection = GetProjectionUseCase(repo)

    // Profile
    val getProfile = GetProfileUseCase(repo)
    val putProfile = PutProfileUseCase(repo)

    // Sync (Settings explicit action)
    val syncGmail = SyncGmailUseCase(repo)

    // Notifications
    val getNotifications = GetNotificationsUseCase(repo)

    // Money Radar
    val getMoneyRadar = GetMoneyRadarUseCase(repo)
    val getRadarSummary = GetRadarSummaryUseCase(repo)
    val markRadarSeen = MarkRadarSeenUseCase(repo)
    val dismissFinding = DismissFindingUseCase(repo)

    // Rewards
    val getRewards = GetRewardsUseCase(repo)

    // Card utilization
    val getCardUtilization = GetCardUtilizationUseCase(repo)
    val setCardLimit = SetCardLimitUseCase(repo)
}
