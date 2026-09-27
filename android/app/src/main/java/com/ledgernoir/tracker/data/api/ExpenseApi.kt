package com.ledgernoir.tracker.data.api

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

// ── Request bodies ────────────────────────────────────────────────────────────

@JsonClass(generateAdapter = true)
data class SmsPayload(
    val sender: String,
    val body: String,
    @Json(name = "received_at") val receivedAt: String,   // ISO 8601
    @Json(name = "external_id") val externalId: String,
)

@JsonClass(generateAdapter = true)
data class PinPayload(val pin: String)

@JsonClass(generateAdapter = true)
data class ManualTxnPayload(
    val amount: Double,
    val direction: String,
    val merchant: String,
    @Json(name = "occurred_at") val occurredAt: String,   // ISO 8601
    @Json(name = "account_id") val accountId: Int? = null,
    @Json(name = "category_id") val categoryId: Int? = null,
    val note: String? = null,
)

@JsonClass(generateAdapter = true)
data class IncomePayload(
    val month: String,  // "YYYY-MM"
    val amount: Double,
)

@JsonClass(generateAdapter = true)
data class AssignPayload(
    @Json(name = "category_id") val categoryId: Int,
    val month: String,
    @Json(name = "assigned_amount") val assignedAmount: Double,
)

// ── Response bodies ───────────────────────────────────────────────────────────

@JsonClass(generateAdapter = true)
data class TokenResponse(val token: String)

@JsonClass(generateAdapter = true)
data class TransactionDto(
    val id: Int,
    val amount: Double,
    val direction: String,
    val merchant: String,
    @Json(name = "occurred_at") val occurredAt: String,
    @Json(name = "account_id") val accountId: Int,
    @Json(name = "category_id") val categoryId: Int?,
    val note: String? = null,
    @Json(name = "entry_type") val entryType: String? = null,
    val currency: String? = null,
    @Json(name = "original_amount") val originalAmount: Double? = null,
)

@JsonClass(generateAdapter = true)
data class AccountDto(
    val id: Int,
    val bank: String,
    val type: String,
    val last4: String,
    @Json(name = "current_balance") val currentBalance: Double?,
)

@JsonClass(generateAdapter = true)
data class SummaryItem(
    val category: String,
    val total: Double,
)

@JsonClass(generateAdapter = true)
data class NetWorthComponents(
    val savings: Double = 0.0,
    @Json(name = "fixed_deposits") val fixedDeposits: Double = 0.0,
    @Json(name = "indian_equity") val indianEquity: Double = 0.0,
    @Json(name = "us_stocks") val usStocks: Double = 0.0,
)

@JsonClass(generateAdapter = true)
data class NetWorthResponse(
    @Json(name = "net_worth") val netWorth: Double,
    val components: NetWorthComponents? = null,
)

@JsonClass(generateAdapter = true)
data class ProjectionPoint(
    val year: Int,
    val equity: Double,
    val fd: Double,
    val savings: Double,
    val total: Double,
)

@JsonClass(generateAdapter = true)
data class ProjectionAssumptions(
    @Json(name = "equity_rate") val equityRate: Double,
    @Json(name = "fd_rate") val fdRate: Double,
    @Json(name = "savings_rate") val savingsRate: Double,
    val sip: Double,
    @Json(name = "sip_after") val sipAfter: Double,
    @Json(name = "step_after_month") val stepAfterMonth: Int,
    val years: Int,
)

@JsonClass(generateAdapter = true)
data class ProjectionResponse(
    val assumptions: ProjectionAssumptions,
    @Json(name = "current_total") val currentTotal: Double,
    @Json(name = "projected_total") val projectedTotal: Double,
    @Json(name = "step_date") val stepDate: String? = null,
    val series: List<ProjectionPoint>,
)

@JsonClass(generateAdapter = true)
data class SpentResponse(
    val month: String?,
    val spent: Double,
)

@JsonClass(generateAdapter = true)
data class MonthsResponse(val months: List<String>)

@JsonClass(generateAdapter = true)
data class SyncResult(val ingested: Int = 0, val fetched: Int = 0, val detail: String? = null)

// Matches backend CategoryStatus dataclass from /budget/{month}.
@JsonClass(generateAdapter = true)
data class BudgetCategoryStatus(
    @Json(name = "category_id") val categoryId: Int,
    val category: String = "",
    val assigned: Double,
    val spent: Double,
    val remaining: Double,
    val status: String,
    val projected: Double = 0.0,
    @Json(name = "pace_status") val paceStatus: String = "on_track",
)

