package com.ledgernoir.tracker.ui.rewards

import com.ledgernoir.tracker.ui.designsystem.*

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ledgernoir.tracker.data.api.RewardCardDto
import com.ledgernoir.tracker.data.repository.ExpenseRepository
import com.ledgernoir.tracker.ui.common.ledgerViewModelFactory
import com.ledgernoir.tracker.ui.theme.AccentGreen
import com.ledgernoir.tracker.ui.theme.AccentTeal
import com.ledgernoir.tracker.ui.theme.CanvasBlack
import com.ledgernoir.tracker.ui.theme.DisplayFamily
import com.ledgernoir.tracker.ui.theme.SurfaceMid
import com.ledgernoir.tracker.ui.theme.TextMuted
import com.ledgernoir.tracker.ui.theme.TextPrimary
import com.ledgernoir.tracker.ui.theme.TextSecondary

private val AmberWarn = Color(0xFFFBBF24)

private fun cardAccent(bank: String): Color = when (bank.uppercase()) {
    "AXIS" -> Color(0xFFAE275F)
    "BOB" -> Color(0xFFF26522)
    "IDFC" -> Color(0xFF9C1D26)
    else -> AccentTeal
}

/**
 * Brief teal border pulse for a notification-targeted card. Returns the animated
 * border color (teal while pulsing, transparent otherwise). Mirrors the Bills
 * highlight pattern: the border fades off ~1200ms after the highlight begins.
 */
@Composable
private fun highlightCardBorder(highlighted: Boolean): Color {
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
        label = "rewardCardPulse",
    )
    return borderColor
}

@Composable
fun RewardsScreen(
    repository: ExpenseRepository,
    onBack: () -> Unit,
    onOpenCard: (bank: String, last4: String) -> Unit = { _, _ -> },
    scrollAnchor: String? = null,
    onAnchorConsumed: () -> Unit = {},
) {
    when (LocalFormFactor.current) {
        FormFactor.Cover -> RewardsCover(repository, onBack, onOpenCard, scrollAnchor, onAnchorConsumed)
        else -> RewardsMain(repository, onBack, onOpenCard, scrollAnchor, onAnchorConsumed)
    }
}

