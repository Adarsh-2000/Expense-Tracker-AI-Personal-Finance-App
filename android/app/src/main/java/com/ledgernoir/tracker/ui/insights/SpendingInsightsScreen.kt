package com.ledgernoir.tracker.ui.insights

import com.ledgernoir.tracker.ui.designsystem.*

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.SwapHoriz
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ledgernoir.tracker.data.repository.ExpenseRepository
import com.ledgernoir.tracker.data.api.FrequencyItem
import com.ledgernoir.tracker.data.api.HeatCell
import com.ledgernoir.tracker.data.api.RailGroup
import com.ledgernoir.tracker.data.api.RailsResponse
import com.ledgernoir.tracker.data.api.SpendingPatternsResponse
import com.ledgernoir.tracker.ui.theme.AccentTeal
import com.ledgernoir.tracker.ui.theme.CanvasBlack
import com.ledgernoir.tracker.ui.theme.SurfaceMid
import com.ledgernoir.tracker.ui.theme.TextMuted
import com.ledgernoir.tracker.ui.theme.TextPrimary
import com.ledgernoir.tracker.ui.theme.TextSecondary
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/** Human caption for a [start, endExclusive) ISO range. */
private fun payRangeCaption(startIso: String, endExclusiveIso: String): String {
    return runCatching {
        val start = LocalDate.parse(startIso)
        val lastDay = LocalDate.parse(endExclusiveIso).minusDays(1)
        val dFmt = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())
        val dyFmt = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault())
        "${start.format(dFmt)} – ${lastDay.format(dyFmt)}"
    }.getOrElse { "$startIso – $endExclusiveIso" }
}

/** Visual identity for a payment rail: tinted color + leading icon. */
private data class RailStyle(val color: Color, val icon: ImageVector)

private fun railStyleFor(rail: String): RailStyle = when (rail.lowercase().trim()) {
    "credit_card", "credit card", "creditcard" -> RailStyle(AccentTeal, Icons.Filled.CreditCard)
    "upi"                                       -> RailStyle(Color(0xFF34D399), Icons.Filled.Smartphone)
    "debit_card", "debit card", "debitcard"     -> RailStyle(Color(0xFF38BDF8), Icons.Filled.CreditCard)
    "bank_transfer", "bank transfer", "transfer" -> RailStyle(Color(0xFFA78BFA), Icons.Filled.SwapHoriz)
    else                                        -> RailStyle(Color(0xFF9CA3AF), Icons.Filled.AccountBalanceWallet)
}

private val SLOT_LABELS = listOf("Night", "Morn", "Aft", "Eve")
private val SLOT_LONG = listOf("nights", "mornings", "afternoons", "evenings")
private val DOW_SHORT = listOf("M", "T", "W", "T", "F", "S", "S")
private val DOW_LONG = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")

/**
 * HOW I PAY — premium payment-method + spending-rhythm analytics over a
 * selectable date range. Three lenses: the rail breakdown (expandable), a
 * 7×4 time-of-week heatmap, and a most-visited merchant frequency list.
 *
 * @param onOpenCategory kept for navigation parity; unused here.
 */
@Composable
fun SpendingInsightsScreen(
    repository: ExpenseRepository,
    onOpenCategory: (start: String, end: String, catId: Int, title: String) -> Unit = { _, _, _, _ -> },
) {
    when (com.ledgernoir.tracker.ui.designsystem.LocalFormFactor.current) {
        com.ledgernoir.tracker.ui.designsystem.FormFactor.Cover -> SpendingInsightsCover(
            repository, onOpenCategory,
        )
        else -> SpendingInsightsMain(
            repository, onOpenCategory,
        )
    }
}

@Composable
fun SpendingInsightsMain(
    repository: ExpenseRepository,
    onOpenCategory: (start: String, end: String, catId: Int, title: String) -> Unit = { _, _, _, _ -> },
) {
    val vm: SpendingInsightsViewModel = viewModel(
        factory = ledgerViewModelFactory {
            SpendingInsightsViewModel(it.useCases.getRails, it.useCases.getSpendingPatterns)
        },
    )
    val state by vm.uiState.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CanvasBlack)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 20.dp),
    ) {
        ResponsiveContainer {
            // 1) TITLE + subtitle.
            Text(
                text = "How I Pay",
                style = MaterialTheme.typography.headlineMedium,
                color = TextPrimary,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Where, when & how often you spend",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
            )
            Spacer(Modifier.height(18.dp))

            SpendingInsightsBody(state = state, vm = vm)
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun SpendingInsightsCover(
    repository: ExpenseRepository,
    onOpenCategory: (start: String, end: String, catId: Int, title: String) -> Unit = { _, _, _, _ -> },
) {
    val vm: SpendingInsightsViewModel = viewModel(
        factory = ledgerViewModelFactory {
            SpendingInsightsViewModel(it.useCases.getRails, it.useCases.getSpendingPatterns)
        },
    )
    val state by vm.uiState.collectAsStateWithLifecycle()
    val total = state.rails?.total ?: 0.0

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CanvasBlack)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 20.dp),
    ) {
        ResponsiveContainer {
            // Compact header with hero number for Cover form factor.
            HeroNumber(
                label = "Total Spent",
                amount = total,
                change = null,
            )
            Spacer(Modifier.height(18.dp))

            SpendingInsightsBody(state = state, vm = vm)
            Spacer(Modifier.height(8.dp))
        }
    }
}