// Matches backend GET /categories → [{id, name}].
@JsonClass(generateAdapter = true)
data class CategoryDto(
    val id: Int,
    val name: String,
)

@JsonClass(generateAdapter = true)
data class BudgetResponse(
    val month: String,
    @Json(name = "ready_to_assign") val readyToAssign: Double,
    val categories: List<BudgetCategoryStatus>,
)

// ── Analytics / auth responses ────────────────────────────────────────────────

@JsonClass(generateAdapter = true)
data class AuthGoogleResponse(val authorized: Boolean, val email: String?, val token: String?)

@JsonClass(generateAdapter = true)
data class AuthConfigResponse(val google_web_client_id: String)

@JsonClass(generateAdapter = true)
data class NonceResponse(val nonce: String)

@JsonClass(generateAdapter = true)
data class VerifyGoogleRequest(val id_token: String, val nonce: String? = null)

@JsonClass(generateAdapter = true)
data class TrendPoint(val month: String, val spent: Double)

@JsonClass(generateAdapter = true)
data class TrendResponse(val series: List<TrendPoint>)

@JsonClass(generateAdapter = true)
data class MerchantItem(val merchant: String, val total: Double, val count: Int)

@JsonClass(generateAdapter = true)
data class TopMerchantsResponse(val merchants: List<MerchantItem>)

@JsonClass(generateAdapter = true)
data class DailyPoint(val date: String, val spent: Double)

@JsonClass(generateAdapter = true)
data class DailyResponse(val month: String, val days: List<DailyPoint>)

@JsonClass(generateAdapter = true)
data class BillDto(
    val id: Int,
    val bank: String,
    val last4: String?,
    @Json(name = "total_due") val totalDue: Double,
    @Json(name = "min_due") val minDue: Double?,
    @Json(name = "due_date") val dueDate: String,
    @Json(name = "statement_period") val statementPeriod: String?,
    val paid: Boolean = false,
    @Json(name = "days_until_due") val daysUntilDue: Int,
)

@JsonClass(generateAdapter = true)
data class CardBillInfo(
    @Json(name = "total_due") val totalDue: Double,
    @Json(name = "due_date") val dueDate: String?,
    @Json(name = "days_until_due") val daysUntilDue: Int?,
)

@JsonClass(generateAdapter = true)
data class CardStateDto(
    @Json(name = "account_id") val accountId: Int = 0,
    val bank: String,
    val last4: String?,
    val unbilled: Double,
    @Json(name = "unbilled_count") val unbilledCount: Int,
    @Json(name = "last_statement_date") val lastStatementDate: String?,
    @Json(name = "current_bill") val currentBill: CardBillInfo?,
    val network: String? = null,
)

@JsonClass(generateAdapter = true)
data class CardsResponse(
    val cards: List<CardStateDto>,
    @Json(name = "total_unbilled") val totalUnbilled: Double,
    @Json(name = "total_due") val totalDue: Double,
)

// ── Goal 1: rails + spending patterns ─────────────────────────────────────────
@JsonClass(generateAdapter = true)
data class RailItem(val name: String, val total: Double, val count: Int)

@JsonClass(generateAdapter = true)
data class RailGroup(
    val rail: String, val label: String, val total: Double, val count: Int,
    val items: List<RailItem> = emptyList(),
)

@JsonClass(generateAdapter = true)
data class RailsResponse(val rails: List<RailGroup>, val total: Double)

@JsonClass(generateAdapter = true)
data class HeatCell(val dow: Int, val slot: Int, val total: Double, val count: Int)

@JsonClass(generateAdapter = true)
data class FrequencyItem(val merchant: String, val count: Int, val total: Double, val avg: Double)

@JsonClass(generateAdapter = true)
data class SpendingPatternsResponse(
    val heatmap: List<HeatCell>,
    @Json(name = "by_dow") val byDow: List<Double>,
    val frequency: List<FrequencyItem>,
)

// ── Goal 2: save-more ─────────────────────────────────────────────────────────
@JsonClass(generateAdapter = true)
data class SubscriptionItem(
    val merchant: String,
    @Json(name = "monthly_amount") val monthlyAmount: Double,
    val count: Int,
    @Json(name = "last_seen") val lastSeen: String,
    val category: String,
)

