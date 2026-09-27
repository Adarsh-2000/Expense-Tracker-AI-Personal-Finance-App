package com.ledgernoir.tracker.ui.networth

import com.ledgernoir.tracker.ui.designsystem.*

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshContainer
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ledgernoir.tracker.ui.common.ledgerViewModelFactory
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ledgernoir.tracker.data.repository.ExpenseRepository
import com.ledgernoir.tracker.data.api.NetWorthComponents
import com.ledgernoir.tracker.data.api.ProjectionResponse
import com.ledgernoir.tracker.ui.theme.AccentGreen
import com.ledgernoir.tracker.ui.theme.AccentTeal
import com.ledgernoir.tracker.ui.theme.CanvasBlack
import com.ledgernoir.tracker.ui.theme.DisplayFamily
import com.ledgernoir.tracker.ui.theme.SurfaceMid
import com.ledgernoir.tracker.ui.theme.TextMuted
import com.ledgernoir.tracker.ui.theme.TextPrimary
import com.ledgernoir.tracker.ui.theme.TextSecondary

/** Compact INR for axis/labels: ₹3.3Cr, ₹38.5L, ₹75k. */
private fun compactInr(v: Double): String = when {
    v >= 1_00_00_000 -> "₹%.2fCr".format(v / 1_00_00_000)
    v >= 1_00_000 -> "₹%.1fL".format(v / 1_00_000)
    v >= 1_000 -> "₹%.0fk".format(v / 1_000)
    else -> "₹%.0f".format(v)
}

private data class Component(val label: String, val value: Double, val color: Color)

// ── Public dispatcher — byte-identical signature ───────────────────────────────

@Composable
fun NetWorthScreen(repository: ExpenseRepository, onBack: () -> Unit) {
    when (com.ledgernoir.tracker.ui.designsystem.LocalFormFactor.current) {
        com.ledgernoir.tracker.ui.designsystem.FormFactor.Cover -> NetWorthCover(repository, onBack)
        else -> NetWorthMain(repository, onBack)
    }
}

// ── Main (phone / large) ───────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NetWorthMain(repository: ExpenseRepository, onBack: () -> Unit) {
    val vm: NetWorthViewModel = viewModel(
        factory = ledgerViewModelFactory {
            NetWorthViewModel(it.useCases.getNetWorthFull, it.useCases.getProjection)
        },
    )
    val state by vm.uiState.collectAsStateWithLifecycle()
    val components = state.components
    val total = state.total
    val projection = state.projection
    val loading = state.loading

    val ptr = rememberPullToRefreshState()
    if (ptr.isRefreshing) {
        LaunchedEffect(Unit) { vm.refresh() }
    }
    // End the pull indicator when the silent refresh finishes. refresh() does not
    // toggle `loading`, so track the dedicated `refreshing` flag instead.
    LaunchedEffect(state.refreshing) { if (!state.refreshing) ptr.endRefresh() }
    // Re-fetch whenever the screen returns to the foreground (e.g. after adding an
    // FD or editing a balance on another screen).
    LifecycleResumeEffect(Unit) {
        vm.refresh()
        onPauseOrDispose { }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(CanvasBlack)
            .nestedScroll(ptr.nestedScrollConnection),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 20.dp),
        ) {
            ResponsiveContainer {
                val haptics = LocalHapticFeedback.current
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
                    Text("Net Worth", style = MaterialTheme.typography.headlineMedium,
                        color = TextPrimary, fontFamily = DisplayFamily)
                }
                Spacer(Modifier.height(20.dp))

                if (loading) {
                    Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = AccentTeal)
                    }
                } else {
                    // Hero
                    val animated by animateFloatAsState(total.toFloat(), tween(800), label = "nw")
                    CredCard {
                        Text("TOTAL NET WORTH", style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary, letterSpacing = 1.4.sp)
                        Spacer(Modifier.height(10.dp))
                        MoneyText(amount = animated.toDouble(), fontSize = 34.sp,
                            color = AccentGreen, showPaise = false)
                        Spacer(Modifier.height(6.dp))
                        Text("Savings + FDs + investments", color = TextSecondary,
                            style = MaterialTheme.typography.labelMedium)
                    }

                    components?.let { c ->
                        Spacer(Modifier.height(20.dp))
                        BreakdownCard(c, total)
                    }

                    projection?.let { p ->
                        Spacer(Modifier.height(20.dp))
                        ProjectionCard(p)
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
        PullToRefreshContainer(
            state = ptr,
            modifier = Modifier.align(Alignment.TopCenter),
        )
    }
}

// ── Cover (small square watch-adjacent form factor) ────────────────────────────

