package com.ledgernoir.tracker.ui.portfolio

import com.ledgernoir.tracker.ui.designsystem.*

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.foundation.Canvas
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ledgernoir.tracker.data.repository.ExpenseRepository
import com.ledgernoir.tracker.data.api.HoldingDto
import com.ledgernoir.tracker.data.api.PortfolioResponse
import com.ledgernoir.tracker.ui.theme.AccentGreen
import com.ledgernoir.tracker.ui.theme.AccentTeal
import com.ledgernoir.tracker.ui.theme.BorderGray
import com.ledgernoir.tracker.ui.theme.CanvasBlack
import com.ledgernoir.tracker.ui.theme.CreditGreen
import com.ledgernoir.tracker.ui.theme.DebitRose
import com.ledgernoir.tracker.ui.theme.DisplayFamily
import com.ledgernoir.tracker.ui.theme.SurfaceDark
import com.ledgernoir.tracker.ui.theme.TextMuted
import com.ledgernoir.tracker.ui.theme.TextPrimary
import com.ledgernoir.tracker.ui.theme.TextSecondary

private val DONUT_PALETTE = listOf(
    Color(0xFF2DD4BF), // teal
    Color(0xFF34D399), // green
    Color(0xFF60A5FA), // blue
    Color(0xFFA78BFA), // violet
    Color(0xFFFBBF24), // amber
    Color(0xFFF472B6), // pink
    Color(0xFF6B7280), // muted (Others)
)

/**
 * Portfolio screen (CRED/Groww-inspired, dark). On first load, fetches the
 * portfolio; if empty, auto-syncs once and re-fetches so the first visit
 * populates. Shows a hero value card, an allocation donut, a type filter,
 * and a sorted holdings list with a "Sync now" action.
 */
@Composable
fun PortfolioScreen(repository: ExpenseRepository) {
    when (com.ledgernoir.tracker.ui.designsystem.LocalFormFactor.current) {
        com.ledgernoir.tracker.ui.designsystem.FormFactor.Cover -> PortfolioCover(repository)
        else -> PortfolioMain(repository)
    }
}

