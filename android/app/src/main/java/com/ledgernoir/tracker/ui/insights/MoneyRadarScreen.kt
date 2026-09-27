package com.ledgernoir.tracker.ui.insights

import androidx.compose.animation.animateColorAsState
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ledgernoir.tracker.data.api.FindingDto
import com.ledgernoir.tracker.data.repository.ExpenseRepository
import com.ledgernoir.tracker.ui.common.ledgerViewModelFactory
import com.ledgernoir.tracker.ui.designsystem.CredCard
import com.ledgernoir.tracker.ui.designsystem.MoneyText
import com.ledgernoir.tracker.ui.theme.AccentGreen
import com.ledgernoir.tracker.ui.theme.AccentTeal
import com.ledgernoir.tracker.ui.theme.CanvasBlack
import com.ledgernoir.tracker.ui.theme.SurfaceMid
import com.ledgernoir.tracker.ui.theme.TextMuted
import com.ledgernoir.tracker.ui.theme.TextPrimary
import com.ledgernoir.tracker.ui.theme.TextSecondary
import kotlinx.coroutines.launch

private fun severityColor(severity: String): Color = when (severity) {
    "security" -> Color(0xFFEF4444)
    "warning" -> Color(0xFFFBBF24)
    "action" -> AccentTeal
    "positive" -> AccentGreen
    else -> TextSecondary
}

private fun severityLabel(severity: String): String = when (severity) {
    "security" -> "Needs attention"
    "warning" -> "Worth a look"
    "action" -> "New commitments"
    "positive" -> "Money owed back"
    else -> "Good to know"
}

private val SEVERITY_ORDER = listOf("security", "action", "warning", "positive", "info")

/** ISO occurred_at → "28 Feb 2023 · 6:25 PM"; null if it can't be parsed. */
private fun formatFindingWhen(iso: String): String? = runCatching {
    val dt = java.time.LocalDateTime.parse(iso.take(19))
    dt.format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy · h:mm a", java.util.Locale.getDefault()))
}.getOrNull()

/**
 * MONEY RADAR — charges & commitments the app surfaces nowhere else: auto-pay
 * mandates, forex markup, hidden card costs, duplicates, refunds, declines.
 *
 * @param onDrillDown (merchant, categoryId) — opens the existing transaction list.
 * @param onBack pops back to the previous screen (matches other detail screens).
 */
@Composable
fun MoneyRadarScreen(
    repository: ExpenseRepository,
    onDrillDown: (merchant: String?, categoryId: Int?) -> Unit = { _, _ -> },
    onBack: () -> Unit = {},
    scrollAnchor: String? = null,
    onAnchorConsumed: () -> Unit = {},
) {
    when (com.ledgernoir.tracker.ui.designsystem.LocalFormFactor.current) {
        com.ledgernoir.tracker.ui.designsystem.FormFactor.Cover -> MoneyRadarCover(
            repository, onDrillDown, onBack, scrollAnchor, onAnchorConsumed,
        )
        else -> MoneyRadarMain(
            repository, onDrillDown, onBack, scrollAnchor, onAnchorConsumed,
        )
    }
}

