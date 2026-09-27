package com.ledgernoir.tracker.ui.insights

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ledgernoir.tracker.data.api.InsightSummaryResponse
import com.ledgernoir.tracker.ui.common.ledgerViewModelFactory
import com.ledgernoir.tracker.ui.designsystem.CredCard
import com.ledgernoir.tracker.ui.designsystem.ResponsiveContainer
import com.ledgernoir.tracker.ui.theme.AccentTeal
import com.ledgernoir.tracker.ui.theme.CanvasBlack
import com.ledgernoir.tracker.ui.theme.DisplayFamily
import com.ledgernoir.tracker.ui.theme.TextMuted
import com.ledgernoir.tracker.ui.theme.TextPrimary
import com.ledgernoir.tracker.ui.theme.TextSecondary

/**
 * AI INSIGHTS — the backend's three per-month LLM summary sections (Spending Patterns /
 * How To Save More / Money Radar), with a month stepper that can't advance past the
 * current month. Section strings are rendered verbatim (no client-side number formatting).
 * A failed/disabled/unavailable load shows the subtle placeholder rather than an error.
 */
@Composable
fun AiInsightsScreen() {
    val vm: AiInsightsViewModel = viewModel(
        factory = ledgerViewModelFactory { AiInsightsViewModel(it.useCases.getInsightSummary) },
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
            Text(
                text = "AI Insights",
                style = MaterialTheme.typography.headlineMedium,
                color = TextPrimary,
                fontFamily = DisplayFamily,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Your money, explained",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
            )
            Spacer(Modifier.height(20.dp))

            // Month stepper
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { vm.prevMonth() }) {
                    Icon(
                        Icons.Filled.KeyboardArrowLeft,
                        contentDescription = "Previous month",
                        tint = AccentTeal,
                    )
                }
                Text(
                    text = MonthStepper.label(state.month),
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary,
                    fontFamily = DisplayFamily,
                )
                IconButton(onClick = { vm.nextMonth() }, enabled = state.canGoForward) {
                    Icon(
                        Icons.Filled.KeyboardArrowRight,
                        contentDescription = "Next month",
                        tint = if (state.canGoForward) AccentTeal else TextMuted,
                    )
                }
            }
            Spacer(Modifier.height(16.dp))

            val summary = state.summary
            when {
                state.loading -> {
                    Box(
                        Modifier.fillMaxWidth().padding(40.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(color = AccentTeal)
                    }
                }
                summary == null || !summary.enabled || !summary.available -> {
                    PlaceholderCard(
                        title = if (summary?.enabled == false) "AI insights aren't turned on"
                                else "Couldn't generate insights right now",
                        body = "Your monthly Spending, Save-More and Money-Radar summaries will appear here.",
                    )
                }
                else -> {
                    SectionCard("📊", "Spending Patterns", summary.spendingPatterns)
                    SectionCard("💸", "How To Save More", summary.howToSave)
                    SectionCard("📡", "Money Radar", summary.moneyRadar)
                    summary.generatedAt?.let {
                        Text(
                            text = "Generated $it",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMuted,
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun SectionCard(emoji: String, title: String, body: String) {
    if (body.isBlank()) return  // omit empty sections
    CredCard {
        Text(
            text = "$emoji  $title",
            style = MaterialTheme.typography.titleSmall,
            color = TextPrimary,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
        )
    }
    Spacer(Modifier.height(12.dp))
}

@Composable
private fun PlaceholderCard(title: String, body: String) {
    CredCard {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("✨", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = TextPrimary,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                textAlign = TextAlign.Center,
            )
        }
    }
}