@Composable
private fun RewardsCover(
    repository: ExpenseRepository,
    onBack: () -> Unit,
    onOpenCard: (bank: String, last4: String) -> Unit = { _, _ -> },
    scrollAnchor: String? = null,
    onAnchorConsumed: () -> Unit = {},
) {
    val vm: RewardsViewModel = viewModel(
        factory = ledgerViewModelFactory { RewardsViewModel(it.useCases.getRewards) },
    )
    val state by vm.uiState.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CanvasBlack)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        HeroNumber(label = "Total redeemable", amount = state.totalValueInr, change = null)
        Spacer(Modifier.height(12.dp))

        val expiring = state.cards.firstOrNull { it.expiringPoints > 0 && it.expiringDate != null }
        if (expiring != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(AmberWarn.copy(alpha = 0.14f))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Text("⚠ ${expiring.expiringPoints} pts expire ${expiring.expiringDate}",
                    color = AmberWarn, style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(12.dp))
        }

        val anchorIndex = scrollAnchor
            ?.let { anchor -> state.cards.indexOfFirst { it.last4 == anchor } }
            ?.takeIf { it >= 0 }
        var highlightLast4 by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(scrollAnchor, state.cards) {
            if (scrollAnchor != null && anchorIndex != null) {
                highlightLast4 = scrollAnchor
                onAnchorConsumed()
            }
        }
        CardCarousel(count = state.cards.size, scrollToPage = anchorIndex) { i ->
            val c = state.cards[i]
            val pulseOn = highlightCardBorder(c.last4 == highlightLast4)
            Column(
                modifier = Modifier.clickable { onOpenCard(c.bank, c.last4) },
            ) {
                CreditCardArt(
                    bank = c.bank,
                    last4 = c.last4,
                    holderName = null,
                    network = c.network,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .border(2.dp, pulseOn, RoundedCornerShape(16.dp)),
                    height = 200.dp,
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${c.pointsBalance} pts · ", color = TextPrimary,
                        style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    MoneyText(amount = c.valueInr, fontSize = 14.sp, color = AccentGreen,
                        showPaise = false)
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RewardsMain(
    repository: ExpenseRepository,
    onBack: () -> Unit,
    onOpenCard: (bank: String, last4: String) -> Unit = { _, _ -> },
    scrollAnchor: String? = null,
    onAnchorConsumed: () -> Unit = {},
) {
    val vm: RewardsViewModel = viewModel(
        factory = ledgerViewModelFactory { RewardsViewModel(it.useCases.getRewards) },
    )
    val state by vm.uiState.collectAsStateWithLifecycle()
    val haptics = LocalHapticFeedback.current

    // Notification deep-link: scroll the matching reward card into view and pulse it.
    val bringReq = remember { BringIntoViewRequester() }
    var highlightLast4 by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(scrollAnchor, state.cards) {
        val target = scrollAnchor
        if (target != null && state.cards.any { it.last4 == target }) {
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            onBack()
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = TextSecondary)
                }
                Spacer(Modifier.width(4.dp))
                Text("Rewards", style = MaterialTheme.typography.headlineMedium,
                    color = TextPrimary, fontFamily = DisplayFamily)
            }
            Spacer(Modifier.height(20.dp))

            if (state.loading) {
                Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = AccentTeal)
                }
            } else if (state.cards.isEmpty()) {
                CredCard {
                    Text("No rewards yet", color = TextPrimary,
                        style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Text("Sync your email so we can read your card statements.",
                        color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
                }
            } else {
                if (state.cards.size > 1) {
                    CredCard {
                        Text("TOTAL REDEEMABLE", style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary, letterSpacing = 1.4.sp)
                        Spacer(Modifier.height(10.dp))
                        MoneyText(amount = state.totalValueInr, fontSize = 32.sp,
                            color = AccentGreen, showPaise = false)
                        Spacer(Modifier.height(6.dp))
                        Text("across ${state.cards.size} cards · ${state.totalPoints} pts",
                            color = TextSecondary, style = MaterialTheme.typography.labelMedium)
                    }
                    Spacer(Modifier.height(20.dp))
                }
                state.cards.forEachIndexed { i, card ->
                    val isAnchor = card.last4 == scrollAnchor
                    Box(
                        modifier = if (isAnchor) {
                            Modifier.bringIntoViewRequester(bringReq)
                        } else {
                            Modifier
                        },
                    ) {
                        RewardCard(
                            card,
                            onClick = { onOpenCard(card.bank, card.last4) },
                            highlighted = card.last4 == highlightLast4,
                        )
                    }
                    if (i < state.cards.size - 1) Spacer(Modifier.height(16.dp))
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun RewardCard(card: RewardCardDto, onClick: () -> Unit = {}, highlighted: Boolean = false) {
    val accent = cardAccent(card.bank)
    val pulseColor = highlightCardBorder(highlighted)
    CredCard(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .border(2.dp, pulseColor, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(accent))
            Spacer(Modifier.width(10.dp))
            Text("${card.bank} ••${card.last4}", color = TextPrimary,
                style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f))
            Text(card.displayName, color = TextSecondary,
                style = MaterialTheme.typography.labelMedium)
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text("${card.pointsBalance} pts", color = TextPrimary, fontSize = 22.sp,
                fontFamily = DisplayFamily, fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f))
            MoneyText(amount = card.valueInr, fontSize = 18.sp, color = AccentGreen,
                showPaise = false)
        }
        Spacer(Modifier.height(4.dp))
        val asOf = if (card.stale) "as of ${card.asOf} · may be outdated"
                   else "as of ${card.asOf}"
        Text(asOf, color = TextMuted, style = MaterialTheme.typography.labelSmall)

        if (card.expiringPoints > 0 && card.expiringDate != null) {
            Spacer(Modifier.height(10.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(AmberWarn.copy(alpha = 0.14f))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Text("⚠ ${card.expiringPoints} pts expire ${card.expiringDate}",
                    color = AmberWarn, style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold)
            }
        }

        if (card.redemptionOptions.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text("REDEEM AT", style = MaterialTheme.typography.labelSmall,
                color = TextSecondary, letterSpacing = 1.4.sp)
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                card.redemptionOptions.forEach { opt ->
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(SurfaceMid)
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        Text(opt.label, style = MaterialTheme.typography.labelMedium,
                            color = TextSecondary, maxLines = 1, softWrap = false)
                    }
                }
            }
        }

        val voucher = card.recentEvents.firstOrNull { it.kind == "voucher" && it.voucherExpiry != null }
        if (voucher != null) {
            Spacer(Modifier.height(10.dp))
            Text("🎟 ${voucher.voucherCode} · exp ${voucher.voucherExpiry}",
                color = TextSecondary, style = MaterialTheme.typography.labelMedium)
        }

        val redemptions = card.recentEvents.filter { it.kind == "redemption" }
        if (redemptions.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text("RECENT", style = MaterialTheme.typography.labelSmall,
                color = TextSecondary, letterSpacing = 1.4.sp)
            Spacer(Modifier.height(6.dp))
            redemptions.take(3).forEach { ev ->
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    Text(ev.merchant ?: "Redeemed", color = TextPrimary,
                        style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f),
                        maxLines = 1)
                    Text("${ev.points ?: 0} pts · ${ev.occurredAt}", color = TextMuted,
                        style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}
