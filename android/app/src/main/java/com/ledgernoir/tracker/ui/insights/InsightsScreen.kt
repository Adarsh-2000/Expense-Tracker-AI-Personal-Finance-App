package com.ledgernoir.tracker.ui.insights

import com.ledgernoir.tracker.ui.designsystem.*

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ledgernoir.tracker.ui.common.ledgerViewModelFactory
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ledgernoir.tracker.data.api.CategoryBreakdownDto
import com.ledgernoir.tracker.data.repository.ExpenseRepository
import androidx.compose.ui.graphics.Color
import com.ledgernoir.tracker.ui.theme.AccentGreen
import com.ledgernoir.tracker.ui.theme.AccentTeal
import com.ledgernoir.tracker.ui.theme.CanvasBlack
import com.ledgernoir.tracker.ui.theme.SurfaceMid
import com.ledgernoir.tracker.ui.theme.TextMuted
import com.ledgernoir.tracker.ui.theme.TextPrimary
import com.ledgernoir.tracker.ui.theme.TextSecondary
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/** Human caption for a [start, endExclusive) ISO range, e.g. "1 Mar – 31 May 2026". */
private fun rangeCaption(startIso: String, endExclusiveIso: String): String {
    return runCatching {
        val start = LocalDate.parse(startIso)
        val lastDay = LocalDate.parse(endExclusiveIso).minusDays(1)
        val dFmt = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())
        val dyFmt = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault())
        "${start.format(dFmt)} – ${lastDay.format(dyFmt)}"
    }.getOrElse { "$startIso – $endExclusiveIso" }
}

/**
 * INSIGHTS — premium category→sub-category spend analytics over a selectable date
 * range. Debit-only totals from reports/breakdown. Category multi-select filter,
 * allocation donut, and an expandable hierarchy with drill-down into the existing
 * transaction list per category.
 *
 * @param onOpenCategory (start, endExclusive, categoryId, title) — matches the
 *        Dashboard's category drill-down route exactly.
 */
@Composable
fun InsightsScreen(
    repository: ExpenseRepository,
    onOpenCategory: (start: String, end: String, catId: Int, title: String) -> Unit = { _, _, _, _ -> },
    onOpenSpending: () -> Unit = {},
    onOpenSaveMore: () -> Unit = {},
    onOpenBudget: () -> Unit = {},
    onOpenMoneyRadar: () -> Unit = {},
    onOpenAiInsights: () -> Unit = {},
    onOpenRewards: () -> Unit = {},
) {
    when (com.ledgernoir.tracker.ui.designsystem.LocalFormFactor.current) {
        com.ledgernoir.tracker.ui.designsystem.FormFactor.Cover -> InsightsCover(
            repository, onOpenCategory, onOpenSpending, onOpenSaveMore, onOpenBudget,
            onOpenMoneyRadar, onOpenAiInsights, onOpenRewards,
        )
        else -> InsightsMain(
            repository, onOpenCategory, onOpenSpending, onOpenSaveMore, onOpenBudget,
            onOpenMoneyRadar, onOpenAiInsights, onOpenRewards,
        )
    }
}