@JsonClass(generateAdapter = true)
data class SubscriptionsResponse(
    val subscriptions: List<SubscriptionItem>,
    @Json(name = "monthly_total") val monthlyTotal: Double,
    val count: Int,
)

@JsonClass(generateAdapter = true)
data class SavingsRatePoint(
    val month: String, val income: Double, val spend: Double,
    val saved: Double, val rate: Double?,
)

@JsonClass(generateAdapter = true)
data class SavingsRateResponse(
    val series: List<SavingsRatePoint>,
    @Json(name = "avg_rate") val avgRate: Double?,
)

@JsonClass(generateAdapter = true)
data class LeakItem(
    val merchant: String, val count: Int, val total: Double, val avg: Double,
    @Json(name = "annual_pace") val annualPace: Double, val category: String,
)

@JsonClass(generateAdapter = true)
data class SmallLeaksResponse(
    val leaks: List<LeakItem>,
    @Json(name = "annual_total") val annualTotal: Double,
)

@JsonClass(generateAdapter = true)
data class InsightQuery(
    val merchant: String? = null,
    @Json(name = "category_id") val categoryId: Int? = null,
    val start: String,
    val end: String,
)

@JsonClass(generateAdapter = true)
data class InsightCard(
    val type: String,
    val severity: String,
    val title: String,
    val detail: String,
    val query: InsightQuery? = null,
)

@JsonClass(generateAdapter = true)
data class InsightsResponse(val insights: List<InsightCard>, val count: Int)

@JsonClass(generateAdapter = true)
data class InsightSummaryResponse(
    val period: String,
    val enabled: Boolean,
    val available: Boolean,
    val model: String,
    @Json(name = "generated_at") val generatedAt: String?,
    @Json(name = "spending_patterns") val spendingPatterns: String,
    @Json(name = "how_to_save") val howToSave: String,
    @Json(name = "money_radar") val moneyRadar: String,
)

@JsonClass(generateAdapter = true)
data class NotificationDto(
    val id: String,
    val type: String,
    val severity: String,
    val title: String,
    val body: String,
    val route: String? = null,
    val anchor: String? = null,
)

@JsonClass(generateAdapter = true)
data class PushRegisterPayload(val token: String, val platform: String = "android")

@JsonClass(generateAdapter = true)
data class NotificationsResponse(val notifications: List<NotificationDto>, val count: Int)

@JsonClass(generateAdapter = true)
data class FindingDto(
    val id: Int,
    val type: String,
    val severity: String,
    val title: String,
    val detail: String,
    val amount: Double?,
    val currency: String,
    @Json(name = "occurred_at") val occurredAt: String,
    val status: String,
    @Json(name = "dedup_key") val dedupKey: String? = null,
    val query: FindingQuery? = null,
)

/**
 * Drill-down filter on a finding. Distinct from [InsightQuery] because Money Radar
 * findings carry only a merchant (and sometimes a category) — never a date range —
 * so every field must be nullable. (InsightQuery requires start/end.)
 */
@JsonClass(generateAdapter = true)
data class FindingQuery(
    val merchant: String? = null,
    @Json(name = "category_id") val categoryId: Int? = null,
    val start: String? = null,
    val end: String? = null,
)

@JsonClass(generateAdapter = true)
data class MoneyRadarResponse(
    val findings: List<FindingDto>,
    @Json(name = "new_count") val newCount: Int,
)

@JsonClass(generateAdapter = true)
data class RadarSummary(
    @Json(name = "new_count") val newCount: Int,
    val top: List<FindingDto>,
)

@JsonClass(generateAdapter = true)
data class RadarActionResponse(val ok: Boolean = true, val scanned: Int? = null)

@JsonClass(generateAdapter = true)
data class GoalDto(
    val id: Int, val name: String,
    @Json(name = "target_amount") val targetAmount: Double,
    @Json(name = "target_date") val targetDate: String?,
    val current: Double,
    @Json(name = "progress_pct") val progressPct: Double,
    val remaining: Double,
)

@JsonClass(generateAdapter = true)
data class GoalsResponse(val goals: List<GoalDto>, @Json(name = "net_worth") val netWorth: Double)

@JsonClass(generateAdapter = true)
data class GoalPayload(
    val name: String,
    @Json(name = "target_amount") val targetAmount: Double,
    @Json(name = "target_date") val targetDate: String? = null,
)

@JsonClass(generateAdapter = true)
data class OkResponse(val status: String = "ok")

// ── Profile / Portfolio ─────────────────────────────────────────────────────

