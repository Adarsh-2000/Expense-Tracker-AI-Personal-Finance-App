package com.ledgernoir.tracker.ui.dashboard

import com.ledgernoir.tracker.ui.designsystem.*

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ledgernoir.tracker.ui.common.ledgerViewModelFactory
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ledgernoir.tracker.LedgerNoirApp
import com.ledgernoir.tracker.SyncPhase
import com.ledgernoir.tracker.data.repository.ExpenseRepository
import com.ledgernoir.tracker.data.api.SummaryItem
import com.ledgernoir.tracker.ui.theme.AccentGreen
import com.ledgernoir.tracker.ui.theme.AccentTeal
import com.ledgernoir.tracker.ui.theme.CanvasBlack
import com.ledgernoir.tracker.ui.theme.CreditGreen
import com.ledgernoir.tracker.ui.theme.DebitRose
import com.ledgernoir.tracker.ui.theme.DisplayFamily
import com.ledgernoir.tracker.ui.theme.SurfaceMid
import com.ledgernoir.tracker.ui.theme.TextMuted
import com.ledgernoir.tracker.ui.theme.TextPrimary
import com.ledgernoir.tracker.ui.theme.TextSecondary
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun DashboardScreen(
    app: LedgerNoirApp,
    repository: ExpenseRepository,
    smsPermissionGranted: Boolean,
    onOpenRange: (start: String, end: String, title: String) -> Unit = { _, _, _ -> },
    onOpenCategory: (start: String, end: String, catId: Int, title: String) -> Unit = { _, _, _, _ -> },
    onOpenSettings: () -> Unit = {},
    onOpenNetWorth: () -> Unit = {},
    onOpenNotifications: () -> Unit = {},
    onOpenMoneyRadar: () -> Unit = {},
    onOpenRewards: () -> Unit = {},
) {
    when (com.ledgernoir.tracker.ui.designsystem.LocalFormFactor.current) {
        com.ledgernoir.tracker.ui.designsystem.FormFactor.Cover -> DashboardCover(
            app, repository, smsPermissionGranted, onOpenRange, onOpenCategory,
            onOpenSettings, onOpenNetWorth, onOpenNotifications, onOpenMoneyRadar, onOpenRewards,
        )
        else -> DashboardMain(
            app, repository, smsPermissionGranted, onOpenRange, onOpenCategory,
            onOpenSettings, onOpenNetWorth, onOpenNotifications, onOpenMoneyRadar, onOpenRewards,
        )
    }
}

