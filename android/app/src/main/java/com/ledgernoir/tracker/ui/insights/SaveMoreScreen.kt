package com.ledgernoir.tracker.ui.insights

import com.ledgernoir.tracker.ui.designsystem.*

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ledgernoir.tracker.ui.common.ledgerViewModelFactory
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ledgernoir.tracker.data.repository.ExpenseRepository
import com.ledgernoir.tracker.data.api.GoalDto
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.ui.draw.rotate
import com.ledgernoir.tracker.data.api.InsightCard
import com.ledgernoir.tracker.data.api.TransactionDto
import com.ledgernoir.tracker.data.api.InsightsResponse
import com.ledgernoir.tracker.data.api.LeakItem
import com.ledgernoir.tracker.data.api.SavingsRateResponse
import com.ledgernoir.tracker.data.api.SubscriptionItem
import com.ledgernoir.tracker.data.api.SubscriptionsResponse
import com.ledgernoir.tracker.data.api.GoalsResponse
import com.ledgernoir.tracker.data.api.SmallLeaksResponse
import com.ledgernoir.tracker.ui.theme.AccentGreen
import com.ledgernoir.tracker.ui.theme.CreditGreen
import com.ledgernoir.tracker.ui.theme.AccentTeal
import com.ledgernoir.tracker.ui.theme.CanvasBlack
import com.ledgernoir.tracker.ui.theme.DebitRose
import com.ledgernoir.tracker.ui.theme.DisplayFamily
import com.ledgernoir.tracker.ui.theme.SurfaceDark
import com.ledgernoir.tracker.ui.theme.SurfaceMid
import com.ledgernoir.tracker.ui.theme.TextMuted
import com.ledgernoir.tracker.ui.theme.TextPrimary
import com.ledgernoir.tracker.ui.theme.TextSecondary

private val Amber = Color(0xFFF59E0B)

@Composable
fun SaveMoreScreen(repository: com.ledgernoir.tracker.data.repository.ExpenseRepository) {
    when (com.ledgernoir.tracker.ui.designsystem.LocalFormFactor.current) {
        com.ledgernoir.tracker.ui.designsystem.FormFactor.Cover -> SaveMoreCover(repository)
        else -> SaveMoreMain(repository)
    }
}

@Composable
fun SaveMoreMain(repository: com.ledgernoir.tracker.data.repository.ExpenseRepository) {
    val vm: SaveMoreViewModel = viewModel(
        factory = ledgerViewModelFactory {
            SaveMoreViewModel(
                it.useCases.getSavingsRate, it.useCases.getInsights, it.useCases.getSubscriptions,
                it.useCases.getSmallLeaks, it.useCases.getGoals, it.useCases.addGoal,
                it.useCases.deleteGoal, it.useCases.searchTransactions,
            )
        },
    )
    val state by vm.uiState.collectAsStateWithLifecycle()
    var showAddGoal by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CanvasBlack)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 20.dp),
    ) {
        ResponsiveContainer {
            Text(
                text = "Save More",
                style = MaterialTheme.typography.headlineMedium,
                color = TextPrimary,
                fontFamily = DisplayFamily,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Spend less, save more",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
            )
            Spacer(Modifier.height(20.dp))
            SaveMoreBody(state = state, vm = vm, showAddGoal = showAddGoal, onShowAddGoal = { showAddGoal = it })
            Spacer(Modifier.height(8.dp))
        }
    }

    if (showAddGoal) {
        AddGoalDialog(
            onDismiss = { showAddGoal = false },
            onSave = { name, target, date ->
                vm.addGoal(name, target, date)
                showAddGoal = false
            },
        )
    }
}

@Composable
private fun SaveMoreCover(repository: com.ledgernoir.tracker.data.repository.ExpenseRepository) {
    val vm: SaveMoreViewModel = viewModel(
        factory = ledgerViewModelFactory {
            SaveMoreViewModel(
                it.useCases.getSavingsRate, it.useCases.getInsights, it.useCases.getSubscriptions,
                it.useCases.getSmallLeaks, it.useCases.getGoals, it.useCases.addGoal,
                it.useCases.deleteGoal, it.useCases.searchTransactions,
            )
        },
    )
    val state by vm.uiState.collectAsStateWithLifecycle()
    var showAddGoal by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CanvasBlack)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 12.dp),
    ) {
        Text(
            text = "Save More",
            style = MaterialTheme.typography.titleMedium,
            color = TextPrimary,
            fontFamily = DisplayFamily,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = "Spend less, save more",
            style = MaterialTheme.typography.labelMedium,
            color = TextSecondary,
        )
        Spacer(Modifier.height(12.dp))
        SaveMoreBody(state = state, vm = vm, showAddGoal = showAddGoal, onShowAddGoal = { showAddGoal = it })
        Spacer(Modifier.height(4.dp))
    }

    if (showAddGoal) {
        AddGoalDialog(
            onDismiss = { showAddGoal = false },
            onSave = { name, target, date ->
                vm.addGoal(name, target, date)
                showAddGoal = false
            },
        )
    }
}