@Composable
fun InsightsMain(
    repository: ExpenseRepository,
    onOpenCategory: (start: String, end: String, catId: Int, title: String) -> Unit = { _, _, _, _ -> },
    onOpenSpending: () -> Unit = {},
    onOpenSaveMore: () -> Unit = {},
    onOpenBudget: () -> Unit = {},
    onOpenMoneyRadar: () -> Unit = {},
    onOpenAiInsights: () -> Unit = {},
    onOpenRewards: () -> Unit = {},
) {
    val vm: InsightsViewModel = viewModel(
        factory = ledgerViewModelFactory {
            InsightsViewModel(it.useCases.getCategories, it.useCases.getBreakdown,
                it.useCases.getRadarSummary)
        },
    )
    val state by vm.uiState.collectAsStateWithLifecycle()

    val preset = state.preset
    val startIso = state.startIso
    val endIso = state.endIso
    val breakdown = state.breakdown
    val loading = state.loading
    val categoryIdByName = state.categoryIdByName
    val selected = state.selectedCategory
    val expanded = state.expanded

    // Material3 date-range picker dialog for the "Custom" preset.
    if (state.showCustomPicker) {
        InsightsRangePickerDialog(
            initialStartIso = startIso,
            initialEndExclusiveIso = endIso,
            onDismiss = { vm.dismissCustomPicker() },
            onConfirm = { newStart, newEndExclusive ->
                vm.applyCustomRange(newStart, newEndExclusive)
            },
        )
    }

    // Filtered categories (client-side) sorted by total desc.
    val allCategories = breakdown?.categories.orEmpty()
    val filteredCategories = if (selected == null) {
        allCategories
    } else {
        allCategories.filter { it.category.equals(selected, ignoreCase = true) }
    }.sortedByDescending { it.total }
    val filteredTotal = filteredCategories.sumOf { it.total }

    val listState = rememberLazyListState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CanvasBlack),
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = 16.dp,
                vertical = 20.dp,
            ),
        ) {
            // 1) HEADER
            item {
                ResponsiveContainer {
                    Text(
                        text = "Insights",
                        style = MaterialTheme.typography.headlineMedium,
                        color = TextPrimary,
                    )
                    Spacer(Modifier.height(16.dp))

                    // AI Insights — full-width hub entry (LLM monthly summaries).
                    AiInsightsHubCard(onClick = onOpenAiInsights)
                    Spacer(Modifier.height(12.dp))

                    // Entry tiles → deeper analytics screens.
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        InsightsNavTile("How I Pay", "Cards · UPI · when",
                            AccentTeal, Modifier.weight(1f), onOpenSpending)
                        InsightsNavTile("Save More", "Subs · leaks · goals",
                            AccentGreen, Modifier.weight(1f), onOpenSaveMore)
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        InsightsNavTile("Budgets", "Caps · overspend alerts",
                            Color(0xFFFBBF24), Modifier.weight(1f), onOpenBudget)
                        InsightsNavTile("Rewards", "Points · redeem · expiry",
                            AccentGreen, Modifier.weight(1f), onOpenRewards)
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        InsightsNavTile("Money Radar", "Mandates · forex · fees",
                            Color(0xFFEF4444), Modifier.weight(1f), onOpenMoneyRadar,
                            badgeCount = state.radarNewCount)
                    }
                    Spacer(Modifier.height(18.dp))

                    // 2) DATE-RANGE FILTER chips + resolved caption.
                    RangeChips(
                        selected = preset,
                        onSelect = { p -> vm.selectPreset(p) },
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = rangeCaption(startIso, endIso),
                        style = MaterialTheme.typography.labelMedium,
                        color = TextMuted,
                    )
                    Spacer(Modifier.height(20.dp))
                }
            }

            if (loading) {
                item {
                    Box(
                        Modifier.fillMaxWidth().padding(40.dp),
                        contentAlignment = Alignment.Center,
                    ) { CircularProgressIndicator(color = AccentTeal) }
                }
            } else if (filteredTotal <= 0.0) {
                item {
                    ResponsiveContainer {
                        InsightsEmptyCard("No spending in this range yet.")
                    }
                }
            } else {
                // 3) HERO + 4) DONUT + 5) CATEGORY FILTER (single ResponsiveContainer block).
                item {
                    ResponsiveContainer {
                        TotalSpentHero(total = filteredTotal)
                        Spacer(Modifier.height(20.dp))

                        CredCard {
                            InsightsEyebrow("Allocation")
                            Spacer(Modifier.height(8.dp))
                            CategoryDonut(
                                summary = allCategories.map {
                                    com.ledgernoir.tracker.data.api.SummaryItem(
                                        category = it.category,
                                        total = it.total,
                                    )
                                },
                                selectedCategory = selected,
                            )
                        }
                        Spacer(Modifier.height(20.dp))

                        InsightsCategoryChips(
                            categories = allCategories,
                            selected = selected,
                            onSelect = { name ->
                                vm.selectCategory(name)
                            },
                        )
                        Spacer(Modifier.height(20.dp))
                    }
                }

                // 6) HIERARCHY LIST — category rows with expandable sub-rows.
                itemsIndexed(
                    items = filteredCategories,
                    key = { _, c -> c.category },
                ) { _, cat ->
                    ResponsiveContainer {
                        CategoryBreakdownCard(
                            category = cat,
                            total = filteredTotal,
                            isExpanded = expanded.contains(cat.category),
                            onToggleExpand = { vm.toggleExpanded(cat.category) },
                            onDrillDown = {
                                val catId = categoryIdByName[cat.category.lowercase().trim()]
                                if (catId != null) {
                                    onOpenCategory(
                                        startIso,
                                        endIso,
                                        catId,
                                        cat.category.replaceFirstChar { it.uppercase() },
                                    )
                                }
                            },
                        )
                        Spacer(Modifier.height(12.dp))
                    }
                }
            }

            item { Spacer(Modifier.height(8.dp)) }
        }
    }
}