@JsonClass(generateAdapter = true)
data class ProfileResponse(
    @Json(name = "has_pan") val hasPan: Boolean,
    @Json(name = "pan_masked") val panMasked: String?,
    val dob: String?,
)

@JsonClass(generateAdapter = true)
data class ProfileIn(val pan: String?, val dob: String?)

@JsonClass(generateAdapter = true)
data class HoldingDto(
    val isin: String,
    val name: String,
    val quantity: Double,
    val rate: Double,
    val value: Double,
    val type: String,
)

@JsonClass(generateAdapter = true)
data class UsStocksDto(
    val broker: String?,
    @Json(name = "account_last4") val accountLast4: String?,
    @Json(name = "invested_usd") val investedUsd: Double?,
    @Json(name = "current_usd") val currentUsd: Double,
    @Json(name = "current_inr") val currentInr: Double,
    @Json(name = "invested_inr") val investedInr: Double?,
    @Json(name = "returns_pct") val returnsPct: Double?,
    @Json(name = "fx_rate") val fxRate: Double,
    @Json(name = "as_of") val asOf: String?,
)

@JsonClass(generateAdapter = true)
data class PortfolioResponse(
    @Json(name = "as_of") val asOf: String?,
    @Json(name = "total_value") val totalValue: Double,
    @Json(name = "grand_total_value") val grandTotalValue: Double = 0.0,
    val count: Int,
    val holdings: List<HoldingDto>,
    @Json(name = "us_stocks") val usStocks: UsStocksDto? = null,
)

@JsonClass(generateAdapter = true)
data class SavingsAccountDto(
    val id: Int,
    val bank: String,
    val last4: String?,
    val balance: Double?,
    @Json(name = "as_of") val asOf: String?,
    val source: String,   // "sms" | "manual" | "none"
)

@JsonClass(generateAdapter = true)
data class FixedDepositDto(
    val id: Int,
    val bank: String,
    val principal: Double,
    @Json(name = "interest_rate") val interestRate: Double?,
    @Json(name = "start_date") val startDate: String?,
    @Json(name = "maturity_date") val maturityDate: String?,
    @Json(name = "maturity_amount") val maturityAmount: Double?,
    val note: String?,
)

@JsonClass(generateAdapter = true)
data class SavingsResponse(
    val accounts: List<SavingsAccountDto>,
    @Json(name = "savings_total") val savingsTotal: Double,
    @Json(name = "fixed_deposits") val fixedDeposits: List<FixedDepositDto>,
    @Json(name = "fd_total") val fdTotal: Double,
    @Json(name = "liquid_total") val liquidTotal: Double,
)

@JsonClass(generateAdapter = true)
data class ManualBalancePayload(
    val balance: Double,
    @Json(name = "as_of") val asOf: String? = null,
)

@JsonClass(generateAdapter = true)
data class FixedDepositPayload(
    val bank: String,
    val principal: Double,
    @Json(name = "interest_rate") val interestRate: Double? = null,
    @Json(name = "start_date") val startDate: String? = null,
    @Json(name = "maturity_date") val maturityDate: String? = null,
    @Json(name = "maturity_amount") val maturityAmount: Double? = null,
    val note: String? = null,
)

@JsonClass(generateAdapter = true)
data class PortfolioSyncResult(
    val status: String? = null,
    val holdings: Int? = null,
    @Json(name = "total_value") val totalValue: Double? = null,
    @Json(name = "as_of") val asOf: String? = null,
    val detail: String? = null,
)

@JsonClass(generateAdapter = true)
data class SourceCount(val total: Int, val parsed: Int)

@JsonClass(generateAdapter = true)
data class StatsResponse(
    val sms: SourceCount,
    val email: SourceCount,
    val transactions: Int,
)

// ── Category → sub-category breakdown (reports/breakdown) ──────────────────────

@JsonClass(generateAdapter = true)
data class SubcategoryDto(val name: String, val total: Double, val count: Int)

@JsonClass(generateAdapter = true)
data class CategoryBreakdownDto(
    val category: String,
    val total: Double,
    val count: Int,
    val subcategories: List<SubcategoryDto>,
)

@JsonClass(generateAdapter = true)
data class BreakdownResponse(
    val start: String,
    val end: String,
    val total: Double,
    val categories: List<CategoryBreakdownDto>,
)

@JsonClass(generateAdapter = true)
data class RewardOfferCategoryDto(
    val title: String,
    val description: String,
    val kind: String,
)