@Composable
private fun SaveMoreBody(
    state: SaveMoreUiState,
    vm: SaveMoreViewModel,
    showAddGoal: Boolean,
    onShowAddGoal: (Boolean) -> Unit,
) {
    val insights = state.insights
    val savingsRate = state.savingsRate
    val subscriptions = state.subscriptions
    val leaks = state.leaks
    val goals = state.goals
    val loading = state.loading

    if (loading) {
        Box(
            Modifier.fillMaxWidth().padding(40.dp),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(color = AccentTeal)
        }
    } else {
        Column(modifier = Modifier.animateContentSize()) {
            // 2. Savings-rate hero
            SavingsRateHero(savingsRate)

            Spacer(Modifier.height(22.dp))

            // 3. Insights feed
            SectionLabel("Insights for you")
            Spacer(Modifier.height(12.dp))
            val cards = insights?.insights ?: emptyList()
            if (cards.isEmpty()) {
                CredCard {
                    Text(
                        "No insights right now",
                        color = TextSecondary,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            } else {
                cards.forEachIndexed { index, card ->
                    InsightRow(
                        card = card,
                        expanded = state.expandedInsight == index,
                        txns = state.insightTxns[index],
                        loading = state.insightLoading == index,
                        onClick = { vm.toggleInsight(index, card.query) },
                    )
                    Spacer(Modifier.height(12.dp))
                }
            }

            // 4. Subscriptions
            val subs = subscriptions
            if (subs != null && subs.subscriptions.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                SubscriptionsCard(subs)
            }

            // 5. Small leaks
            val lk = leaks
            if (lk != null && lk.leaks.isNotEmpty()) {
                Spacer(Modifier.height(22.dp))
                SmallLeaksCard(lk)
            }

            // 6. Goals
            Spacer(Modifier.height(22.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                SectionLabel("Goals")
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(SurfaceMid)
                        .clickable { onShowAddGoal(true) }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.Add, "Add goal", tint = AccentTeal,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "Add", color = AccentTeal,
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            val goalList = goals?.goals ?: emptyList()
            if (goalList.isEmpty()) {
                CredCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.Flag, null, tint = AccentGreen,
                            modifier = Modifier.size(22.dp),
                        )
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(
                                "Set your first goal",
                                color = TextPrimary,
                                fontWeight = FontWeight.SemiBold,
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                "Set a goal like ₹50L in 3 years",
                                color = TextSecondary,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            } else {
                goalList.forEach { goal ->
                    GoalCard(
                        goal = goal,
                        onDelete = { vm.deleteGoal(goal.id) },
                    )
                    Spacer(Modifier.height(12.dp))
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = TextSecondary,
        letterSpacing = 1.4.sp,
    )
}

@Composable
private fun SavingsRateHero(data: SavingsRateResponse?) {
    val series = data?.series ?: emptyList()
    val rated = series.filter { it.rate != null }
    val avg = data?.avgRate
    val latest = rated.lastOrNull()
    CredCard {
        // Headline: the average rate, stated plainly.
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = if (avg != null) "${"%.0f".format(avg)}%" else "—",
                color = if ((avg ?: 0.0) >= 0) AccentGreen else DebitRose,
                style = androidx.compose.ui.text.TextStyle(
                    fontFamily = DisplayFamily,
                    fontSize = 48.sp,
                    fontWeight = FontWeight.Bold,
                ),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = "avg savings rate",
                style = MaterialTheme.typography.labelMedium,
                color = TextSecondary,
                modifier = Modifier.padding(bottom = 12.dp),
            )
        }
        // Plain-English meaning — the intuitive explainer.
        if (avg != null) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = "You keep about ₹${"%.0f".format(avg)} of every ₹100 you earn.",
                style = MaterialTheme.typography.bodyMedium,
                color = TextPrimary,
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Savings rate = (income − spend) ÷ income",
            style = MaterialTheme.typography.labelSmall,
            color = TextMuted,
        )

        // Concrete latest month: earned / spent / saved.
        latest?.let { m ->
            Spacer(Modifier.height(14.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                SavingsStat("Earned", m.income, CreditGreen)
                SavingsStat("Spent", m.spend, DebitRose)
                SavingsStat("Saved", m.saved, if (m.saved >= 0) AccentGreen else DebitRose)
            }
            Text(
                text = "${formatRateMonth(m.month)} · saved ${m.rate?.let { "${"%.0f".format(it)}%" } ?: "—"} of income",
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted,
                modifier = Modifier.padding(top = 6.dp),
            )
        }

        // Per-month bars: month name + the actual % on each bar; green for
        // positive savings, rose for a deficit month (spent more than earned).
        if (rated.isNotEmpty()) {
            Spacer(Modifier.height(18.dp))
            Text(
                text = "MONTHLY SAVINGS RATE",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary,
                letterSpacing = 1.4.sp,
            )
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth().height(108.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                rated.forEach { point ->
                    val rate = point.rate ?: 0.0
                    val positive = rate >= 0
                    val barColor = if (positive) AccentGreen else DebitRose
                    // Bars share a scale so heights are comparable; deficits show a
                    // small rose stub with a clear negative label.
                    val frac = (rate.coerceIn(0.0, 100.0) / 100.0).toFloat()
                    Column(
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Bottom,
                    ) {
                        Text(
                            text = "${"%.0f".format(rate)}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = barColor,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(3.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .fillMaxHeight(if (positive) frac.coerceAtLeast(0.04f) else 0.04f)
                                .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                                .background(barColor.copy(alpha = if (positive) 0.85f else 1f)),
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = formatRateMonth(point.month),
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMuted,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RowScope.SavingsStat(label: String, amount: Double, color: Color) {
    Column(modifier = Modifier.weight(1f)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = TextMuted)
        Spacer(Modifier.height(2.dp))
        MoneyText(amount = amount, fontSize = 15.sp, color = color, showPaise = false)
    }
}

/** "2026-05" -> "May". Falls back to the last 2 chars on parse failure. */
private fun formatRateMonth(month: String): String = runCatching {
    java.time.YearMonth.parse(month).month
        .getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.getDefault())
}.getOrElse { month.takeLast(2) }

@Composable
private fun InsightRow(
    card: InsightCard,
    expanded: Boolean,
    txns: List<TransactionDto>?,
    loading: Boolean,
    onClick: () -> Unit,
) {
    val dotColor = when (card.severity) {
        "warning" -> DebitRose
        "positive" -> AccentGreen
        else -> AccentTeal
    }
    val canExpand = card.query != null
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        label = "chevron",
    )
    CredCard(
        modifier = if (canExpand) Modifier.clickable { onClick() } else Modifier,
    ) {
        Column(modifier = Modifier.animateContentSize()) {
            Row(verticalAlignment = Alignment.Top) {
                Box(
                    modifier = Modifier
                        .padding(top = 6.dp)
                        .size(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(dotColor),
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = card.title,
                        color = TextPrimary,
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = card.detail,
                        color = TextSecondary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (canExpand) {
                    Icon(
                        imageVector = Icons.Filled.ExpandMore,
                        contentDescription = if (expanded) "Collapse" else "Expand",
                        tint = TextMuted,
                        modifier = Modifier.rotate(chevronRotation),
                    )
                }
            }

            if (expanded) {
                Spacer(Modifier.height(12.dp))
                when {
                    loading -> Box(
                        Modifier.fillMaxWidth().padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) { CircularProgressIndicator(color = AccentTeal, modifier = Modifier.size(22.dp)) }

                    txns.isNullOrEmpty() -> Text(
                        "No transactions found.",
                        color = TextMuted,
                        style = MaterialTheme.typography.bodySmall,
                    )

                    else -> InsightTxnList(txns)
                }
            }
        }
    }
}

/** Datewise list of the transactions behind an insight: a date header per day,
 *  then each transaction's merchant + amount (newest first). */
@Composable
private fun InsightTxnList(txns: List<TransactionDto>) {
    val byDate: Map<String, List<TransactionDto>> = txns.groupBy { it.occurredAt.take(10) }
    Column {
        byDate.forEach { (date, dayTxns) ->
            Text(
                text = formatInsightDate(date),
                color = AccentTeal,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
            )
            dayTxns.forEach { t ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = t.merchant,
                        color = TextPrimary,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(12.dp))
                    MoneyText(amount = t.amount, fontSize = 13.sp, color = TextSecondary, showPaise = false)
                }
            }
        }
    }
}

/** "2026-06-03" -> "3 Jun 2026". Falls back to the raw string on parse failure. */
private fun formatInsightDate(iso: String): String = runCatching {
    val d = java.time.LocalDate.parse(iso)
    "${d.dayOfMonth} ${d.month.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.getDefault())} ${d.year}"
}.getOrElse { iso }

@Composable
private fun SubscriptionsCard(data: SubscriptionsResponse) {
    CredCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "Subscriptions",
                color = TextPrimary,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "${formatWhole(data.monthlyTotal)}/mo · ${data.count}",
                color = TextSecondary,
                style = MaterialTheme.typography.labelMedium,
            )
        }
        Spacer(Modifier.height(14.dp))
        data.subscriptions.forEachIndexed { i, sub ->
            if (i > 0) {
                Spacer(Modifier.height(14.dp))
            }
            SubscriptionRow(sub)
        }
    }
}

@Composable
private fun SubscriptionRow(sub: SubscriptionItem) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CategoryIconChip(sub.category)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = sub.merchant,
                color = TextPrimary,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${sub.count} charges · since ${sub.lastSeen}",
                color = TextSecondary,
                style = MaterialTheme.typography.labelMedium,
            )
        }
        Spacer(Modifier.width(8.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            MoneyText(amount = sub.monthlyAmount, fontSize = 16.sp, showPaise = false)
            Text(
                text = "/mo",
                color = TextMuted,
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

@Composable
private fun SmallLeaksCard(data: SmallLeaksResponse) {
    CredCard {
        Text(
            text = "Small leaks add up",
            color = TextPrimary,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = "Frequent small spends, annualized.",
            color = TextSecondary,
            style = MaterialTheme.typography.labelMedium,
        )
        Spacer(Modifier.height(14.dp))
        data.leaks.take(6).forEachIndexed { i, leak ->
            if (i > 0) {
                Spacer(Modifier.height(14.dp))
            }
            LeakRow(leak)
        }
    }
}

@Composable
private fun LeakRow(leak: LeakItem) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CategoryIconChip(leak.category)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = leak.merchant,
                color = TextPrimary,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${leak.count} charges · avg ${formatWhole(leak.avg)}",
                color = TextSecondary,
                style = MaterialTheme.typography.labelMedium,
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = "${formatWhole(leak.annualPace)}/yr",
            color = Amber,
            style = androidx.compose.ui.text.TextStyle(
                fontFamily = DisplayFamily,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
            ),
        )
    }
}

@Composable
private fun GoalCard(goal: GoalDto, onDelete: () -> Unit) {
    val pct = goal.progressPct.coerceIn(0.0, 100.0)
    CredCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = goal.name,
                    color = TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = formatWhole(goal.targetAmount) +
                        (goal.targetDate?.let { " · by $it" } ?: ""),
                    color = TextSecondary,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(SurfaceMid),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth((pct / 100.0).toFloat())
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(4.dp))
                    .background(AccentGreen),
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = "${"%.0f".format(pct)}% · ${formatWhole(goal.current)} of " +
                "${formatWhole(goal.targetAmount)} · ${formatWhole(goal.remaining)} to go",
            color = TextSecondary,
            style = MaterialTheme.typography.labelMedium,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = "Remove",
            style = MaterialTheme.typography.labelMedium,
            color = TextMuted,
            modifier = Modifier.clickable { onDelete() },
        )
    }
}

@Composable
private fun AddGoalDialog(
    onDismiss: () -> Unit,
    onSave: (String, Double, String?) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var target by remember { mutableStateOf("") }
    var date by remember { mutableStateOf("") }
    val targetVal = target.replace(",", "").toDoubleOrNull()
    val valid = name.isNotBlank() && targetVal != null

    AlertDialog(
        containerColor = SurfaceDark,
        onDismissRequest = onDismiss,
        title = { Text("Add goal", color = TextPrimary) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                GoalField(name, { name = it }, "Name (e.g. Emergency fund)", KeyboardType.Text)
                Spacer(Modifier.height(10.dp))
                GoalField(
                    target, { target = it.filter { c -> c.isDigit() || c == '.' } },
                    "Target amount (₹)", KeyboardType.Number,
                )
                Spacer(Modifier.height(10.dp))
                GoalField(
                    date, { date = it }, "Target date YYYY-MM-DD (optional)", KeyboardType.Number,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(name.trim(), targetVal ?: 0.0, date.ifBlank { null }) },
                enabled = valid,
            ) { Text("Add", color = if (valid) AccentTeal else TextMuted) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = TextSecondary) } },
    )
}

@Composable
private fun GoalField(value: String, onChange: (String) -> Unit, label: String, kb: KeyboardType) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = kb),
        modifier = Modifier.fillMaxWidth(),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = AccentTeal,
            focusedLabelColor = AccentTeal,
            cursorColor = AccentTeal,
            focusedTextColor = TextPrimary,
            unfocusedTextColor = TextPrimary,
        ),
    )
}