/**
 * Shared body composable used by both [SpendingInsightsMain] and [SpendingInsightsCover].
 * Contains the date-range preset chips, range caption, and the three content lenses
 * (payment methods, spending rhythm, most visited).
 */
@Composable
private fun SpendingInsightsBody(
    state: SpendingInsightsUiState,
    vm: SpendingInsightsViewModel,
) {
    val preset = state.preset
    val startIso = state.startIso
    val endIso = state.endIso
    val rails = state.rails
    val patterns = state.patterns
    val loading = state.loading
    val expandedRails = state.expandedRails

    val total = rails?.total ?: 0.0

    // DATE-RANGE preset chips.
    PayRangeChips(
        selected = preset,
        onSelect = { p -> vm.selectPreset(p) },
    )
    Spacer(Modifier.height(8.dp))
    Text(
        text = payRangeCaption(startIso, endIso),
        style = MaterialTheme.typography.labelMedium,
        color = TextMuted,
    )
    Spacer(Modifier.height(20.dp))

    if (loading) {
        Box(
            Modifier.fillMaxWidth().padding(40.dp),
            contentAlignment = Alignment.Center,
        ) { CircularProgressIndicator(color = AccentTeal) }
    } else if (total <= 0.0) {
        PayEmptyCard("No spending in this range yet.")
    } else {
        // PAYMENT-METHOD LENS.
        PaymentMethodCard(
            rails = rails?.rails.orEmpty(),
            total = total,
            expanded = expandedRails,
            onToggle = { railKey -> vm.toggleRail(railKey) },
        )
        Spacer(Modifier.height(20.dp))

        // SPENDING RHYTHM heatmap.
        SpendingRhythmCard(heatmap = patterns?.heatmap.orEmpty())
        Spacer(Modifier.height(20.dp))

        // FREQUENCY — most visited merchants.
        MostVisitedCard(frequency = patterns?.frequency.orEmpty())
    }
}

/** Uppercase eyebrow label. */
@Composable
private fun PayEyebrow(text: String, color: Color = TextSecondary) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = color,
        letterSpacing = 1.4.sp,
    )
}

// ── Date-range chips ─────────────────────────────────────────────────────────

@Composable
private fun PayRangeChips(selected: PayRangePreset, onSelect: (PayRangePreset) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PayRangePreset.entries.forEach { p ->
            PayChipPill(
                label = p.label,
                active = selected == p,
                onClick = { onSelect(p) },
            )
        }
    }
}

@Composable
private fun PayChipPill(label: String, active: Boolean, onClick: () -> Unit) {
    val bg = if (active) AccentTeal.copy(alpha = 0.18f) else SurfaceMid
    val fg = if (active) AccentTeal else TextSecondary
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = fg,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            softWrap = false,
        )
    }
}

// ── Payment-method lens ────────────────────────────────────────────────────────

@Composable
private fun PaymentMethodCard(
    rails: List<RailGroup>,
    total: Double,
    expanded: Set<String>,
    onToggle: (String) -> Unit,
) {
    val sorted = rails.sortedByDescending { it.total }
    CredCard {
        PayEyebrow("Payment Methods")
        Spacer(Modifier.height(16.dp))
        sorted.forEachIndexed { i, rail ->
            RailRow(
                rail = rail,
                total = total,
                isExpanded = expanded.contains(rail.rail),
                onToggle = { onToggle(rail.rail) },
            )
            if (i < sorted.size - 1) {
                Spacer(Modifier.height(18.dp))
            }
        }
    }
}

@Composable
private fun RailRow(
    rail: RailGroup,
    total: Double,
    isExpanded: Boolean,
    onToggle: () -> Unit,
) {
    val style = railStyleFor(rail.rail)
    val pct = if (total > 0.0) (rail.total / total * 100.0) else 0.0
    val chevronRotation by animateFloatAsState(
        targetValue = if (isExpanded) 180f else 0f,
        animationSpec = tween(250),
        label = "railChevron",
    )

    Column(modifier = Modifier.animateContentSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable(onClick = onToggle)
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Tinted leading chip.
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(style.color.copy(alpha = 0.15f), RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = style.icon,
                    contentDescription = rail.label,
                    tint = style.color,
                    modifier = Modifier.size(22.dp),
                )
            }
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
                        text = rail.label,
                        style = MaterialTheme.typography.bodyLarge,
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(horizontalAlignment = Alignment.End) {
                        MoneyText(amount = rail.total, fontSize = 17.sp, color = TextPrimary, showPaise = false)
                        Text(
                            text = "${rail.count} txn${if (rail.count == 1) "" else "s"}",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMuted,
                        )
                    }
                }
                PayMiniBar(fraction = (pct / 100.0).toFloat(), color = style.color, height = 6)
            }
            Spacer(Modifier.width(10.dp))
            Icon(
                imageVector = Icons.Filled.ExpandMore,
                contentDescription = if (isExpanded) "Collapse" else "Expand",
                tint = TextSecondary,
                modifier = Modifier.size(22.dp).rotate(chevronRotation),
            )
        }

        if (isExpanded) {
            Spacer(Modifier.height(14.dp))
            if (rail.items.isEmpty()) {
                Text(
                    text = "No detail available.",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted,
                    modifier = Modifier.padding(start = 58.dp),
                )
            } else {
                rail.items.sortedByDescending { it.total }.forEachIndexed { i, item ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 58.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f, fill = false)) {
                            Text(
                                text = item.name,
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = "${item.count} txn${if (item.count == 1) "" else "s"}",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextMuted,
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        MoneyText(amount = item.total, fontSize = 14.sp, color = TextSecondary, showPaise = false)
                    }
                    if (i < rail.items.size - 1) Spacer(Modifier.height(12.dp))
                }
            }
        }
    }
}