@Composable
private fun NetWorthCover(repository: ExpenseRepository, onBack: () -> Unit) {
    val vm: NetWorthViewModel = viewModel(
        factory = ledgerViewModelFactory {
            NetWorthViewModel(it.useCases.getNetWorthFull, it.useCases.getProjection)
        },
    )
    val state by vm.uiState.collectAsStateWithLifecycle()
    val components = state.components
    val total = state.total
    val loading = state.loading

    // Re-fetch whenever the screen returns to the foreground (e.g. after adding an
    // FD or editing a balance on another screen).
    LifecycleResumeEffect(Unit) {
        vm.refresh()
        onPauseOrDispose { }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CanvasBlack)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        ResponsiveContainer {
            // Compact back affordance
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { onBack() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = TextSecondary)
            }
            Spacer(Modifier.height(12.dp))

            if (loading) {
                Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = AccentTeal)
                }
            } else {
                HeroNumber(label = "Net worth", amount = total, change = null)
                Spacer(Modifier.height(16.dp))

                components?.let { c ->
                    BreakdownCard(c, total)
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun BreakdownCard(c: NetWorthComponents, total: Double) {
    val comps = listOf(
        Component("Indian equity & MF", c.indianEquity, Color(0xFF2DD4BF)),
        Component("Fixed deposits", c.fixedDeposits, Color(0xFFFBBF24)),
        Component("Savings accounts", c.savings, Color(0xFF60A5FA)),
        Component("US stocks", c.usStocks, Color(0xFFA78BFA)),
    ).filter { it.value > 0 }.sortedByDescending { it.value }
    val denom = if (total > 0) total else 1.0
    CredCard {
        Text("WHAT YOU OWN", style = MaterialTheme.typography.labelSmall,
            color = TextSecondary, letterSpacing = 1.4.sp)
        Spacer(Modifier.height(14.dp))
        comps.forEach { comp ->
            val pct = comp.value / denom * 100.0
            Row(verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(comp.color))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(comp.label, color = TextPrimary,
                        style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(4.dp))
                    // mini bar
                    Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp))
                        .background(SurfaceMid)) {
                        Box(Modifier.fillMaxWidth(fraction = (pct / 100.0).toFloat().coerceIn(0f, 1f))
                            .height(4.dp).clip(RoundedCornerShape(2.dp)).background(comp.color))
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(horizontalAlignment = Alignment.End) {
                    MoneyText(amount = comp.value, fontSize = 14.sp, color = TextPrimary,
                        showPaise = false)
                    Text("%.0f%%".format(pct), color = TextMuted,
                        style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun ProjectionCard(p: ProjectionResponse) {
    CredCard {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top) {
            Column {
                Text("PROJECTED IN ${p.assumptions.years} YEARS",
                    style = MaterialTheme.typography.labelSmall, color = TextSecondary,
                    letterSpacing = 1.4.sp)
                Spacer(Modifier.height(8.dp))
                MoneyText(amount = p.projectedTotal, fontSize = 28.sp, color = AccentGreen,
                    showPaise = false)
            }
            val multiple = if (p.currentTotal > 0) p.projectedTotal / p.currentTotal else 0.0
            Text("%.1f×".format(multiple), color = AccentTeal, fontWeight = FontWeight.Bold,
                fontSize = 20.sp, fontFamily = DisplayFamily)
        }
        Spacer(Modifier.height(16.dp))
        ProjectionChart(p)
        Spacer(Modifier.height(16.dp))
        val a = p.assumptions
        Text(
            "Assumes equity ${a.equityRate.toInt()}% · FD ${a.fdRate.toInt()}% · savings ${a.savingsRate}% p.a., " +
                "SIP ${compactInr(a.sip)}/mo → ${compactInr(a.sipAfter)}/mo from ${p.stepDate?.take(7) ?: "next yr"}.",
            style = MaterialTheme.typography.labelSmall, color = TextMuted,
        )
        Spacer(Modifier.height(4.dp))
        Text("Nominal (future) rupees — not inflation-adjusted.",
            style = MaterialTheme.typography.labelSmall, color = TextMuted)
    }
}

@Composable
private fun ProjectionChart(p: ProjectionResponse) {
    val pts = p.series
    if (pts.size < 2) {
        Box(Modifier.fillMaxWidth().height(40.dp))
    } else {
        val maxV = pts.maxOf { it.total }
        val minV = pts.minOf { it.total }
        val range = (maxV - minV).takeIf { it > 0 } ?: 1.0
        val anim by animateFloatAsState(1f, tween(900), label = "chart")
        Column {
            Canvas(modifier = Modifier.fillMaxWidth().height(160.dp)) {
                val w = size.width
                val h = size.height
                val n = pts.size
                fun x(i: Int) = w * i / (n - 1)
                fun y(v: Double) = h - ((v - minV) / range).toFloat() * h * 0.92f - h * 0.04f

                // Area fill
                val fill = Path().apply {
                    moveTo(0f, h)
                    pts.forEachIndexed { i, pt -> lineTo(x(i), y(pt.total)) }
                    lineTo(x(n - 1), h); close()
                }
                drawPath(fill, color = AccentTeal.copy(alpha = 0.12f))

                // Line (animated reveal)
                val visible = (anim * (n - 1)).toInt().coerceAtLeast(1)
                val line = Path().apply {
                    moveTo(x(0), y(pts[0].total))
                    for (i in 1..visible) lineTo(x(i), y(pts[i].total))
                }
                drawPath(line, color = AccentGreen, style = Stroke(width = 4f, cap = StrokeCap.Round))

                // Endpoint dots
                drawCircle(AccentTeal, radius = 5f, center = Offset(x(0), y(pts[0].total)))
                drawCircle(AccentGreen, radius = 6f, center = Offset(x(n - 1), y(pts[n - 1].total)))
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${pts.first().year} · ${compactInr(pts.first().total)}",
                    color = TextMuted, style = MaterialTheme.typography.labelSmall)
                Text("${pts.last().year} · ${compactInr(pts.last().total)}",
                    color = AccentGreen, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
