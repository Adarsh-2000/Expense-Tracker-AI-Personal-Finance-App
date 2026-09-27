package com.ledgernoir.tracker.ui.transactions

import com.ledgernoir.tracker.ui.designsystem.*

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ledgernoir.tracker.ui.common.ledgerViewModelFactory
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ledgernoir.tracker.data.repository.ExpenseRepository
import com.ledgernoir.tracker.ui.theme.AccentTeal
import com.ledgernoir.tracker.ui.theme.CanvasBlack
import com.ledgernoir.tracker.ui.theme.DisplayFamily
import com.ledgernoir.tracker.ui.theme.TextPrimary
import com.ledgernoir.tracker.ui.theme.TextSecondary

/**
 * Reusable full-screen drill-down: fetches a date-bounded (optionally
 * category-scoped) transaction list and renders it with a date/amount sort toggle.
 */
@Composable
fun TransactionListScreen(
    repository: ExpenseRepository,
    title: String,
    startIso: String,
    endIso: String,
    categoryId: Int? = null,
    accountId: Int? = null,
    query: String? = null,
    onBack: () -> Unit,
) {
    val vm: TransactionListViewModel = viewModel(
        factory = ledgerViewModelFactory {
            TransactionListViewModel(it.useCases.getCategories, it.useCases.searchTransactions)
        },
    )
    val state by vm.uiState.collectAsStateWithLifecycle()
    val transactions = state.transactions
    val categoryNames = state.categoryNames
    val loading = state.loading
    val totalSpent = state.totalSpent
    var sortBy by remember { mutableStateOf(SortBy.DATE) }

    val haptics = LocalHapticFeedback.current

    // Pass the (non-construction-time) query args into the VM. Keyed on the args so
    // a navigation that changes range/scope re-fetches, exactly like the original.
    LaunchedEffect(startIso, endIso, categoryId, accountId, query) {
        vm.load(startIso, endIso, categoryId, accountId, query)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CanvasBlack),
    ) {
        // Top app bar: back arrow + title
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clickable {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onBack()
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = TextPrimary,
                )
            }
            Spacer(Modifier.width(4.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge.copy(fontFamily = DisplayFamily),
                color = TextPrimary,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }

        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.TopCenter,
        ) {
            val isCover = LocalFormFactor.current == FormFactor.Cover
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 520.dp)
                    .padding(horizontal = 16.dp),
            ) {
                if (loading) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = AccentTeal)
                    }
                } else {
                    // Summary strip: total spent + count
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp, bottom = 16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Bottom,
                    ) {
                        Column {
                            Text(
                                text = "TOTAL SPENT",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextSecondary,
                                letterSpacing = 1.4.sp,
                            )
                            Spacer(Modifier.height(6.dp))
                            MoneyText(
                                amount = totalSpent,
                                fontSize = if (isCover) 20.sp else 28.sp,
                                color = TextPrimary,
                            )
                        }
                        Text(
                            text = "${transactions.size} transaction" +
                                if (transactions.size == 1) "" else "s",
                            style = MaterialTheme.typography.labelMedium,
                            color = TextSecondary,
                        )
                    }

                    // Sort toggle pill
                    SortToggle(
                        sortBy = sortBy,
                        onSelect = {
                            if (it != sortBy) {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                sortBy = it
                            }
                        },
                    )
                    Spacer(Modifier.height(16.dp))

                    if (transactions.isEmpty()) {
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
                                    text = "No transactions",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = TextSecondary,
                                )
                            }
                        }
                    } else {
                        val listAlpha by animateFloatAsState(
                            targetValue = 1f,
                            animationSpec = tween(450),
                            label = "detailListFade",
                        )
                        AnimatedContent(
                            targetState = sortBy,
                            transitionSpec = {
                                fadeIn(tween(250)).togetherWith(fadeOut(tween(150)))
                            },
                            label = "sortSwap",
                            modifier = Modifier.alpha(listAlpha),
                        ) { sort ->
                            when (sort) {
                                SortBy.DATE -> {
                                    val items = buildGroupedDetailItems(transactions, categoryNames)
                                    LazyColumn(
                                        modifier = Modifier.fillMaxSize(),
                                        verticalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        items.forEach { item ->
                                            when (item) {
                                                is DetailListItem.Header ->
                                                    item(key = "h-${item.label}") { DetailDayHeader(item) }
                                                is DetailListItem.Row ->
                                                    item(key = "t-${item.txn.id}") { DetailTxnRow(item) }
                                            }
                                        }
                                        item { Spacer(Modifier.height(24.dp)) }
                                    }
                                }
                                SortBy.AMOUNT -> {
                                    val sorted = transactions.sortedByDescending { it.amount }
                                    LazyColumn(
                                        modifier = Modifier.fillMaxSize(),
                                        verticalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        items(sorted.size, key = { sorted[it].id }) { idx ->
                                            val txn = sorted[idx]
                                            DetailTxnRow(
                                                DetailListItem.Row(
                                                    txn,
                                                    txn.categoryId?.let { categoryNames[it] },
                                                )
                                            )
                                        }
                                        item { Spacer(Modifier.height(24.dp)) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