@Composable
fun DashboardMain(
    app: LedgerNoirApp,
    repository: ExpenseRepository,
    smsPermissionGranted: Boolean,
    onOpenRange: (start: String, end: String, title: String) -> Unit = { _, _, _ -> },
    onOpenCategory: (start: String, end: String, catId: Int, title: String) -> Unit = { _, _, _, _ -> },
    onOpenSettings: () -> Unit = {},
    onOpenNetWorth: () -> Unit = {},
    onOpenNotifications: () -> Unit = {},
    onOpenMoneyRadar: () -> Unit = {},
    onOpenRewards: () -> Unit = {},
) {
    val vm: DashboardViewModel = viewModel(
        factory = ledgerViewModelFactory {
            DashboardViewModel(
                it.useCases.getSpentRange, it.useCases.getCategories, it.useCases.getMonths,
                it.useCases.getMonthSummary, it.useCases.getSpent, it.useCases.getNetWorth,
                it.useCases.getStats, it.useCases.getNotifications, it.useCases.getRadarSummary,
                it.useCases.getRewards, it,
            )
        },
    )
    val state by vm.uiState.collectAsStateWithLifecycle()
    val phase by vm.syncPhase.collectAsStateWithLifecycle()

    val haptics = LocalHapticFeedback.current

    // Read derived values straight off the immutable state.
    val summary = state.summary
    val netWorth = state.netWorth
    val spentThisMonth = state.spentThisMonth
    val prevMonthSpent = state.prevMonthSpent
    val stats = state.stats
    val loading = state.loading
    val error = state.error
    val last3Total = state.last3Total
    val last3StartIso = state.last3StartIso
    val last3EndIso = state.last3EndIso
    val categoryIdByName = state.categoryIdByName
    val months = state.months
    val selectedMonth = state.selectedMonth

    // Automatic SMS detection: kick off once per process when permission is granted.
    LaunchedEffect(smsPermissionGranted) {
        vm.startSyncIfPermitted(smsPermissionGranted)
    }

    // When the sync finishes, refresh the months list and dashboard data so new
    // transactions appear.
    LaunchedEffect(phase) {
        if (phase is SyncPhase.Done) {
            vm.onSyncCompleted()
        }
    }

    // Refresh net worth each time the dashboard returns to the foreground so
    // changes made on Savings/Portfolio (new FD, edited balance, synced holdings)
    // are reflected without a full reload.
    LifecycleResumeEffect(Unit) {
        vm.refreshNetWorth()
        onPauseOrDispose { }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CanvasBlack)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 20.dp),
    ) {
        ResponsiveContainer {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text  = "Dashboard",
                    style = MaterialTheme.typography.headlineMedium,
                    color = TextPrimary,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    NotificationBell(
                        unread = state.unreadNotifications,
                        onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            onOpenNotifications()
                        },
                    )
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(SurfaceMid)
                            .clickable {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                onOpenSettings()
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Settings",
                            tint = TextSecondary,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(20.dp))

            // Manual sync controls: pull new SMS / new emails on demand (auto-sync
            // only runs once per launch, so this catches anything that arrived since).
            val syncing = phase is SyncPhase.ReadingInbox ||
                phase is SyncPhase.Importing || phase is SyncPhase.SyncingEmail
            SyncActionsRow(
                syncing = syncing,
                onSyncSms = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    vm.syncSms()
                },
                onSyncEmail = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    vm.syncEmail()
                },
            )
            Spacer(Modifier.height(16.dp))

            if (state.radarNewCount > 0) {
                val summary = state.radarTop.take(2).joinToString(" · ") { it.title }
                    .ifBlank { "${state.radarNewCount} new finding${if (state.radarNewCount == 1) "" else "s"}" }
                MoneyRadarStripCard(
                    newCount = state.radarNewCount,
                    summary = summary,
                    onClick = onOpenMoneyRadar,
                )
                Spacer(Modifier.height(16.dp))
            }

            if (state.rewardsExpiringLabel != null) {
                RewardsExpiryStripCard(
                    label = state.rewardsExpiringLabel!!,
                    onClick = { onOpenRewards() },
                )
                Spacer(Modifier.height(16.dp))
            }

            if (!smsPermissionGranted) {
                SmsBanner()
                Spacer(Modifier.height(16.dp))
            }

            // Automatic SMS-sync status card (reflects the current SyncPhase).
            if (phase !is SyncPhase.Idle) {
                SyncStatusCard(phase)
                Spacer(Modifier.height(16.dp))
            }

            // Persistent source stats: SMS + email parsed/total.
            stats?.let {
                SourcesCard(it)
                Spacer(Modifier.height(16.dp))
            }

            if (loading) {
                Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = AccentTeal)
                }
            } else {
                if (error != null) {
                    Text(
                        text = "Error: $error",
                        color = DebitRose,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(16.dp))
                }

                // Hero — big count-up Spent for the selected month, with MoM delta.
                SpentHeroCard(
                    amount = spentThisMonth,
                    prevAmount = prevMonthSpent,
                    selectedMonth = selectedMonth,
                    months = months,
                    onSelect = { vm.selectMonth(it) },
                )
                Spacer(Modifier.height(20.dp))

                // Net worth card
                netWorth?.let {
                    SecondaryHeroCard(label = "NET WORTH", amount = it, color = AccentTeal,
                        onClick = onOpenNetWorth)
                    Spacer(Modifier.height(20.dp))
                }

                // Trailing-3-month spend card → opens a sortable drill-down list.
                Last3MonthsCard(
                    total = last3Total,
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onOpenRange(last3StartIso, last3EndIso, "Last 3 Months")
                    },
                )
                Spacer(Modifier.height(20.dp))

                // Per-category breakdown — centered donut + iconized ranked bars.
                if (summary.isNotEmpty()) {
                    val month = selectedMonth
                    WhereItWentCard(
                        summary = summary,
                        onCategoryClick = { categoryName ->
                            val catId = categoryIdByName[categoryName.lowercase().trim()]
                            if (catId != null && month != null) {
                                val (mStart, mEnd) = monthRange(month)
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                onOpenCategory(
                                    mStart,
                                    mEnd,
                                    catId,
                                    categoryName.replaceFirstChar { it.uppercase() },
                                )
                            }
                        },
                    )
                } else {
                    EmptyCard(message = "No spending recorded for this month yet.")
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

/**
 * Compact cover-display Dashboard: net worth hero + spent-this-month, with optional
 * radar / notification strips. Shares the SAME [DashboardViewModel] instance as
 * [DashboardMain] (same nav destination → same ViewModelStoreOwner + key), so state
 * survives the fold-driven swap.
 */
@Composable
private fun DashboardCover(
    app: LedgerNoirApp,
    repository: ExpenseRepository,
    smsPermissionGranted: Boolean,
    onOpenRange: (start: String, end: String, title: String) -> Unit,
    onOpenCategory: (start: String, end: String, catId: Int, title: String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenNetWorth: () -> Unit,
    onOpenNotifications: () -> Unit,
    onOpenMoneyRadar: () -> Unit,
    onOpenRewards: () -> Unit,
) {
    val vm: DashboardViewModel = viewModel(
        factory = ledgerViewModelFactory {
            DashboardViewModel(
                it.useCases.getSpentRange, it.useCases.getCategories, it.useCases.getMonths,
                it.useCases.getMonthSummary, it.useCases.getSpent, it.useCases.getNetWorth,
                it.useCases.getStats, it.useCases.getNotifications, it.useCases.getRadarSummary,
                it.useCases.getRewards, it,
            )
        },
    )
    val state by vm.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(smsPermissionGranted) { vm.startSyncIfPermitted(smsPermissionGranted) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CanvasBlack)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        HeroNumber(
            label = "Net worth",
            amount = state.netWorth ?: 0.0,
            change = null,
            modifier = Modifier.clickable { onOpenNetWorth() },
        )
        Spacer(Modifier.height(12.dp))
        CredCard {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Spent this month",
                    color = TextSecondary,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.weight(1f),
                )
                MoneyText(
                    amount = state.spentThisMonth,
                    fontSize = 18.sp,
                    color = DebitRose,
                    showPaise = false,
                )
            }
        }
        if (state.radarNewCount > 0) {
            Spacer(Modifier.height(10.dp))
            CredCard(modifier = Modifier.clickable { onOpenMoneyRadar() }) {
                Text(
                    "📡 Money Radar · ${state.radarNewCount} new",
                    color = Color(0xFFFBBF24),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        if (state.unreadNotifications > 0) {
            Spacer(Modifier.height(10.dp))
            CredCard(modifier = Modifier.clickable { onOpenNotifications() }) {
                Text(
                    "🔔 ${state.unreadNotifications} notifications",
                    color = TextPrimary,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

/** Reusable uppercase section/eyebrow label. */
@Composable
private fun SectionLabel(text: String, color: Color = TextSecondary) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = color,
        letterSpacing = 1.4.sp,
    )
}

/** Header bell with an unread-count badge (capped "9+"). Opens the notification center. */
@Composable
private fun NotificationBell(unread: Int, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(SurfaceMid)
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Default.Notifications,
            contentDescription = "Notifications",
            tint = if (unread > 0) AccentTeal else TextSecondary,
            modifier = Modifier.size(20.dp),
        )
        if (unread > 0) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(16.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(DebitRose),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (unread > 9) "9+" else unread.toString(),
                    color = Color.White,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun SyncActionsRow(
    syncing: Boolean,
    onSyncSms: () -> Unit,
    onSyncEmail: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SyncPill("Sync Messages", Icons.Default.Sync, syncing, Modifier.weight(1f), onSyncSms)
        SyncPill("Sync Email", Icons.Default.Email, syncing, Modifier.weight(1f), onSyncEmail)
    }
}

@Composable
private fun SyncPill(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    disabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(SurfaceMid)
            .clickable(enabled = !disabled, onClick = onClick)
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (disabled) TextMuted else AccentTeal,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = if (disabled) TextMuted else TextPrimary,
            maxLines = 1,
            softWrap = false,
        )
    }
}

@Composable
private fun SourcesCard(stats: com.ledgernoir.tracker.data.api.StatsResponse) {
    CredCard {
        Text(
            text = "SOURCES",
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondary,
            letterSpacing = 1.4.sp,
        )
        Spacer(Modifier.height(12.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            SourceStat("SMS", stats.sms.parsed, stats.sms.total, Modifier.weight(1f))
            Spacer(Modifier.width(12.dp))
            SourceStat("Email", stats.email.parsed, stats.email.total, Modifier.weight(1f))
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = "${stats.transactions} transactions tracked",
            style = MaterialTheme.typography.labelMedium,
            color = AccentGreen,
        )
    }
}

@Composable
private fun SourceStat(label: String, parsed: Int, total: Int, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(SurfaceMid, RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = TextSecondary)
        Spacer(Modifier.height(6.dp))
        Text(
            text = "%,d".format(parsed),
            style = MaterialTheme.typography.titleLarge.copy(fontFamily = DisplayFamily),
            color = TextPrimary,
        )
        Text(
            text = "of %,d read".format(total),
            style = MaterialTheme.typography.labelSmall,
            color = TextMuted,
        )
    }
}

@Composable
private fun SyncStatusCard(phase: SyncPhase) {
    CredCard {
        when (phase) {
            is SyncPhase.ReadingInbox -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        color = AccentTeal,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text  = "Reading your messages…",
                        style = MaterialTheme.typography.labelLarge,
                        color = TextPrimary,
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text  = "Scanning SMS inbox for bank transactions",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary,
                )
            }

            is SyncPhase.Importing -> {
                Text(
                    text  = "Importing transactions… ${phase.done} / ${phase.total}",
                    style = MaterialTheme.typography.labelLarge,
                    color = TextPrimary,
                )
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress = { if (phase.total > 0) phase.done.toFloat() / phase.total else 0f },
                    color = AccentTeal,
                    trackColor = SurfaceMid,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp),
                )
            }

            is SyncPhase.SyncingEmail -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        color = AccentTeal,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text  = "Syncing email…",
                        style = MaterialTheme.typography.labelLarge,
                        color = TextPrimary,
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text  = "Fetching bank transaction emails",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary,
                )
            }

            is SyncPhase.Done -> {
                val emailSuffix = if (phase.emailIngested > 0) " · ${phase.emailIngested} from email" else ""
                Text(
                    text  = "Synced ✓  ${phase.sent} from SMS$emailSuffix",
                    style = MaterialTheme.typography.labelLarge,
                    color = TextPrimary,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text  = "${phase.scanned} scanned · ${phase.bank} bank messages",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary,
                )
                if (phase.bank == 0) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text  = "New bank messages will be captured automatically.",
                        style = MaterialTheme.typography.labelSmall,
                        color = AccentGreen,
                    )
                }
            }

            is SyncPhase.Failed -> {
                Text(
                    text  = "Sync failed: ${phase.message}",
                    style = MaterialTheme.typography.labelLarge,
                    color = DebitRose,
                )
            }

            is SyncPhase.Idle -> {
                // unreachable; caller gates on Idle
            }
        }
    }
}