/**
 * Compact cover-display Insights: total-spent hero + single-column list of hub
 * entry rows (icon + title + chevron). Shares the SAME [InsightsViewModel] instance
 * as [InsightsMain] (same nav destination → same ViewModelStoreOwner + key), so
 * state survives the fold-driven swap.
 */
@Composable
private fun InsightsCover(
    repository: ExpenseRepository,
    onOpenCategory: (start: String, end: String, catId: Int, title: String) -> Unit,
    onOpenSpending: () -> Unit,
    onOpenSaveMore: () -> Unit,
    onOpenBudget: () -> Unit,
    onOpenMoneyRadar: () -> Unit,
    onOpenAiInsights: () -> Unit,
    onOpenRewards: () -> Unit,
) {
    val vm: InsightsViewModel = viewModel(
        factory = ledgerViewModelFactory {
            InsightsViewModel(it.useCases.getCategories, it.useCases.getBreakdown,
                it.useCases.getRadarSummary)
        },
    )
    val state by vm.uiState.collectAsStateWithLifecycle()

    val allCategories = state.breakdown?.categories.orEmpty()
    val filteredTotal = allCategories.sumOf { it.total }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CanvasBlack)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(
            text = "Insights",
            style = MaterialTheme.typography.headlineMedium,
            color = TextPrimary,
        )
        Spacer(Modifier.height(12.dp))

        if (filteredTotal > 0.0) {
            HeroNumber(label = "Spent", amount = filteredTotal, change = null)
            Spacer(Modifier.height(16.dp))
        }

        InsightsCoverHubRow(
            title = "AI Insights",
            subtitle = "Monthly AI summaries",
            accent = AccentTeal,
            onClick = onOpenAiInsights,
        )
        Spacer(Modifier.height(8.dp))
        InsightsCoverHubRow(
            title = "How I Pay",
            subtitle = "Cards · UPI · when",
            accent = AccentTeal,
            onClick = onOpenSpending,
        )
        Spacer(Modifier.height(8.dp))
        InsightsCoverHubRow(
            title = "Save More",
            subtitle = "Subs · leaks · goals",
            accent = AccentGreen,
            onClick = onOpenSaveMore,
        )
        Spacer(Modifier.height(8.dp))
        InsightsCoverHubRow(
            title = "Budgets",
            subtitle = "Caps · overspend alerts",
            accent = Color(0xFFFBBF24),
            onClick = onOpenBudget,
        )
        Spacer(Modifier.height(8.dp))
        InsightsCoverHubRow(
            title = "Rewards",
            subtitle = "Points · redeem · expiry",
            accent = AccentGreen,
            onClick = onOpenRewards,
        )
        Spacer(Modifier.height(8.dp))
        InsightsCoverHubRow(
            title = "Money Radar",
            subtitle = "Mandates · forex · fees",
            accent = Color(0xFFEF4444),
            onClick = onOpenMoneyRadar,
            badgeCount = state.radarNewCount,
        )
    }
}

/**
 * Full-width hub entry row for the Cover layout: tinted title + subtitle on
 * the left, optional live badge, and a chevron "›" on the right.
 * Reuses the same color/badge data as [InsightsNavTile] in the Main layout so
 * the two layouts cannot diverge.
 */
@Composable
private fun InsightsCoverHubRow(
    title: String,
    subtitle: String,
    accent: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
    badgeCount: Int = 0,
) {
    CredCard(modifier = Modifier.clickable { onClick() }) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleSmall,
                        color = accent,
                        fontWeight = FontWeight.Bold,
                    )
                    if (badgeCount > 0) {
                        Spacer(Modifier.width(6.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(accent)
                                .padding(horizontal = 6.dp, vertical = 1.dp),
                        ) {
                            Text(
                                badgeCount.toString(),
                                style = MaterialTheme.typography.labelSmall,
                                color = CanvasBlack,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(subtitle, style = MaterialTheme.typography.labelSmall, color = TextMuted)
            }
            Text(
                text = "›",
                style = MaterialTheme.typography.titleLarge,
                color = accent,
            )
        }
    }
}