@Composable
private fun PortfolioMain(repository: ExpenseRepository) {
    val vm: PortfolioViewModel = viewModel(
        factory = ledgerViewModelFactory {
            PortfolioViewModel(it.useCases.getPortfolio, it.useCases.syncPortfolio)
        },
    )
    val state by vm.uiState.collectAsStateWithLifecycle()
    val portfolio = state.portfolio
    val loading = state.loading
    val syncing = state.syncing
    val statusLine = state.statusLine
    val filter = state.filter

    val haptic = LocalHapticFeedback.current

    fun triggerSync() {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        vm.sync()
    }

    val data = portfolio

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CanvasBlack)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 20.dp),
    ) {
        ResponsiveContainer {
            // Header with sync action.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Investments",
                        style = MaterialTheme.typography.headlineMedium,
                        color = TextPrimary,
                        fontFamily = DisplayFamily,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Your holdings, in one place",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary,
                    )
                }
                SyncChip(syncing = syncing, onClick = { if (!syncing) triggerSync() })
            }

            Spacer(Modifier.height(20.dp))

            if (loading) {
                Box(
                    Modifier.fillMaxWidth().padding(40.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(color = AccentTeal)
                }
            } else if (data == null || (data.holdings.isEmpty() && data.usStocks == null)) {
                PortfolioEmptyState(syncing = syncing, onSync = { if (!syncing) triggerSync() })
            } else {
                PortfolioContent(
                    data = data,
                    filter = filter,
                    onFilterChange = { vm.setFilter(it) },
                )
            }

            if (statusLine != null) {
                Spacer(Modifier.height(14.dp))
                Text(
                    text = statusLine!!,
                    style = MaterialTheme.typography.labelMedium,
                    color = AccentTeal,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun PortfolioCover(repository: ExpenseRepository) {
    val vm: PortfolioViewModel = viewModel(
        factory = ledgerViewModelFactory {
            PortfolioViewModel(it.useCases.getPortfolio, it.useCases.syncPortfolio)
        },
    )
    val state by vm.uiState.collectAsStateWithLifecycle()
    val data = state.portfolio
    val loading = state.loading

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CanvasBlack)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        ResponsiveContainer {
        if (loading) {
            Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = AccentTeal)
            }
        } else if (data == null || (data.holdings.isEmpty() && data.usStocks == null)) {
            PortfolioEmptyState(syncing = state.syncing, onSync = { if (!state.syncing) vm.sync() })
        } else {
            val total = if (data.grandTotalValue > 0) data.grandTotalValue else data.totalValue

            HeroNumber(
                label = "Portfolio value",
                amount = total,
                change = data.usStocks?.returnsPct?.let {
                    (if (it >= 0) "▲ +" else "▼ ") + "%.2f%%".format(it)
                },
                changePositive = (data.usStocks?.returnsPct ?: 0.0) >= 0,
            )
            Spacer(Modifier.height(16.dp))

            // Allocation split by holding type (Stocks / Funds / Others), matching
            // the Main "Allocation" section's data source (data.holdings).
            val stocksAmt = data.holdings.filter { it.type == "stock" }.sumOf { it.value }
            val fundsAmt = data.holdings.filter { it.type == "mutual_fund" }.sumOf { it.value }
            val othersAmt = data.holdings.filter {
                it.type != "stock" && it.type != "mutual_fund"
            }.sumOf { it.value }
            val allocTotal = (stocksAmt + fundsAmt + othersAmt).takeIf { it > 0.0 } ?: 1.0
            val stocksFraction = (stocksAmt / allocTotal).toFloat()
            val fundsFraction = (fundsAmt / allocTotal).toFloat()
            val othersFraction = (othersAmt / allocTotal).toFloat()

            val amberFunds = Color(0xFFFBBF24)
            if (stocksAmt + fundsAmt + othersAmt > 0.0) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AllocationRing(
                        slices = listOf(
                            RingSlice(stocksFraction, AccentTeal),
                            RingSlice(fundsFraction, amberFunds),
                            RingSlice(othersFraction, DebitRose),
                        ),
                        center = {
                            Text(
                                text = formatMoney(allocTotal),
                                style = MaterialTheme.typography.labelSmall,
                                color = TextPrimary,
                                fontWeight = FontWeight.Bold,
                            )
                        },
                    )
                    Spacer(Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        CoverLegendRow("Stocks", stocksFraction, AccentTeal)
                        Spacer(Modifier.height(4.dp))
                        CoverLegendRow("Funds", fundsFraction, amberFunds)
                        Spacer(Modifier.height(4.dp))
                        CoverLegendRow("Others", othersFraction, DebitRose)
                    }
                }
                Spacer(Modifier.height(16.dp))
            }

            // Compact US stocks aggregate summary (reuses Main's card when present).
            data.usStocks?.let { us ->
                UsStocksCard(us)
            }
        }
        }
    }
}

@Composable
private fun CoverLegendRow(label: String, fraction: Float, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(9.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(color),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondary,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = "%.0f%%".format(fraction * 100f),
            style = MaterialTheme.typography.labelSmall,
            color = TextMuted,
        )
    }
}

@Composable
private fun PortfolioContent(
    data: PortfolioResponse,
    filter: HoldingFilter,
    onFilterChange: (HoldingFilter) -> Unit,
) {
    // Hero shows the GRAND total (Indian holdings + US INR-converted) so it
    // reflects the whole portfolio. Falls back to total_value if grand is 0.
    val heroTarget = if (data.grandTotalValue > 0) data.grandTotalValue else data.totalValue
    val animatedValue by animateFloatAsState(
        targetValue = heroTarget.toFloat(),
        animationSpec = tween(900),
        label = "value-countup",
    )

    HeroValueCard(
        value = animatedValue.toDouble(),
        asOf = data.asOf,
        count = data.count,
    )

    Spacer(Modifier.height(18.dp))

    // US stocks aggregate card (INDmoney) — shown when present.
    data.usStocks?.let { us ->
        UsStocksCard(us)
        Spacer(Modifier.height(18.dp))
    }

    // Allocation donut (top 6 + Others).
    val sorted = data.holdings.sortedByDescending { it.value }
    val slices = buildSlices(sorted)
    if (slices.isNotEmpty()) {
        AllocationCard(slices = slices)
        Spacer(Modifier.height(18.dp))
    }

    // Filter toggle.
    FilterToggle(filter = filter, onChange = onFilterChange)
    Spacer(Modifier.height(16.dp))

    val filtered = sorted.filter {
        when (filter) {
            HoldingFilter.ALL -> true
            HoldingFilter.STOCKS -> it.type == "stock"
            HoldingFilter.FUNDS -> it.type == "mutual_fund"
        }
    }

    val total = if (data.totalValue > 0) data.totalValue else 1.0
    filtered.forEachIndexed { i, h ->
        AnimatedVisibility(visible = true, enter = fadeIn(tween(300 + i * 40))) {
            Column {
                HoldingRow(holding = h, pctOfPortfolio = h.value / total * 100.0)
                if (i < filtered.size - 1) Spacer(Modifier.height(12.dp))
            }
        }
    }
}