/**
 * Premium "Where it went" card: a rounded-cap donut with the total centered, then
 * ranked category rows with icon chip + proportional bar.
 */
@Composable
private fun WhereItWentCard(
    summary: List<SummaryItem>,
    onCategoryClick: (String) -> Unit,
) {
    CredCard {
        SectionLabel("Where it went")
        Spacer(Modifier.height(18.dp))

        CategoryDonut(summary)

        Spacer(Modifier.height(20.dp))

        val maxTotal = summary.maxOf { it.total }.takeIf { it > 0.0 } ?: 1.0
        val ranked = summary.sortedByDescending { it.total }
        ranked.forEachIndexed { i, item ->
            CategoryRow(
                item = item,
                fraction = (item.total / maxTotal).toFloat(),
                onClick = { onCategoryClick(item.category) },
            )
            if (i < ranked.size - 1) Spacer(Modifier.height(16.dp))
        }
    }
}

/**
 * Trailing-3-month spend card (current month + 2 prior). Whole card is tappable
 * and opens a sortable drill-down list of every transaction in the window.
 */
@Composable
private fun Last3MonthsCard(total: Double?, onClick: () -> Unit) {
    CredCard(modifier = Modifier.clickable(onClick = onClick)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                SectionLabel("LAST 3 MONTHS")
                Spacer(Modifier.height(10.dp))
                if (total != null) {
                    MoneyText(amount = total, fontSize = 28.sp, color = TextPrimary)
                } else {
                    Text(
                        text = "—",
                        style = MaterialTheme.typography.headlineSmall,
                        color = TextSecondary,
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "View all transactions",
                    style = MaterialTheme.typography.labelSmall,
                    color = AccentTeal,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text(
                text = "›",
                style = MaterialTheme.typography.headlineMedium,
                color = AccentTeal,
            )
        }
    }
}

/**
 * Returns the inclusive [start, end] ISO date bounds for a "YYYY-MM" month key.
 * End is the last day of that month (search end is inclusive).
 */
private fun monthRange(month: String): Pair<String, String> {
    return try {
        val ym = java.time.YearMonth.parse(month)
        ym.atDay(1).toString() to ym.atEndOfMonth().toString()
    } catch (e: Exception) {
        "$month-01" to "$month-28"
    }
}

/**
 * Hero "Spent" card: an oversized count-up amount with eyebrow label, an inline
 * month selector pill, and a MoM delta chip. AnimatedContent swaps on month change.
 */
@Composable
private fun SpentHeroCard(
    amount: Double,
    prevAmount: Double?,
    selectedMonth: String?,
    months: List<String>,
    onSelect: (String) -> Unit,
) {
    // Count-up animation toward the target spend whenever it changes.
    val animated by animateFloatAsState(
        targetValue = amount.toFloat(),
        animationSpec = tween(900),
        label = "spendCountUp",
    )
    CredCard(padding = 24) {
        SectionLabel("SPENT · ${formatMonthLabel(selectedMonth)}")
        Spacer(Modifier.height(12.dp))

        AnimatedContent(
            targetState = selectedMonth,
            transitionSpec = {
                (fadeIn(tween(300)) + slideInVertically { it / 4 })
                    .togetherWith(fadeOut(tween(200)) + slideOutVertically { -it / 4 })
            },
            label = "monthSwap",
        ) { _ ->
            // Whole rupees (no paise) + length-bucketed auto-shrink so very large
            // months (e.g. ₹ 3,00,426) stay on a single line and never clip.
            val formatted = formatMoney(animated.toDouble(), showPaise = false)
            val heroSize = when {
                formatted.length > 14 -> 30.sp
                formatted.length > 12 -> 36.sp
                formatted.length > 10 -> 42.sp
                else -> 48.sp
            }
            Text(
                text  = formatted,
                style = MaterialTheme.typography.displayLarge.copy(fontSize = heroSize),
                color = TextPrimary,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(14.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MonthOverMonthChip(amount = amount, prevAmount = prevAmount, selectedMonth = selectedMonth)
            MonthSelector(
                selectedMonth = selectedMonth,
                months = months,
                onSelect = onSelect,
            )
        }
    }
}

@Composable
private fun MonthOverMonthChip(amount: Double, prevAmount: Double?, selectedMonth: String?) {
    val prevLabel = run {
        val m = try {
            selectedMonth?.let { java.time.YearMonth.parse(it).minusMonths(1) }
        } catch (e: Exception) {
            null
        }
        m?.month?.getDisplayName(
            java.time.format.TextStyle.SHORT,
            java.util.Locale.getDefault(),
        ) ?: "last month"
    }

    if (prevAmount == null || prevAmount <= 0.0) {
        Box(
            modifier = Modifier
                .background(SurfaceMid, RoundedCornerShape(20.dp))
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Text(
                text  = "— no prior data",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary,
            )
        }
    } else {
        val pct = (amount - prevAmount) / prevAmount * 100.0
        val up = pct >= 0
        val arrow = if (up) "▲" else "▼"
        val color = if (up) DebitRose else CreditGreen
        Box(
            modifier = Modifier
                .background(color.copy(alpha = 0.14f), RoundedCornerShape(20.dp))
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Text(
                text  = "$arrow ${abs(pct).roundToInt()}% vs $prevLabel",
                style = MaterialTheme.typography.labelSmall,
                color = color,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun SecondaryHeroCard(label: String, amount: Double, color: Color, onClick: (() -> Unit)? = null) {
    CredCard(modifier = if (onClick != null) Modifier.clickable { onClick() } else Modifier) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            SectionLabel(label)
            if (onClick != null) {
                Spacer(Modifier.weight(1f))
                Text("View →", color = color, style = MaterialTheme.typography.labelMedium)
            }
        }
        Spacer(Modifier.height(10.dp))
        MoneyText(
            amount = amount,
            fontSize = 32.sp,
            color = color,
            showPaise = false,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun EmptyCard(message: String) {
    CredCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CategoryIconChip(null, size = 48.dp)
            Spacer(Modifier.height(12.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * Ranked category row: a colored category icon chip, the category name + amount,
 * and a thin proportional bar tinted in the category color.
 */
@Composable
private fun CategoryRow(item: SummaryItem, fraction: Float, onClick: () -> Unit) {
    val color = styleFor(item.category).color
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CategoryIconChip(item.category, size = 40.dp)
        Spacer(Modifier.width(14.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text  = item.category.replaceFirstChar { it.uppercase() },
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(12.dp))
                MoneyText(amount = item.total, fontSize = 15.sp, color = TextPrimary)
            }
            // Proportional bar
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .background(SurfaceMid, RoundedCornerShape(3.dp)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction.coerceIn(0f, 1f))
                        .height(6.dp)
                        .background(color, RoundedCornerShape(3.dp)),
                )
            }
        }
    }
}

/**
 * Formats a "YYYY-MM" month key into a friendly "May 2025" label. Falls back to
 * the raw value if it cannot be parsed.
 */
private fun formatMonthLabel(month: String?): String {
    if (month == null) return "—"
    return try {
        val ym = java.time.YearMonth.parse(month)
        val name = ym.month.getDisplayName(
            java.time.format.TextStyle.FULL,
            java.util.Locale.getDefault(),
        )
        "$name ${ym.year}"
    } catch (e: Exception) {
        month
    }
}

private fun formatMonthShort(month: String?): String {
    if (month == null) return "—"
    return try {
        val ym = java.time.YearMonth.parse(month)
        val name = ym.month.getDisplayName(
            java.time.format.TextStyle.SHORT,
            java.util.Locale.getDefault(),
        )
        "$name '${ym.year % 100}"
    } catch (e: Exception) {
        month
    }
}

/**
 * Inline month navigation pill: ◀ steps to the next OLDER month with data (a later
 * index in the DESC-sorted [months] list), ▶ steps to the newer month. Disabled
 * chevrons grey out when there's nothing further in that direction.
 */
@Composable
private fun MonthSelector(
    selectedMonth: String?,
    months: List<String>,
    onSelect: (String) -> Unit,
) {
    val index = months.indexOf(selectedMonth)
    val canGoOlder = months.isNotEmpty() && index < months.size - 1
    val canGoNewer = index > 0

    Row(
        modifier = Modifier
            .background(SurfaceMid, RoundedCornerShape(20.dp))
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Chevron(
            glyph = "‹",
            enabled = canGoOlder || (index < 0 && months.isNotEmpty()),
            onClick = {
                if (canGoOlder) onSelect(months[index + 1])
                else if (index < 0 && months.isNotEmpty()) onSelect(months.first())
            },
        )
        Text(
            text  = formatMonthShort(selectedMonth),
            style = MaterialTheme.typography.labelMedium.copy(fontFamily = DisplayFamily),
            color = TextPrimary,
            modifier = Modifier.padding(horizontal = 6.dp),
        )
        Chevron(
            glyph = "›",
            enabled = canGoNewer,
            onClick = { if (canGoNewer) onSelect(months[index - 1]) },
        )
    }
}

@Composable
private fun Chevron(glyph: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(28.dp)
            .let { m -> if (enabled) m.clickable(onClick = onClick) else m },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text  = glyph,
            style = MaterialTheme.typography.titleLarge,
            color = if (enabled) AccentTeal else TextSecondary.copy(alpha = 0.3f),
        )
    }
}

@Composable
private fun SmsBanner() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(DebitRose.copy(alpha = 0.10f), RoundedCornerShape(14.dp))
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text  = "SMS permission not granted — bank messages won't be forwarded automatically.",
            style = MaterialTheme.typography.labelSmall,
            color = DebitRose,
        )
    }
}

@Composable
private fun RewardsExpiryStripCard(label: String, onClick: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(SurfaceMid)
            .clickable {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onClick()
            }
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("⚠️", fontSize = 18.sp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "Points expiring",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary,
                    letterSpacing = 1.2.sp,
                )
                Spacer(Modifier.height(2.dp))
                Text(label, style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
            }
            Text("Redeem →", style = MaterialTheme.typography.labelMedium, color = AccentGreen)
        }
    }
}

/**
 * "You might've missed" strip — shown when Money Radar has new findings. Tapping
 * opens the full Money Radar screen.
 *
 * Note: Icons.Filled.Visibility is not in this project's icon set; using
 * Icons.Filled.Insights as a semantically appropriate substitute.
 */
@Composable
private fun MoneyRadarStripCard(newCount: Int, summary: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(SurfaceMid)
            .clickable(onClick = onClick)
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Filled.Insights,
                contentDescription = null,
                tint = Color(0xFFEF4444),
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "You might've missed",
                    style = MaterialTheme.typography.titleSmall,
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = summary,
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = "See all →",
                style = MaterialTheme.typography.labelMedium,
                color = Color(0xFFEF4444),
            )
        }
    }
}