@Composable
fun MoneyRadarMain(
    repository: ExpenseRepository,
    onDrillDown: (merchant: String?, categoryId: Int?) -> Unit = { _, _ -> },
    onBack: () -> Unit = {},
    scrollAnchor: String? = null,
    onAnchorConsumed: () -> Unit = {},
) {
    val vm: MoneyRadarViewModel = viewModel(
        factory = ledgerViewModelFactory {
            MoneyRadarViewModel(it.useCases.getMoneyRadar, it.useCases.markRadarSeen,
                it.useCases.dismissFinding)
        },
    )
    val state by vm.uiState.collectAsStateWithLifecycle()
    val grouped = state.findings.groupBy { it.severity }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // Flat LazyColumn index of each severity's section header, so tapping a summary
    // pill can scroll to it. Layout: item 0 = header/summary, then per present
    // severity in SEVERITY_ORDER: 1 header item + N finding items.
    val headerIndexBySeverity = remember(state.findings) {
        val map = LinkedHashMap<String, Int>()
        var idx = 1
        SEVERITY_ORDER.forEach { sev ->
            val count = grouped[sev]?.size ?: 0
            if (count > 0) {
                map[sev] = idx
                idx += 1 + count
            }
        }
        map
    }

    // Card that should briefly highlight (the finding deep-linked from a notification).
    var highlightKey by remember { mutableStateOf<String?>(null) }

    // Scroll to the finding whose dedup key matches the incoming anchor. Uses the SAME
    // flattening as the pill-scroll: each severity contributes its header index, then the
    // finding sits at header + 1 + (its position within that severity's rows).
    LaunchedEffect(scrollAnchor, state.findings) {
        if (scrollAnchor != null && state.findings.isNotEmpty()) {
            val match = state.findings.firstOrNull { it.dedupKey == scrollAnchor }
            if (match != null) {
                val header = headerIndexBySeverity[match.severity]
                val pos = grouped[match.severity].orEmpty().indexOfFirst { it.id == match.id }
                if (header != null && pos >= 0) {
                    listState.animateScrollToItem(header + 1 + pos)
                }
                highlightKey = match.dedupKey
                onAnchorConsumed()
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().background(CanvasBlack),
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp, 20.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onBack() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = TextSecondary)
                    }
                    Spacer(Modifier.width(4.dp))
                    Text("Money Radar", style = MaterialTheme.typography.headlineMedium, color = TextPrimary)
                }
                Spacer(Modifier.height(4.dp))
                Text("Charges & commitments you might've missed.",
                    style = MaterialTheme.typography.bodyMedium, color = TextMuted)
                Spacer(Modifier.height(12.dp))
                if (!state.loading && state.findings.isNotEmpty()) {
                    SeverityCountRow(
                        findings = state.findings,
                        onSeverityClick = { sev ->
                            headerIndexBySeverity[sev]?.let { target ->
                                scope.launch { listState.animateScrollToItem(target) }
                            }
                        },
                    )
                    Spacer(Modifier.height(8.dp))
                }
                Spacer(Modifier.height(8.dp))
            }

            moneyRadarFindingsItems(state = state, grouped = grouped, vm = vm,
                onDrillDown = onDrillDown, highlightKey = highlightKey)

            item { Spacer(Modifier.height(8.dp)) }
        }
    }
}

@Composable
private fun MoneyRadarCover(
    repository: ExpenseRepository,
    onDrillDown: (merchant: String?, categoryId: Int?) -> Unit = { _, _ -> },
    onBack: () -> Unit = {},
    scrollAnchor: String? = null,
    onAnchorConsumed: () -> Unit = {},
) {
    val vm: MoneyRadarViewModel = viewModel(
        factory = ledgerViewModelFactory {
            MoneyRadarViewModel(it.useCases.getMoneyRadar, it.useCases.markRadarSeen,
                it.useCases.dismissFinding)
        },
    )
    val state by vm.uiState.collectAsStateWithLifecycle()
    val grouped = state.findings.groupBy { it.severity }
    val listState = rememberLazyListState()

    // Mirror MoneyRadarMain: item 0 = header, then per present severity 1 header + N findings.
    val headerIndexBySeverity = remember(state.findings) {
        val map = LinkedHashMap<String, Int>()
        var idx = 1
        SEVERITY_ORDER.forEach { sev ->
            val count = grouped[sev]?.size ?: 0
            if (count > 0) {
                map[sev] = idx
                idx += 1 + count
            }
        }
        map
    }

    var highlightKey by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(scrollAnchor, state.findings) {
        if (scrollAnchor != null && state.findings.isNotEmpty()) {
            val match = state.findings.firstOrNull { it.dedupKey == scrollAnchor }
            if (match != null) {
                val header = headerIndexBySeverity[match.severity]
                val pos = grouped[match.severity].orEmpty().indexOfFirst { it.id == match.id }
                if (header != null && pos >= 0) {
                    listState.animateScrollToItem(header + 1 + pos)
                }
                highlightKey = match.dedupKey
                onAnchorConsumed()
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().background(CanvasBlack),
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp, 12.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { onBack() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = TextSecondary)
                    }
                    Spacer(Modifier.width(4.dp))
                    Text("Money Radar", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                }
                Spacer(Modifier.height(8.dp))
            }

            moneyRadarFindingsItems(state = state, grouped = grouped, vm = vm,
                onDrillDown = onDrillDown, highlightKey = highlightKey)

            item { Spacer(Modifier.height(8.dp)) }
        }
    }
}