@Composable
private fun PayMiniBar(fraction: Float, color: Color, height: Int) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(height.dp)
            .background(SurfaceMid, RoundedCornerShape((height / 2).dp)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(height.dp)
                .background(color, RoundedCornerShape((height / 2).dp)),
        )
    }
}

// ── Spending rhythm heatmap (7 rows × 4 slots) ──────────────────────────────────

@Composable
private fun SpendingRhythmCard(heatmap: List<HeatCell>) {
    // Index cells by (dow, slot) for O(1) lookup; aggregate defensively.
    val byKey = HashMap<Int, HeatCell>()
    heatmap.forEach { cell ->
        if (cell.dow in 0..6 && cell.slot in 0..3) {
            val key = cell.dow * 4 + cell.slot
            val existing = byKey[key]
            byKey[key] = if (existing == null) {
                cell
            } else {
                existing.copy(total = existing.total + cell.total, count = existing.count + cell.count)
            }
        }
    }
    val maxTotal = byKey.values.maxOfOrNull { it.total }?.takeIf { it > 0.0 } ?: 1.0
    val peak = byKey.values.maxByOrNull { it.total }

    CredCard {
        PayEyebrow("Spending Rhythm")
        Spacer(Modifier.height(16.dp))

        // Column header labels (offset by the row-label gutter).
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.width(24.dp))
            SLOT_LABELS.forEach { label ->
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Spacer(Modifier.height(8.dp))

        // Seven rows (Mon..Sun) × four slot cells.
        for (dow in 0..6) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(modifier = Modifier.width(24.dp)) {
                    Text(
                        text = DOW_SHORT[dow],
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted,
                    )
                }
                for (slot in 0..3) {
                    val cell = byKey[dow * 4 + slot]
                    val intensity = if (cell != null) (cell.total / maxTotal).toFloat() else 0f
                    // Empty cells very faint; populated cells scale teal alpha.
                    val cellColor = if (intensity <= 0f) {
                        SurfaceMid.copy(alpha = 0.4f)
                    } else {
                        AccentTeal.copy(alpha = 0.12f + 0.78f * intensity.coerceIn(0f, 1f))
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 3.dp)
                            .height(26.dp)
                            .background(cellColor, RoundedCornerShape(7.dp)),
                    )
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        val insight = if (peak != null && peak.total > 0.0) {
            "You spend most on ${DOW_LONG[peak.dow]} ${SLOT_LONG[peak.slot]}."
        } else {
            "Not enough data to spot a pattern yet."
        }
        Text(
            text = insight,
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
        )
    }
}

// ── Most-visited frequency list ─────────────────────────────────────────────────

@Composable
private fun MostVisitedCard(frequency: List<FrequencyItem>) {
    val top = frequency.sortedByDescending { it.count }.take(8)
    CredCard {
        PayEyebrow("Most Visited")
        Spacer(Modifier.height(16.dp))
        if (top.isEmpty()) {
            Text(
                text = "No repeat merchants in this range.",
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted,
            )
        } else {
            top.forEachIndexed { i, item ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .background(SurfaceMid, RoundedCornerShape(9.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "${i + 1}",
                            style = MaterialTheme.typography.labelMedium,
                            color = TextSecondary,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = item.merchant,
                            style = MaterialTheme.typography.bodyLarge,
                            color = TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "${item.count} visit${if (item.count == 1) "" else "s"} · avg ${formatWhole(item.avg)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMuted,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    MoneyText(amount = item.total, fontSize = 16.sp, color = TextPrimary, showPaise = false)
                }
                if (i < top.size - 1) Spacer(Modifier.height(16.dp))
            }
        }
    }
}

// ── Empty state ────────────────────────────────────────────────────────────────

@Composable
private fun PayEmptyCard(message: String) {
    CredCard {
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .background(AccentTeal.copy(alpha = 0.14f), RoundedCornerShape(16.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Insights,
                    contentDescription = null,
                    tint = AccentTeal,
                    modifier = Modifier.size(28.dp),
                )
            }
            Spacer(Modifier.height(14.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
                textAlign = TextAlign.Center,
            )
        }
    }
}