private data class Slice(val label: String, val value: Double, val color: Color)

private fun buildSlices(sorted: List<HoldingDto>): List<Slice> {
    if (sorted.isEmpty()) return emptyList()
    val top = sorted.take(6)
    val rest = sorted.drop(6)
    val slices = top.mapIndexed { i, h ->
        Slice(h.name, h.value, DONUT_PALETTE[i % DONUT_PALETTE.size])
    }.toMutableList()
    if (rest.isNotEmpty()) {
        slices.add(Slice("Others", rest.sumOf { it.value }, DONUT_PALETTE.last()))
    }
    return slices
}

@Composable
private fun HeroValueCard(value: Double, asOf: String?, count: Int) {
    CredCard {
        Text(
            text = "PORTFOLIO VALUE",
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondary,
            letterSpacing = 1.4.sp,
        )
        Spacer(Modifier.height(10.dp))
        MoneyText(amount = value, fontSize = 36.sp, color = AccentGreen)
        Spacer(Modifier.height(8.dp))
        val subtitle = buildString {
            if (asOf != null) append("as of $asOf · ")
            append(if (count == 1) "1 holding" else "$count holdings")
        }
        Text(
            text = subtitle,
            style = MaterialTheme.typography.labelMedium,
            color = TextSecondary,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Value as of last statement — not live.",
            style = MaterialTheme.typography.labelSmall,
            color = TextMuted,
        )
    }
}

private fun fmtUsd(v: Double): String {
    // "$1,650" / "$1,650.50" — drop paise when whole.
    return if (v % 1.0 == 0.0) "$%,.0f".format(v) else "$%,.2f".format(v)
}