/**
 * Full-width AI Insights hub entry (Main layout): "✨ AI Insights" title + subtitle
 * with a trailing chevron, styled with the shared [CredCard] surface so it sits with
 * the rest of the hub. Opens the per-month LLM summary screen.
 */
@Composable
private fun AiInsightsHubCard(onClick: () -> Unit) {
    CredCard(modifier = Modifier.clickable { onClick() }) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "✨  AI Insights",
                    style = MaterialTheme.typography.titleSmall,
                    color = AccentTeal,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "Monthly AI summaries of your money",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted,
                )
            }
            Text(
                text = "›",
                style = MaterialTheme.typography.titleLarge,
                color = AccentTeal,
            )
        }
    }
}

/** Uppercase eyebrow label. */
@Composable
private fun InsightsEyebrow(text: String, color: androidx.compose.ui.graphics.Color = TextSecondary) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = color,
        letterSpacing = 1.4.sp,
    )
}

// ── Date-range chips ───────────────────────────────────────────────────────────

@Composable
private fun RangeChips(selected: RangePreset, onSelect: (RangePreset) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RangePreset.entries.forEach { p ->
            FilterChipPill(
                label = p.label,
                active = selected == p,
                onClick = { onSelect(p) },
            )
        }
    }
}

@Composable
private fun FilterChipPill(label: String, active: Boolean, onClick: () -> Unit) {
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

// ── Total spent hero ───────────────────────────────────────────────────────────

@Composable
private fun TotalSpentHero(total: Double) {
    val animated by animateFloatAsState(
        targetValue = total.toFloat(),
        animationSpec = tween(900),
        label = "insightsCountUp",
    )
    CredCard(padding = 24) {
        InsightsEyebrow("Total Spent")
        Spacer(Modifier.height(12.dp))
        // Whole rupees + length-bucketed auto-shrink (same no-overflow treatment as
        // the Dashboard SPENT hero) so very large totals stay on a single line.
        val formatted = formatMoney(animated.toDouble(), showPaise = false)
        val heroSize = when {
            formatted.length > 14 -> 30.sp
            formatted.length > 12 -> 36.sp
            formatted.length > 10 -> 42.sp
            else -> 48.sp
        }
        Text(
            text = formatted,
            style = MaterialTheme.typography.displayLarge.copy(fontSize = heroSize),
            color = TextPrimary,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "debit only",
            style = MaterialTheme.typography.labelSmall,
            color = TextMuted,
        )
    }
}

// ── Category single-select filter ───────────────────────────────────────────────

@Composable
private fun InsightsCategoryChips(
    categories: List<CategoryBreakdownDto>,
    selected: String?,
    onSelect: (String?) -> Unit,
) {
    if (categories.isNotEmpty()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChipPill(label = "All", active = selected == null, onClick = { onSelect(null) })
            categories.sortedByDescending { it.total }.forEach { cat ->
                FilterChipPill(
                    label = cat.category.replaceFirstChar { it.uppercase() },
                    active = selected.equals(cat.category, ignoreCase = true),
                    onClick = { onSelect(cat.category) },
                )
            }
        }
    }
}

// ── Category breakdown card (expandable) ────────────────────────────────────────

