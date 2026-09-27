package com.ledgernoir.tracker.ui.bills

import com.ledgernoir.tracker.ui.designsystem.*

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
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
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CreditCard
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ledgernoir.tracker.data.api.BillDto
import com.ledgernoir.tracker.data.repository.ExpenseRepository
import com.ledgernoir.tracker.ui.theme.AccentGreen
import com.ledgernoir.tracker.ui.theme.AccentTeal
import com.ledgernoir.tracker.ui.theme.BorderGray
import com.ledgernoir.tracker.ui.theme.CanvasBlack
import com.ledgernoir.tracker.ui.theme.DebitRose
import com.ledgernoir.tracker.ui.theme.DisplayFamily
import com.ledgernoir.tracker.ui.theme.SurfaceMid
import com.ledgernoir.tracker.ui.theme.TextMuted
import com.ledgernoir.tracker.ui.theme.TextPrimary
import com.ledgernoir.tracker.ui.theme.TextSecondary
import java.time.LocalDate

/**
 * Dedicated "Bills" screen: shows unpaid credit-card statements that are due this
 * calendar month or later (excludes bills whose due date fell in a previous
 * month), sorted by due date ascending. Each row offers a "Mark paid" action.
 */
@Composable
fun BillsScreen(
    repository: ExpenseRepository,
    onOpenCard: (accountId: Int, title: String) -> Unit = { _, _ -> },
    onOpenRewards: () -> Unit = {},
    scrollAnchor: String? = null,
    onAnchorConsumed: () -> Unit = {},
) {
    when (LocalFormFactor.current) {
        FormFactor.Cover -> BillsCover(repository, onOpenCard, onOpenRewards, scrollAnchor, onAnchorConsumed)
        else -> BillsMain(repository, onOpenCard, onOpenRewards, scrollAnchor, onAnchorConsumed)
    }
}