@Composable
private fun UsStocksCard(us: com.ledgernoir.tracker.data.api.UsStocksDto) {
    CredCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CategoryIconChip(category = "transfers", size = 38.dp)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "US STOCKS",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary,
                    letterSpacing = 1.4.sp,
                )
                Text(
                    text = "🇺🇸 ${us.broker ?: "INDmoney"}" +
                        (us.accountLast4?.let { " · ••$it" } ?: ""),
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            us.returnsPct?.let { pct ->
                val up = pct >= 0
                Text(
                    text = (if (up) "▲ +" else "▼ ") + "%.2f%%".format(pct),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (up) CreditGreen else DebitRose,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        // Primary value in INR (app currency), USD secondary.
        MoneyText(amount = us.currentInr, fontSize = 30.sp, color = AccentGreen)
        Spacer(Modifier.height(4.dp))
        Text(
            text = fmtUsd(us.currentUsd) + "  ·  current value",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
        )
        us.investedUsd?.let { inv ->
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Invested ${fmtUsd(inv)}" +
                    (us.investedInr?.let { " (≈ ${formatMoney(it)})" } ?: ""),
                style = MaterialTheme.typography.labelMedium,
                color = TextSecondary,
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = (us.asOf?.let { "as of $it · " } ?: "") +
                "aggregate only — INDmoney doesn't email per-stock holdings",
            style = MaterialTheme.typography.labelSmall,
            color = TextMuted,
        )
    }
}

@Composable
private fun AllocationCard(slices: List<Slice>) {
    CredCard {
        Text(
            text = "ALLOCATION",
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondary,
            letterSpacing = 1.4.sp,
        )
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(132.dp),
                contentAlignment = Alignment.Center,
            ) {
                val sum = slices.sumOf { it.value }.takeIf { it > 0 } ?: 1.0
                Canvas(modifier = Modifier.size(132.dp)) {
                    val strokeWidth = 22.dp.toPx()
                    val diameter = size.minDimension - strokeWidth
                    val topLeft = Offset(
                        (size.width - diameter) / 2f,
                        (size.height - diameter) / 2f,
                    )
                    val arcSize = Size(diameter, diameter)
                    var startAngle = -90f
                    slices.forEach { slice ->
                        val sweep = (slice.value / sum * 360.0).toFloat()
                        drawArc(
                            color = slice.color,
                            startAngle = startAngle,
                            sweepAngle = sweep - 2f,
                            useCenter = false,
                            topLeft = topLeft,
                            size = arcSize,
                            style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
                        )
                        startAngle += sweep
                    }
                }
                Text(
                    text = "${slices.size}",
                    style = MaterialTheme.typography.titleLarge,
                    color = TextPrimary,
                    fontFamily = DisplayFamily,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.width(18.dp))
            // Legend.
            Column(modifier = Modifier.weight(1f)) {
                val sum = slices.sumOf { it.value }.takeIf { it > 0 } ?: 1.0
                slices.forEach { slice ->
                    Row(
                        modifier = Modifier.padding(vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(slice.color),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = slice.label,
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "%.0f%%".format(slice.value / sum * 100.0),
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
private fun FilterToggle(filter: HoldingFilter, onChange: (HoldingFilter) -> Unit) {
    val options = listOf(
        HoldingFilter.ALL to "All",
        HoldingFilter.STOCKS to "Stocks",
        HoldingFilter.FUNDS to "Funds",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(SurfaceDark)
            .border(1.dp, BorderGray, RoundedCornerShape(14.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEach { (value, label) ->
            val selected = filter == value
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (selected) AccentTeal.copy(alpha = 0.18f) else Color.Transparent)
                    .clickable { onChange(value) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (selected) AccentTeal else TextSecondary,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}

@Composable
private fun HoldingRow(holding: HoldingDto, pctOfPortfolio: Double) {
    val isStock = holding.type == "stock"
    val icon: ImageVector = if (isStock) Icons.Filled.TrendingUp else Icons.Filled.PieChart
    val tint = if (isStock) AccentTeal else AccentGreen

    CredCard(padding = 16) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(tint.copy(alpha = 0.15f), RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = holding.type,
                    tint = tint,
                    modifier = Modifier.size(22.dp),
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = holding.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "%,.2f units · ₹%,.2f/unit".format(holding.quantity, holding.rate),
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(horizontalAlignment = Alignment.End) {
                MoneyText(amount = holding.value, fontSize = 16.sp, color = TextPrimary)
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "%.1f%%".format(pctOfPortfolio),
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted,
                )
            }
        }
    }
}

@Composable
private fun SyncChip(syncing: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(AccentTeal.copy(alpha = 0.15f))
            .clickable(enabled = !syncing) { onClick() }
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (syncing) {
            CircularProgressIndicator(
                color = AccentTeal,
                strokeWidth = 2.dp,
                modifier = Modifier.size(16.dp),
            )
        } else {
            Icon(
                imageVector = Icons.Filled.Sync,
                contentDescription = "Sync",
                tint = AccentTeal,
                modifier = Modifier.size(16.dp),
            )
        }
        Spacer(Modifier.width(6.dp))
        Text(
            text = if (syncing) "Syncing" else "Sync now",
            style = MaterialTheme.typography.labelMedium,
            color = AccentTeal,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun PortfolioEmptyState(syncing: Boolean, onSync: () -> Unit) {
    CredCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .background(AccentTeal.copy(alpha = 0.15f), RoundedCornerShape(18.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.TrendingUp,
                    contentDescription = "No holdings",
                    tint = AccentTeal,
                    modifier = Modifier.size(28.dp),
                )
            }
            Spacer(Modifier.height(14.dp))
            Text(
                text = "No holdings yet",
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Set your PAN in profile if you haven't, then sync your investments.",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(18.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (syncing) AccentTeal.copy(alpha = 0.3f) else AccentTeal)
                    .clickable(enabled = !syncing) { onSync() }
                    .padding(horizontal = 28.dp, vertical = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (syncing) {
                    CircularProgressIndicator(
                        color = CanvasBlack,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(18.dp),
                    )
                } else {
                    Text(
                        text = "Sync investments",
                        color = CanvasBlack,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}