/**
 * Shared [LazyListScope] extension that renders the findings list body (loading
 * spinner, empty state, or severity-grouped finding cards). Both [MoneyRadarMain]
 * and [MoneyRadarCover] call this so the row logic cannot diverge.
 */
private fun LazyListScope.moneyRadarFindingsItems(
    state: MoneyRadarUiState,
    grouped: Map<String, List<FindingDto>>,
    vm: MoneyRadarViewModel,
    onDrillDown: (merchant: String?, categoryId: Int?) -> Unit,
    highlightKey: String? = null,
) {
    when {
        state.loading -> item {
            Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = AccentTeal)
            }
        }
        state.findings.isEmpty() -> item {
            CredCard {
                Text(state.error ?: "Nothing on your radar — you're all clear. ✨",
                    style = MaterialTheme.typography.bodyMedium, color = TextSecondary,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp))
            }
        }
        else -> SEVERITY_ORDER.forEach { sev ->
            val rows = grouped[sev].orEmpty()
            if (rows.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(4.dp))
                    Text(severityLabel(sev).uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = severityColor(sev), letterSpacing = 1.4.sp)
                    Spacer(Modifier.height(10.dp))
                }
                items(rows, key = { it.id }) { finding ->
                    FindingCard(
                        finding = finding,
                        highlighted = highlightKey != null && finding.dedupKey == highlightKey,
                        onDismiss = { vm.dismiss(finding.id) },
                        onClick = {
                            // Pass the full merchant; the backend's token-OR match
                            // (merchant_any) handles payee-string differences between
                            // a finding name and its underlying debits.
                            val q = finding.query
                            if (q != null) onDrillDown(q.merchant, q.categoryId)
                        },
                    )
                    Spacer(Modifier.height(12.dp))
                }
            }
        }
    }
}

@Composable
private fun SeverityCountRow(findings: List<FindingDto>, onSeverityClick: (String) -> Unit) {
    val counts = findings.groupingBy { it.severity }.eachCount()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SEVERITY_ORDER.forEach { sev ->
            val count = counts[sev] ?: 0
            if (count > 0) {
                val accent = severityColor(sev)
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(accent.copy(alpha = 0.15f))
                        .clickable { onSeverityClick(sev) }
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    Text(
                        text = "$count ${severityLabel(sev).lowercase()}",
                        style = MaterialTheme.typography.labelSmall,
                        color = accent,
                    )
                }
            }
        }
    }
}

@Composable
private fun FindingCard(
    finding: FindingDto,
    onDismiss: () -> Unit,
    onClick: () -> Unit,
    highlighted: Boolean = false,
) {
    val accent = severityColor(finding.severity)

    // Brief teal flash when this card is the notification's deep-link target. The
    // highlight switches on as soon as `highlighted` becomes true, then fades after
    // ~1.2s; animateColorAsState smooths the border between the two states.
    var showHighlight by remember(finding.id) { mutableStateOf(false) }
    LaunchedEffect(highlighted, finding.id) {
        if (highlighted) {
            showHighlight = true
            kotlinx.coroutines.delay(1200)
            showHighlight = false
        } else {
            showHighlight = false
        }
    }
    val borderColor by animateColorAsState(
        targetValue = if (showHighlight) AccentTeal else Color.Transparent,
        label = "findingHighlight",
    )

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .border(1.5.dp, borderColor, RoundedCornerShape(16.dp)),
    ) {
    CredCard {
        Row(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.size(10.dp).clip(CircleShape).background(accent),
            )
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(finding.title, style = MaterialTheme.typography.bodyLarge, color = TextPrimary,
                    fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(finding.detail, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                val whenText = formatFindingWhen(finding.occurredAt)
                if (whenText != null) {
                    Text(whenText, style = MaterialTheme.typography.labelSmall, color = TextMuted)
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(horizontalAlignment = Alignment.End) {
                if (finding.amount != null) {
                    MoneyText(amount = finding.amount, fontSize = 16.sp, color = TextPrimary, showPaise = false)
                }
                Spacer(Modifier.height(6.dp))
                Box(
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(SurfaceMid)
                        .clickable(onClick = onDismiss).padding(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    Text("Dismiss", style = MaterialTheme.typography.labelSmall, color = TextMuted)
                }
            }
        }
    }
    }
}
