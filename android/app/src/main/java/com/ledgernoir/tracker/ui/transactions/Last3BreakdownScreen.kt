package com.ledgernoir.tracker.ui.transactions

import com.ledgernoir.tracker.ui.designsystem.*

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ledgernoir.tracker.data.api.SummaryItem
import com.ledgernoir.tracker.data.repository.ExpenseRepository
import com.ledgernoir.tracker.ui.common.ledgerViewModelFactory
import com.ledgernoir.tracker.ui.theme.AccentTeal
import com.ledgernoir.tracker.ui.theme.CanvasBlack
import com.ledgernoir.tracker.ui.theme.DisplayFamily
import com.ledgernoir.tracker.ui.theme.SurfaceMid
import com.ledgernoir.tracker.ui.theme.TextPrimary
import com.ledgernoir.tracker.ui.theme.TextSecondary

/**
 * "Last 3 Months" breakdown: a category donut + tap-to-filter chips over the full
 * date-bounded transaction list. The donut/chips/list are all derived from one
 * fetch (see [Last3BreakdownViewModel]).
 */
@Composable
fun Last3BreakdownScreen(
    repository: ExpenseRepository,
    title: String,
    startIso: String,
    endIso: String,
    onBack: () -> Unit,
) {
    val vm: Last3BreakdownViewModel = viewModel(
        factory = ledgerViewModelFactory {
            Last3BreakdownViewModel(it.useCases.getCategories, it.useCases.searchTransactions)
        },
    )
    val state by vm.uiState.collectAsStateWithLifecycle()
    val haptics = LocalHapticFeedback.current

    LaunchedEffect(startIso, endIso) { vm.load(startIso, endIso) }

    val selected = state.selectedCategory
    val visibleTxns = remember(state.transactions, selected, state.categoryNames) {
        val debits = state.transactions.filter { it.direction != "credit" }
        if (selected == null) debits
        else debits.filter { t ->
            val name = t.categoryId?.let { state.categoryNames[it] } ?: "Uncategorized"
            name.equals(selected, ignoreCase = true)
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().background(CanvasBlack),
    ) {
        // App bar
        Row(
            modifier = Modifier.fillMaxWidth()
                .padding(start = 8.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.size(44.dp).clickable {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onBack()
                },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = TextPrimary)
            }
            Spacer(Modifier.width(4.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge.copy(fontFamily = DisplayFamily),
                color = TextPrimary, fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
        }

        if (state.loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = AccentTeal)
            }
        } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // Donut header (reflects the current selection)
            item(key = "donut") {
                CategoryDonut(
                    summary = state.categoryTotals,
                    selectedCategory = selected,
                )
                Spacer(Modifier.height(16.dp))
            }
            // Filter chips: All + one per category
            item(key = "chips") {
                CategoryChips(
                    categories = state.categoryTotals,
                    selected = selected,
                    onSelect = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        vm.selectCategory(it)
                    },
                )
                Spacer(Modifier.height(12.dp))
            }
            // Summary line
            item(key = "summary") {
                val prefix = selected?.replaceFirstChar { it.uppercase() }?.let { "$it · " } ?: ""
                Text(
                    text = "$prefix${visibleTxns.size} transaction" +
                        if (visibleTxns.size == 1) "" else "s",
                    style = MaterialTheme.typography.labelMedium, color = TextSecondary,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
                Spacer(Modifier.height(8.dp))
            }

            if (visibleTxns.isEmpty()) {
                item(key = "empty") {
                    Text("No transactions", style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary, modifier = Modifier.padding(vertical = 24.dp))
                }
            } else {
                val items = buildGroupedDetailItems(visibleTxns, state.categoryNames)
                items.forEach { di ->
                    when (di) {
                        is DetailListItem.Header -> item(key = "h-${di.label}") { DetailDayHeader(di) }
                        is DetailListItem.Row -> item(key = "t-${di.txn.id}") { DetailTxnRow(di) }
                    }
                }
            }
            item(key = "tail") { Spacer(Modifier.height(24.dp)) }
        }
        } // else
    }
}

/** Horizontally scrollable category filter chips ("All" + one per category). */
@Composable
private fun CategoryChips(
    categories: List<SummaryItem>,
    selected: String?,
    onSelect: (String?) -> Unit,
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        item(key = "chip-all") {
            FilterChipPill(label = "All", color = null, selected = selected == null) { onSelect(null) }
        }
        items(categories.size, key = { categories[it].category }) { i ->
            val c = categories[i]
            FilterChipPill(
                label = c.category.replaceFirstChar { it.uppercase() },
                color = styleFor(c.category).color,
                selected = selected.equals(c.category, ignoreCase = true),
            ) { onSelect(c.category) }
        }
    }
}

@Composable
private fun FilterChipPill(
    label: String,
    color: Color?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(if (selected) AccentTeal else SurfaceMid)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (color != null) {
            Box(Modifier.size(8.dp).clip(RoundedCornerShape(2.dp))
                .background(if (selected) CanvasBlack else color))
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) CanvasBlack else TextSecondary,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1, softWrap = false,
        )
    }
}