@Composable
private fun BillsCover(
    repository: ExpenseRepository,
    onOpenCard: (accountId: Int, title: String) -> Unit,
    onOpenRewards: () -> Unit,
    scrollAnchor: String? = null,
    onAnchorConsumed: () -> Unit = {},
) {
    val vm: BillsViewModel = viewModel(
        factory = ledgerViewModelFactory {
            BillsViewModel(it.useCases.getBills, it.useCases.getCards, it.useCases.markBillPaid,
                it.useCases.getCardUtilization, it.useCases.setCardLimit)
        },
    )
    val state by vm.uiState.collectAsStateWithLifecycle()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CanvasBlack)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        HeroNumber(label = "Total due now", amount = state.cards?.totalDue ?: 0.0, change = null)
        Spacer(Modifier.height(12.dp))
        val cardList = state.cards?.cards ?: emptyList()
        val anchorIndex = scrollAnchor
            ?.let { anchor -> cardList.indexOfFirst { it.last4 == anchor } }
            ?.takeIf { it >= 0 }
        var highlightLast4 by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(scrollAnchor, cardList) {
            if (scrollAnchor != null && anchorIndex != null) {
                highlightLast4 = scrollAnchor
                onAnchorConsumed()
            }
        }
        CardCarousel(count = cardList.size, scrollToPage = anchorIndex) { i ->
            val c = cardList[i]
            BillCardArt(
                c = c,
                util = state.utilizationByAccount[c.accountId],
                onSetDetails = { limit, network -> vm.setCardDetails(c.accountId, limit, network) },
                onClick = { onOpenCard(c.accountId, "${c.bank} ••${c.last4 ?: ""}") },
                highlighted = c.last4 != null && c.last4 == highlightLast4,
            )
        }
        Spacer(Modifier.height(12.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable { onOpenRewards() }
                .background(SurfaceMid)
                .padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            Text(
                "View card rewards →",
                color = AccentGreen,
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BillsMain(
    repository: ExpenseRepository,
    onOpenCard: (accountId: Int, title: String) -> Unit = { _, _ -> },
    onOpenRewards: () -> Unit = {},
    scrollAnchor: String? = null,
    onAnchorConsumed: () -> Unit = {},
) {
    val vm: BillsViewModel = viewModel(
        factory = ledgerViewModelFactory {
            BillsViewModel(it.useCases.getBills, it.useCases.getCards, it.useCases.markBillPaid,
                it.useCases.getCardUtilization, it.useCases.setCardLimit)
        },
    )
    val state by vm.uiState.collectAsStateWithLifecycle()
    val bills = state.bills
    val cards = state.cards
    val loading = state.loading

    val haptics = LocalHapticFeedback.current

    // Notification deep-link: scroll the matching card into view and pulse it.
    val bringReq = remember { BringIntoViewRequester() }
    var highlightLast4 by remember { mutableStateOf<String?>(null) }
    val cardList = cards?.cards.orEmpty()
    LaunchedEffect(scrollAnchor, cardList) {
        val target = scrollAnchor
        if (target != null && cardList.any { it.last4 == target }) {
            bringReq.bringIntoView()
            highlightLast4 = target
            onAnchorConsumed()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CanvasBlack)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 20.dp),
    ) {
        ResponsiveContainer {
            Text(
                text = "Pending Bills",
                style = MaterialTheme.typography.headlineMedium,
                color = TextPrimary,
                fontFamily = DisplayFamily,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Credit-card payments due",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
            )
            Spacer(Modifier.height(20.dp))

            if (loading) {
                Box(
                    Modifier.fillMaxWidth().padding(40.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(color = AccentTeal)
                }
            } else {
                // --- Your Cards: live unbilled + outstanding bill per card ---
                cards?.cards?.takeIf { it.isNotEmpty() }?.let { cardList ->
                    BillsCardsSummary(
                        unbilled = cards!!.totalUnbilled,
                        due = cards!!.totalDue,
                    )
                    Spacer(Modifier.height(16.dp))
                    SectionHeader("Your cards")
                    Spacer(Modifier.height(12.dp))
                    cardList.forEach { c ->
                        val isAnchor = c.last4 != null && c.last4 == scrollAnchor
                        Box(
                            modifier = if (isAnchor) {
                                Modifier.bringIntoViewRequester(bringReq)
                            } else {
                                Modifier
                            },
                        ) {
                            BillCardArt(
                                c = c,
                                util = state.utilizationByAccount[c.accountId],
                                onSetDetails = { limit, network ->
                                    vm.setCardDetails(c.accountId, limit, network)
                                },
                                onClick = {
                                    onOpenCard(c.accountId, "${c.bank} ••${c.last4 ?: ""}")
                                },
                                highlighted = c.last4 != null && c.last4 == highlightLast4,
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                    }
                    Spacer(Modifier.height(10.dp))
                }

                Spacer(Modifier.height(12.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onOpenRewards() }
                        .background(SurfaceMid)
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                ) {
                    Text(
                        "View card rewards →",
                        color = AccentGreen,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }

                // --- Pending (generated) bills ---
                if (bills.isEmpty()) {
                    SectionHeader("Pending bills")
                    Spacer(Modifier.height(12.dp))
                    BillsEmptyState()
                } else {
                    SectionHeader("Pending bills")
                    Spacer(Modifier.height(12.dp))
                    bills.forEachIndexed { i, bill ->
                        BillCard(
                            bill = bill,
                            onMarkPaid = { id ->
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                vm.markBillPaid(id)
                            },
                        )
                        if (i < bills.size - 1) Spacer(Modifier.height(14.dp))
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun BillsSummaryCard(totalDue: Double, count: Int) {
    CredCard {
        Text(
            text = "TOTAL DUE",
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondary,
            letterSpacing = 1.4.sp,
        )
        Spacer(Modifier.height(10.dp))
        MoneyText(amount = totalDue, fontSize = 32.sp, color = TextPrimary)
        Spacer(Modifier.height(6.dp))
        Text(
            text = if (count == 1) "1 bill due" else "$count bills due",
            style = MaterialTheme.typography.labelMedium,
            color = AccentTeal,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = TextSecondary,
        letterSpacing = 1.4.sp,
    )
}

@Composable
private fun BillsCardsSummary(unbilled: Double, due: Double) {
    CredCard {
        Row(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text("UNBILLED", style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary, letterSpacing = 1.4.sp)
                Spacer(Modifier.height(8.dp))
                MoneyText(amount = unbilled, fontSize = 24.sp, color = Color(0xFFF59E0B),
                    showPaise = false)
                Text("accumulating", style = MaterialTheme.typography.labelSmall, color = TextMuted)
            }
            Box(Modifier.width(1.dp).height(56.dp).background(BorderGray))
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text("DUE NOW", style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary, letterSpacing = 1.4.sp)
                Spacer(Modifier.height(8.dp))
                MoneyText(amount = due, fontSize = 24.sp, color = DebitRose, showPaise = false)
                Text("billed · outstanding", style = MaterialTheme.typography.labelSmall,
                    color = TextMuted)
            }
        }
    }
}

@Composable
private fun BillCardArt(
    c: com.ledgernoir.tracker.data.api.CardStateDto,
    util: com.ledgernoir.tracker.data.api.CardUtilizationDto?,
    onSetDetails: (Double?, String?) -> Unit,
    onClick: () -> Unit,
    highlighted: Boolean = false,
) {
    val trailing = c.currentBill?.let { bill ->
        if (bill.totalDue > 0) "₹${"%,.0f".format(bill.totalDue)} due" else "no dues"
    }
    val pct = util?.takeIf { it.source != "unknown" && it.totalLimit > 0 }?.utilizationPct?.toFloat()

    // Brief teal pulse when this card is the notification target. The border
    // fades off ~1200ms after the highlight begins.
    var pulseOn by remember { mutableStateOf(false) }
    LaunchedEffect(highlighted) {
        if (highlighted) {
            pulseOn = true
            kotlinx.coroutines.delay(1200)
            pulseOn = false
        } else {
            pulseOn = false
        }
    }
    val borderColor by animateColorAsState(
        targetValue = if (pulseOn) AccentTeal else Color.Transparent,
        animationSpec = tween(durationMillis = 400),
        label = "billCardPulse",
    )

    Column {
        com.ledgernoir.tracker.ui.designsystem.CreditCardArt(
            bank = c.bank,
            last4 = c.last4,
            holderName = null,
            network = c.network,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .border(2.dp, borderColor, RoundedCornerShape(16.dp))
                .clickable { onClick() },
            height = 200.dp,
        )

        // Utilization presentation lives BELOW the face now.
        if (pct != null) {
            Spacer(Modifier.height(10.dp))
            val clamped = pct.coerceIn(0f, 100f)
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(5.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(BorderGray),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(clamped / 100f)
                        .height(5.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(AccentTeal),
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "${pct.toInt()}% used" + (trailing?.let { " · $it" } ?: ""),
                color = TextSecondary,
                style = MaterialTheme.typography.labelSmall,
            )
        }

        Spacer(Modifier.height(8.dp))
        var showDialog by remember { mutableStateOf(false) }
        val label = if (pct != null) "Adjust limit ›" else "Set limit to track utilization ›"
        Text(
            label,
            color = AccentTeal,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clickable { showDialog = true },
        )
        if (showDialog) {
            CardDetailsDialog(
                currentNetwork = c.network,
                onDismiss = { showDialog = false },
                onConfirm = { limit, network ->
                    showDialog = false
                    onSetDetails(limit, network)
                },
            )
        }
    }
}

@Composable
private fun BillsEmptyState() {
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
                    .background(AccentGreen.copy(alpha = 0.15f), RoundedCornerShape(18.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = "All caught up",
                    tint = AccentGreen,
                    modifier = Modifier.size(28.dp),
                )
            }
            Spacer(Modifier.height(14.dp))
            Text(
                text = "No pending bills 🎉",
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "You're all caught up.",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun BillCard(bill: BillDto, onMarkPaid: (Int) -> Unit) {
    CredCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
        ) {
            // Card icon chip (AccentTeal tint).
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(AccentTeal.copy(alpha = 0.15f), RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.CreditCard,
                    contentDescription = "Credit card",
                    tint = AccentTeal,
                    modifier = Modifier.size(22.dp),
                )
            }
            Spacer(Modifier.width(14.dp))

            // Middle column: bank • last4, due date, days-until chip.
            Column(modifier = Modifier.weight(1f)) {
                val title = if (bill.last4 != null) {
                    "${bill.bank} ••${bill.last4}"
                } else {
                    "${bill.bank} Credit Card"
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Due ${formatBillDueDate(bill.dueDate)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary,
                )
                Spacer(Modifier.height(8.dp))
                BillDueChip(daysUntilDue = bill.daysUntilDue)
            }
            Spacer(Modifier.width(12.dp))

            // Right column: amount due + optional minimum.
            Column(horizontalAlignment = Alignment.End) {
                MoneyText(amount = bill.totalDue, fontSize = 17.sp, color = TextPrimary)
                if (bill.minDue != null) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "min ${formatMoney(bill.minDue)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted,
                    )
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(BorderGray),
        )
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            Text(
                text = "Mark paid",
                style = MaterialTheme.typography.labelMedium,
                color = AccentTeal,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clickable { onMarkPaid(bill.id) },
            )
        }
    }
}

/** Days-until-due pill: overdue (rose), today/soon ≤5 (amber), otherwise neutral. */
@Composable
private fun BillDueChip(daysUntilDue: Int) {
    val amber = Color(0xFFFBBF24)
    val (label, color) = when {
        daysUntilDue < 0 -> "Overdue" to DebitRose
        daysUntilDue == 0 -> "Due today" to amber
        daysUntilDue <= 5 -> "Due in $daysUntilDue days" to amber
        else -> "Due in $daysUntilDue days" to TextSecondary
    }
    Box(
        modifier = Modifier
            .background(color.copy(alpha = 0.14f), RoundedCornerShape(20.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** Formats a "YYYY-MM-DD" date to "05 May 2026"; falls back to the raw value. */
private fun formatBillDueDate(date: String): String {
    return try {
        val d = LocalDate.parse(date)
        val month = d.month.getDisplayName(
            java.time.format.TextStyle.SHORT,
            java.util.Locale.getDefault(),
        )
        "%02d %s %d".format(d.dayOfMonth, month, d.year)
    } catch (e: Exception) {
        date
    }
}

/**
 * Card-details editor: an optional sanctioned-limit field plus a network selector
 * (Visa / Mastercard / RuPay / None). Pre-selects the card's current [currentNetwork].
 * On Save it reports the entered limit (or null if blank) and the selected network
 * ("None" → null), so the caller may update just the network without a limit.
 */
@Composable
private fun CardDetailsDialog(
    currentNetwork: String?,
    onDismiss: () -> Unit,
    onConfirm: (limit: Double?, network: String?) -> Unit,
) {
    var text by remember { mutableStateOf("") }
    // null = "None" chip; otherwise the lowercase network key.
    var selected by remember { mutableStateOf(currentNetwork?.lowercase()) }
    val options = listOf("visa" to "Visa", "mastercard" to "Mastercard", "rupay" to "RuPay")

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Card details") },
        text = {
            Column {
                androidx.compose.material3.OutlinedTextField(
                    value = text, onValueChange = { text = it.filter { ch -> ch.isDigit() } },
                    singleLine = true, label = { Text("Sanctioned limit (₹)") },
                )
                Spacer(Modifier.height(16.dp))
                Text("Network", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    options.forEach { (key, label) ->
                        androidx.compose.material3.FilterChip(
                            selected = selected == key,
                            onClick = { selected = key },
                            label = { Text(label) },
                        )
                    }
                    androidx.compose.material3.FilterChip(
                        selected = selected == null,
                        onClick = { selected = null },
                        label = { Text("None") },
                    )
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(
                onClick = { onConfirm(text.toDoubleOrNull(), selected) },
            ) { Text("Save", color = AccentTeal) }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text("Cancel", color = TextSecondary)
            }
        },
    )
}
