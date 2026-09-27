package com.ledgernoir.tracker.data.repository

import android.content.Context
import com.ledgernoir.tracker.BuildConfig
import com.ledgernoir.tracker.data.AuthInterceptor
import com.ledgernoir.tracker.data.TokenStore
import com.ledgernoir.tracker.data.api.*
import com.ledgernoir.tracker.sms.SmsFilter
import com.ledgernoir.tracker.sms.SmsHistoryReader
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/** Outcome of an SMS-history import run. */
data class ImportResult(
    val scanned: Int,  // total messages in the inbox
    val bank: Int,     // messages matched as bank senders
    val sent: Int,     // payloads successfully posted to the backend
)

class ExpenseRepository(
    private val tokenStore: TokenStore,
    private val appContext: Context? = null,
) {

    private val moshi: Moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    private fun buildClient(): OkHttpClient {
        val logging = HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BASIC
                    else HttpLoggingInterceptor.Level.NONE
            redactHeader("Authorization")
        }
        return OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(tokenStore))
            .addInterceptor(logging)
            .readTimeout(180, TimeUnit.SECONDS)
            .callTimeout(200, TimeUnit.SECONDS)
            .build()
    }

    private suspend fun api(): ExpenseApi {
        val baseUrl = tokenStore.baseUrl.first().trimEnd('/') + "/"
        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(buildClient())
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(ExpenseApi::class.java)
    }

    /** Returns the bearer token string or null if unlock is needed. */
    suspend fun unlock(pin: String): Result<String> = runCatching {
        val response = api().unlock(PinPayload(pin))
        tokenStore.setToken(response.token)
        response.token
    }

    suspend fun listTransactions(): Result<List<TransactionDto>> =
        runCatching { api().listTransactions() }

    suspend fun searchTransactions(
        q: String? = null,
        merchantAny: String? = null,
        accountId: Int? = null,
        categoryId: Int? = null,
        start: String? = null,
        end: String? = null,
        minAmount: Double? = null,
        maxAmount: Double? = null,
        direction: String? = null,
    ): Result<List<TransactionDto>> = runCatching {
        api().searchTransactions(q, merchantAny, accountId, categoryId, start, end, minAmount, maxAmount, direction)
    }

    suspend fun createManual(payload: ManualTxnPayload): Result<TransactionDto> =
        runCatching { api().createManual(payload) }

    suspend fun summary(): Result<List<SummaryItem>> =
        runCatching { api().summary() }

    suspend fun summary(month: String): Result<List<SummaryItem>> =
        runCatching { api().summary(month) }

    suspend fun spent(month: String): Result<Double> =
        runCatching { api().spent(month).spent }

    suspend fun spentRange(start: String, end: String): Result<Double> =
        runCatching { api().spentRange(start, end).spent }

    suspend fun months(): Result<List<String>> =
        runCatching { api().months().months }

    /** Category→sub-category spend breakdown for [start, end) (end exclusive, debit-only). */
    suspend fun breakdown(start: String, end: String, categoryId: Int? = null): Result<BreakdownResponse> =
        runCatching { api().breakdown(start, end, categoryId) }

    suspend fun categories(): Result<List<CategoryDto>> =
        runCatching { api().categories() }

    suspend fun budget(month: String): Result<BudgetResponse> =
        runCatching { api().budget(month) }

    suspend fun setIncome(month: String, amount: Double): Result<Unit> =
        runCatching { api().setIncome(IncomePayload(month, amount)) }

    suspend fun assign(categoryId: Int, month: String, assignedAmount: Double): Result<Unit> =
        runCatching { api().assign(AssignPayload(categoryId, month, assignedAmount)) }

    suspend fun accounts(): Result<List<AccountDto>> =
        runCatching { api().accounts() }

    suspend fun netWorth(): Result<Double> =
        runCatching { api().netWorth().netWorth }

    suspend fun netWorthFull(): Result<NetWorthResponse> =
        runCatching { api().netWorth() }

    suspend fun projection(years: Int = 10): Result<ProjectionResponse> =
        runCatching { api().projection(years) }

    suspend fun ingestSms(payload: SmsPayload): Result<Unit> =
        runCatching { api().ingestSms(payload) }

    suspend fun syncGmail(): Result<SyncResult> =
        runCatching { api().syncGmail() }

    suspend fun authGoogle(): Result<AuthGoogleResponse> =
        runCatching { api().authGoogle() }

    suspend fun authConfig(): Result<String> =
        runCatching { api().authConfig().google_web_client_id }

    suspend fun authNonce(): Result<String> =
        runCatching { api().authNonce().nonce }

    suspend fun verifyGoogle(idToken: String, nonce: String): Result<AuthGoogleResponse> =
        runCatching { api().verifyGoogle(VerifyGoogleRequest(idToken, nonce)) }

    suspend fun trend(): Result<List<TrendPoint>> =
        runCatching { api().trend().series }

    suspend fun topMerchants(month: String? = null, limit: Int = 10): Result<List<MerchantItem>> =
        runCatching { api().topMerchants(month, limit).merchants }

    suspend fun daily(month: String): Result<List<DailyPoint>> =
        runCatching { api().daily(month).days }

    suspend fun stats(): Result<StatsResponse> =
        runCatching { api().stats() }

    suspend fun bills(): Result<List<BillDto>> =
        runCatching { api().bills() }

    suspend fun cards(): Result<CardsResponse> =
        runCatching { api().cards() }

    suspend fun rails(start: String, end: String): Result<RailsResponse> =
        runCatching { api().rails(start, end) }

    suspend fun spendingPatterns(start: String, end: String): Result<SpendingPatternsResponse> =
        runCatching { api().spendingPatterns(start, end) }

    suspend fun subscriptions(start: String, end: String): Result<SubscriptionsResponse> =
        runCatching { api().subscriptions(start, end) }

    suspend fun savingsRate(months: Int = 6): Result<SavingsRateResponse> =
        runCatching { api().savingsRate(months) }

    suspend fun smallLeaks(start: String, end: String): Result<SmallLeaksResponse> =
        runCatching { api().smallLeaks(start, end) }

    suspend fun insights(): Result<InsightsResponse> =
        runCatching { api().insights() }

    suspend fun insightSummary(month: String?): Result<InsightSummaryResponse> =
        runCatching { api().insightSummary(month) }

    suspend fun notifications(): Result<NotificationsResponse> =
        runCatching { api().notifications() }

    suspend fun registerPushToken(token: String): Result<Unit> =
        runCatching { api().registerPush(com.ledgernoir.tracker.data.api.PushRegisterPayload(token)) }

    suspend fun moneyRadar(): Result<MoneyRadarResponse> =
        runCatching { api().moneyRadar() }

    suspend fun radarSummary(): Result<RadarSummary> =
        runCatching { api().radarSummary() }

    suspend fun scanRadar(): Result<RadarActionResponse> =
        runCatching { api().scanRadar() }

    suspend fun markRadarSeen(): Result<RadarActionResponse> =
        runCatching { api().markRadarSeen() }

    suspend fun dismissFinding(id: Int): Result<RadarActionResponse> =
        runCatching { api().dismissFinding(id) }

    suspend fun goals(): Result<GoalsResponse> =
        runCatching { api().goals() }

    suspend fun addGoal(name: String, target: Double, targetDate: String?): Result<Unit> =
        runCatching { api().addGoal(GoalPayload(name, target, targetDate)); Unit }

    suspend fun deleteGoal(id: Int): Result<Unit> =
        runCatching { api().deleteGoal(id); Unit }

    suspend fun markBillPaid(id: Int): Result<Unit> =
        runCatching { api().markBillPaid(id); Unit }

    // ── Profile / Portfolio ──────────────────────────────────────────────────

    suspend fun getProfile(): Result<ProfileResponse> =
        runCatching { api().getProfile() }

    suspend fun putProfile(pan: String?, dob: String?): Result<Unit> =
        runCatching { api().putProfile(ProfileIn(pan, dob)); Unit }

    suspend fun portfolio(): Result<PortfolioResponse> =
        runCatching { api().portfolio() }

    suspend fun syncPortfolio(): Result<PortfolioSyncResult> =
        runCatching { api().syncPortfolio() }

    suspend fun savings(): Result<SavingsResponse> =
        runCatching { api().savings() }

    suspend fun setBalance(accountId: Int, balance: Double, asOf: String?): Result<Unit> =
        runCatching { api().setBalance(accountId, ManualBalancePayload(balance, asOf)); Unit }

    suspend fun addFixedDeposit(fd: FixedDepositPayload): Result<Unit> =
        runCatching { api().addFixedDeposit(fd); Unit }

    suspend fun deleteFixedDeposit(id: Int): Result<Unit> =
        runCatching { api().deleteFixedDeposit(id); Unit }

    suspend fun rewards(): Result<RewardsResponse> =
        runCatching { api().rewards() }

    suspend fun cardUtilization(): Result<CardUtilizationResponse> =
        runCatching { api().cardUtilization() }

    suspend fun setCardLimit(
        id: Int,
        creditLimit: Double? = null,
        network: String? = null,
    ): Result<Unit> =
        runCatching { api().setCardLimit(id, SetLimitPayload(creditLimit, network)) }

    /**
     * Reads the device SMS inbox, filters to whitelisted bank senders, and posts
     * each matching message to the backend. external_id is "sms-<id>" so re-imports
     * are idempotent server-side. Each POST is wrapped in runCatching so a single
     * failure does not abort the batch. Requires the repository to have been built
     * with an Application context; otherwise returns an empty result.
     */
    suspend fun importSmsHistory(
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): ImportResult {
        val ctx = appContext ?: return ImportResult(0, 0, 0)
        val filter = SmsFilter()
        val isoFormatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME

        val lastImportMillis = tokenStore.lastSmsImportMillis.first()

        val inbox = SmsHistoryReader(ctx).readInbox()
        val bankMessages = inbox.filter { filter.isWhitelisted(it.address) }
        // Only POST messages newer than the last import (first run: lastImportMillis=0 -> all).
        val toSend = bankMessages.filter { it.dateMillis > lastImportMillis }
        onProgress(0, toSend.size)

        var sent = 0
        toSend.forEachIndexed { index, sms ->
            val receivedAt = Instant.ofEpochMilli(sms.dateMillis)
                .atZone(ZoneId.systemDefault())
                .toLocalDateTime()
                .format(isoFormatter)

            val payload = SmsPayload(
                sender     = filter.normalizeSender(sms.address),
                body       = sms.body,
                receivedAt = receivedAt,
                externalId = "sms-${sms.id}",
            )

            runCatching { api().ingestSms(payload) }
                .onSuccess { sent++ }

            onProgress(index + 1, toSend.size)
        }

        // Advance the watermark to the newest message seen across the whole inbox so
        // the next launch only considers strictly-newer messages.
        val maxSeen = inbox.maxOfOrNull { it.dateMillis } ?: 0L
        if (maxSeen > lastImportMillis) {
            tokenStore.setLastSmsImportMillis(maxSeen)
        }

        return ImportResult(scanned = inbox.size, bank = bankMessages.size, sent = sent)
    }
}
