package com.ledgernoir.tracker.ui.rewards

import com.ledgernoir.tracker.ui.designsystem.*

import android.content.Intent
import android.net.Uri
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ledgernoir.tracker.data.api.RewardCardDto
import com.ledgernoir.tracker.data.api.RewardOfferCategoryDto
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

private fun offerEmoji(kind: String): String = when (kind) {
    "lounge", "travel" -> "✈️"
    "dining" -> "🍽️"
    "movies" -> "🎬"
    "fuel" -> "⛽"
    "shopping" -> "🛍️"
    "forex" -> "🌍"
    else -> "🎁"
}

@Composable
fun RewardDetailScreen(
    repository: ExpenseRepository,
    bank: String,
    last4: String,
    onBack: () -> Unit,
) {
    val vm: RewardDetailViewModel = viewModel(
        factory = ledgerViewModelFactory { RewardDetailViewModel(it.useCases.getRewards, bank, last4) },
    )
    val state by vm.uiState.collectAsStateWithLifecycle()
    val haptics = LocalHapticFeedback.current
    val context = LocalContext.current
    val card = state.card

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
                Text(
                    text = card?.let { "${it.displayName} ••${it.last4}" } ?: "Card",
                    style = MaterialTheme.typography.headlineSmall,
                    color = TextPrimary, fontFamily = DisplayFamily,
                )
            }
            Spacer(Modifier.height(20.dp))

            if (state.loading && card == null) {
                Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = AccentTeal)
                }
            } else if (card == null) {
                CredCard {
                    Text("Card not found", color = TextPrimary,
                        style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                }
            } else {
                DetailHero(card)
                if (card.offerCategories.isNotEmpty()) {
                    Spacer(Modifier.height(20.dp))
                    SectionLabel("Card benefits & offers")
                    Spacer(Modifier.height(10.dp))
                    CredCard {
                        card.offerCategories.forEachIndexed { i, o ->
                            OfferRow(o)
                            if (i < card.offerCategories.size - 1) Spacer(Modifier.height(14.dp))
                        }
                    }
                }
                if (!card.portalUrl.isNullOrBlank()) {
                    Spacer(Modifier.height(12.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(AccentTeal.copy(alpha = 0.16f))
                            .clickable {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                runCatching {
                                    context.startActivity(
                                        Intent(Intent.ACTION_VIEW, Uri.parse(card.portalUrl))
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                }
                            }
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("Explore live offers →", color = AccentTeal,
                            style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    }
                }
                if (card.redemptionOptions.isNotEmpty()) {
                    Spacer(Modifier.height(20.dp))
                    SectionLabel("Redeem points")
                    Spacer(Modifier.height(10.dp))
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
                Spacer(Modifier.height(20.dp))
                SectionLabel("History")
                Spacer(Modifier.height(10.dp))
                if (card.recentEvents.isEmpty()) {
                    Text("No redemptions yet.", color = TextSecondary,
                        style = MaterialTheme.typography.bodyMedium)
                } else {
                    CredCard {
                        card.recentEvents.forEachIndexed { i, ev ->
                            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                val label = if (ev.kind == "voucher")
                                    "🎟 ${ev.voucherCode ?: "Voucher"}"
                                else (ev.merchant ?: "Redeemed")
                                Text(label, color = TextPrimary, modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                                val right = if (ev.kind == "voucher")
                                    "exp ${ev.voucherExpiry ?: "—"}"
                                else "${ev.points ?: 0} pts · ${ev.occurredAt}"
                                Text(right, color = TextMuted,
                                    style = MaterialTheme.typography.labelSmall)
                            }
                            if (i < card.recentEvents.size - 1) Spacer(Modifier.height(6.dp))
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun DetailHero(card: RewardCardDto) {
    val accent = cardAccent(card.bank)
    val isCover = LocalFormFactor.current == FormFactor.Cover
    CredCard {
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
            Text("${card.pointsBalance} pts", color = TextPrimary,
                fontSize = if (isCover) 18.sp else 24.sp,
                fontFamily = DisplayFamily, fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f))
            MoneyText(amount = card.valueInr, fontSize = if (isCover) 16.sp else 20.sp,
                color = AccentGreen, showPaise = false)
        }
        Spacer(Modifier.height(4.dp))
        val asOf = if (card.stale) "as of ${card.asOf} · may be outdated" else "as of ${card.asOf}"
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
    }
}

@Composable
private fun OfferRow(o: RewardOfferCategoryDto) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(offerEmoji(o.kind), fontSize = 20.sp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(o.title, color = TextPrimary, style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(2.dp))
            Text(o.description, color = TextSecondary, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelSmall,
        color = TextSecondary, letterSpacing = 1.4.sp)
}