@JsonClass(generateAdapter = true)
data class RewardOptionDto(
    val label: String,
    val kind: String,
)

@JsonClass(generateAdapter = true)
data class RewardEventDto(
    val kind: String,
    val points: Int?,
    val merchant: String?,
    @Json(name = "occurred_at") val occurredAt: String,
    @Json(name = "voucher_code") val voucherCode: String?,
    @Json(name = "voucher_expiry") val voucherExpiry: String?,
)

@JsonClass(generateAdapter = true)
data class RewardCardDto(
    val bank: String,
    val last4: String,
    val program: String,
    @Json(name = "display_name") val displayName: String,
    @Json(name = "points_balance") val pointsBalance: Int,
    @Json(name = "value_inr") val valueInr: Double,
    @Json(name = "as_of") val asOf: String,
    @Json(name = "statement_period") val statementPeriod: String?,
    @Json(name = "expiring_points") val expiringPoints: Int,
    @Json(name = "expiring_date") val expiringDate: String?,
    val stale: Boolean,
    @Json(name = "redemption_options") val redemptionOptions: List<RewardOptionDto>,
    @Json(name = "recent_events") val recentEvents: List<RewardEventDto>,
    @Json(name = "portal_url") val portalUrl: String? = null,
    @Json(name = "offer_categories") val offerCategories: List<RewardOfferCategoryDto> = emptyList(),
    val network: String? = null,
)

@JsonClass(generateAdapter = true)
data class RewardsResponse(
    @Json(name = "total_value_inr") val totalValueInr: Double,
    @Json(name = "total_points") val totalPoints: Int,
    val cards: List<RewardCardDto>,
)

@JsonClass(generateAdapter = true)
data class CardUtilizationDto(
    @Json(name = "account_id") val accountId: Int,
    val bank: String,
    val last4: String?,
    @Json(name = "total_limit") val totalLimit: Double,
    val available: Double,
    val used: Double,
    @Json(name = "utilization_pct") val utilizationPct: Double,
    @Json(name = "as_of") val asOf: String?,
    val source: String,
)

@JsonClass(generateAdapter = true)
data class CardUtilizationResponse(val cards: List<CardUtilizationDto>)

@JsonClass(generateAdapter = true)
data class SetLimitPayload(
    @Json(name = "credit_limit") val creditLimit: Double? = null,
    val network: String? = null,
)

// ── API interface ─────────────────────────────────────────────────────────────

interface ExpenseApi {

    @POST("auth/unlock")
    suspend fun unlock(@Body payload: PinPayload): TokenResponse

    @POST("ingest/sms")
    suspend fun ingestSms(@Body payload: SmsPayload)

    @GET("transactions")
    suspend fun listTransactions(): List<TransactionDto>

    @GET("transactions/search")
    suspend fun searchTransactions(
        @Query("q")           q: String?   = null,
        @Query("merchant_any") merchantAny: String? = null,
        @Query("account_id")  accountId: Int? = null,
        @Query("category_id") categoryId: Int? = null,
        @Query("start")       start: String? = null,
        @Query("end")         end: String?   = null,
        @Query("min_amount")  minAmount: Double? = null,
        @Query("max_amount")  maxAmount: Double? = null,
        @Query("direction")   direction: String? = null,
    ): List<TransactionDto>

    @POST("transactions/manual")
    suspend fun createManual(@Body payload: ManualTxnPayload): TransactionDto

    @GET("reports/summary")
    suspend fun summary(@Query("month") month: String? = null): List<SummaryItem>

    @GET("reports/spent")
    suspend fun spent(@Query("month") month: String): SpentResponse

    @GET("reports/spent")
    suspend fun spentRange(
        @Query("start") start: String,
        @Query("end")   end: String,
    ): SpentResponse

    @GET("reports/months")
    suspend fun months(): MonthsResponse

    @GET("reports/breakdown")
    suspend fun breakdown(
        @Query("start") start: String,
        @Query("end") end: String,
        @Query("category_id") categoryId: Int? = null,
    ): BreakdownResponse

    @GET("categories")
    suspend fun categories(): List<CategoryDto>

    @GET("budget/{month}")
    suspend fun budget(@Path("month") month: String): BudgetResponse

    @PUT("budget/income")
    suspend fun setIncome(@Body payload: IncomePayload)

    @PUT("budget/assign")
    suspend fun assign(@Body payload: AssignPayload)