@Composable
private fun CategoryBreakdownCard(
    category: CategoryBreakdownDto,
    total: Double,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    onDrillDown: () -> Unit,
) {
    val color = styleFor(category.category).color
    val pct = if (total > 0.0) (category.total / total * 100.0) else 0.0
    val chevronRotation by animateFloatAsState(
        targetValue = if (isExpanded) 180f else 0f,
        animationSpec = tween(250),
        label = "chevronRotate",
    )

    CredCard {
        Column(modifier = Modifier.animateContentSize()) {
            // Category header row — bold/elevated. Tapping the row expands; the
            // leading chip drills down into the transaction list for this category.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(onClick = onToggleExpand)
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(modifier = Modifier.clickable(onClick = onDrillDown)) {
                    CategoryIconChip(category.category, size = 44.dp)
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
                            text = category.category.replaceFirstChar { it.uppercase() },
                            style = MaterialTheme.typography.bodyLarge,
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(horizontalAlignment = Alignment.End) {
                            MoneyText(amount = category.total, fontSize = 17.sp, color = TextPrimary, showPaise = false)
                            Text(
                                text = "${pct.roundToInt()}%",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextMuted,
                            )
                        }
                    }
                    // Mini-bar: pct of filtered total in the category color.
                    MiniBar(fraction = (pct / 100.0).toFloat(), color = color, height = 6)
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
                val maxSub = category.subcategories.maxOfOrNull { it.total }?.takeIf { it > 0.0 } ?: 1.0
                category.subcategories.sortedByDescending { it.total }.forEachIndexed { i, sub ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 58.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f, fill = false)) {
                                    Text(
                                        text = sub.name.replaceFirstChar { it.uppercase() },
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = TextSecondary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = "${sub.count} txn${if (sub.count == 1) "" else "s"}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = TextMuted,
                                    )
                                }
                                Spacer(Modifier.width(12.dp))
                                MoneyText(amount = sub.total, fontSize = 14.sp, color = TextSecondary, showPaise = false)
                            }
                            MiniBar(
                                fraction = (sub.total / maxSub).toFloat(),
                                color = color.copy(alpha = 0.6f),
                                height = 4,
                            )
                        }
                    }
                    if (i < category.subcategories.size - 1) Spacer(Modifier.height(12.dp))
                }
                if (category.subcategories.isEmpty()) {
                    Text(
                        text = "No sub-category detail.",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted,
                        modifier = Modifier.padding(start = 58.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun MiniBar(fraction: Float, color: androidx.compose.ui.graphics.Color, height: Int) {
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

// ── Empty state ────────────────────────────────────────────────────────────────

@Composable
private fun InsightsEmptyCard(message: String) {
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
                    imageVector = Icons.Filled.BarChart,
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
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

// ── Custom date-range picker dialog ─────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InsightsRangePickerDialog(
    initialStartIso: String,
    initialEndExclusiveIso: String,
    onDismiss: () -> Unit,
    onConfirm: (startIso: String, endExclusiveIso: String) -> Unit,
) {
    val zone = remember { ZoneId.systemDefault() }
    fun isoToMillis(iso: String): Long? = runCatching {
        LocalDate.parse(iso).atStartOfDay(zone).toInstant().toEpochMilli()
    }.getOrNull()
    fun millisToLocalDate(millis: Long): LocalDate =
        Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()

    val state = rememberDateRangePickerState(
        initialSelectedStartDateMillis = isoToMillis(initialStartIso),
        // The picker's selected end is INCLUSIVE; our stored end is exclusive, so
        // seed it with the last included day.
        initialSelectedEndDateMillis = isoToMillis(initialEndExclusiveIso)?.let {
            isoToMillis(LocalDate.parse(initialEndExclusiveIso).minusDays(1).toString())
        },
    )

    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    val startMillis = state.selectedStartDateMillis
                    val endMillis = state.selectedEndDateMillis
                    if (startMillis != null && endMillis != null) {
                        val start = millisToLocalDate(startMillis)
                        // Selected end is inclusive → store exclusive end (+1 day).
                        val endExclusive = millisToLocalDate(endMillis).plusDays(1)
                        onConfirm(start.toString(), endExclusive.toString())
                    } else {
                        onDismiss()
                    }
                },
            ) { Text("Apply", color = AccentTeal) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = TextSecondary) }
        },
    ) {
        DateRangePicker(
            state = state,
            modifier = Modifier.height(520.dp),
        )
    }
}

@Composable
private fun InsightsNavTile(
    title: String,
    subtitle: String,
    accent: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    badgeCount: Int = 0,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(SurfaceMid)
            .clickable { onClick() }
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = accent,
                fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f, fill = false))
            if (badgeCount > 0) {
                Spacer(Modifier.width(6.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(accent)
                        .padding(horizontal = 6.dp, vertical = 1.dp),
                ) {
                    Text(badgeCount.toString(), style = MaterialTheme.typography.labelSmall,
                        color = CanvasBlack, fontWeight = FontWeight.Bold)
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(subtitle, style = MaterialTheme.typography.labelSmall, color = TextMuted)
        Spacer(Modifier.height(6.dp))
        Text("View →", style = MaterialTheme.typography.labelMedium, color = accent)
    }
}