    @GET("accounts")
    suspend fun accounts(): List<AccountDto>

    @GET("networth")
    suspend fun netWorth(): NetWorthResponse

    @GET("networth/projection")
    suspend fun projection(@Query("years") years: Int = 10): ProjectionResponse

    @POST("sync/gmail")
    suspend fun syncGmail(): SyncResult

    @GET("auth/google")
    suspend fun authGoogle(): AuthGoogleResponse

    @GET("auth/config")
    suspend fun authConfig(): AuthConfigResponse

    @GET("auth/nonce")
    suspend fun authNonce(): NonceResponse

    @POST("auth/verify-google")
    suspend fun verifyGoogle(@Body body: VerifyGoogleRequest): AuthGoogleResponse

    @GET("reports/trend")
    suspend fun trend(): TrendResponse

    @GET("reports/top-merchants")
    suspend fun topMerchants(
        @Query("month") month: String? = null,
        @Query("limit") limit: Int = 10,
    ): TopMerchantsResponse

    @GET("reports/daily")
    suspend fun daily(@Query("month") month: String): DailyResponse

    @GET("stats")
    suspend fun stats(): StatsResponse

    @GET("bills")
    suspend fun bills(): List<BillDto>

    @GET("cards")
    suspend fun cards(): CardsResponse

    @GET("reports/rails")
    suspend fun rails(@Query("start") start: String, @Query("end") end: String): RailsResponse

    @GET("reports/spending-patterns")
    suspend fun spendingPatterns(@Query("start") start: String, @Query("end") end: String): SpendingPatternsResponse

    @GET("reports/subscriptions")
    suspend fun subscriptions(@Query("start") start: String, @Query("end") end: String): SubscriptionsResponse

    @GET("reports/savings-rate")
    suspend fun savingsRate(@Query("months") months: Int = 6): SavingsRateResponse

    @GET("reports/small-leaks")
    suspend fun smallLeaks(@Query("start") start: String, @Query("end") end: String): SmallLeaksResponse

    @GET("insights")
    suspend fun insights(): InsightsResponse

    @GET("insights/summary")
    suspend fun insightSummary(@Query("month") month: String?): InsightSummaryResponse

    @GET("notifications")
    suspend fun notifications(): NotificationsResponse

    @POST("push/register")
    suspend fun registerPush(@Body payload: PushRegisterPayload)

    @GET("money-radar")
    suspend fun moneyRadar(): MoneyRadarResponse

    @GET("money-radar/summary")
    suspend fun radarSummary(): RadarSummary

    @POST("money-radar/scan")
    suspend fun scanRadar(): RadarActionResponse

    @POST("money-radar/seen")
    suspend fun markRadarSeen(): RadarActionResponse

    @POST("money-radar/{id}/dismiss")
    suspend fun dismissFinding(@Path("id") id: Int): RadarActionResponse

    @GET("goals")
    suspend fun goals(): GoalsResponse

    @POST("goals")
    suspend fun addGoal(@Body body: GoalPayload): OkResponse

    @retrofit2.http.DELETE("goals/{id}")
    suspend fun deleteGoal(@Path("id") id: Int): OkResponse

    @POST("bills/{id}/paid")
    suspend fun markBillPaid(@Path("id") id: Int): OkResponse

    @GET("profile")
    suspend fun getProfile(): ProfileResponse

    @PUT("profile")
    suspend fun putProfile(@Body body: ProfileIn): OkResponse

    @GET("portfolio")
    suspend fun portfolio(): PortfolioResponse

    @POST("sync/portfolio")
    suspend fun syncPortfolio(): PortfolioSyncResult

    @GET("savings")
    suspend fun savings(): SavingsResponse

    @PUT("accounts/{id}/balance")
    suspend fun setBalance(@Path("id") id: Int, @Body body: ManualBalancePayload): OkResponse

    @POST("fixed-deposits")
    suspend fun addFixedDeposit(@Body body: FixedDepositPayload): OkResponse

    @retrofit2.http.DELETE("fixed-deposits/{id}")
    suspend fun deleteFixedDeposit(@Path("id") id: Int): OkResponse

    @GET("rewards")
    suspend fun rewards(): RewardsResponse

    @GET("cards/utilization")
    suspend fun cardUtilization(): CardUtilizationResponse

    @PUT("cards/{id}/limit")
    suspend fun setCardLimit(@Path("id") id: Int, @Body payload: SetLimitPayload)
}
