package com.ledgernoir.tracker.ui.budget

import com.ledgernoir.tracker.ui.designsystem.*

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ledgernoir.tracker.data.api.BudgetCategoryStatus
import com.ledgernoir.tracker.data.api.BudgetResponse
import com.ledgernoir.tracker.data.repository.ExpenseRepository
import com.ledgernoir.tracker.ui.theme.AccentGreen
import com.ledgernoir.tracker.ui.theme.AccentTeal
import com.ledgernoir.tracker.ui.theme.CanvasBlack
import com.ledgernoir.tracker.ui.theme.CreditGreen
import com.ledgernoir.tracker.ui.theme.DebitRose
import com.ledgernoir.tracker.ui.theme.SurfaceMid
import com.ledgernoir.tracker.ui.theme.TextPrimary
import com.ledgernoir.tracker.ui.theme.TextSecondary

// ── Public dispatcher — byte-identical signature ───────────────────────────────

@Composable
fun BudgetScreen(
    repository: ExpenseRepository,
    scrollAnchor: String? = null,
    onAnchorConsumed: () -> Unit = {},
) {
    when (com.ledgernoir.tracker.ui.designsystem.LocalFormFactor.current) {
        com.ledgernoir.tracker.ui.designsystem.FormFactor.Cover ->
            BudgetCover(repository, scrollAnchor, onAnchorConsumed)
        else -> BudgetMain(repository, scrollAnchor, onAnchorConsumed)
    }
}

// ── Main (phone / large) ───────────────────────────────────────────────────────

@Composable
fun BudgetMain(
    repository: ExpenseRepository,
    scrollAnchor: String? = null,
    onAnchorConsumed: () -> Unit = {},
) {
    val vm: BudgetViewModel = viewModel(
        factory = ledgerViewModelFactory {
            BudgetViewModel(
                it.useCases.getCategories, it.useCases.getBudget,
                it.useCases.setIncome, it.useCases.assignBudget,
            )
        },
    )
    val state by vm.uiState.collectAsStateWithLifecycle()
    val currentMonth = vm.currentMonth
    var showSetDialog by remember { mutableStateOf(false) }
    var editCat by remember { mutableStateOf<BudgetCategoryStatus?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CanvasBlack)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 20.dp),
    ) {
        ResponsiveContainer {
            Text(
                text  = "Budget",
                style = MaterialTheme.typography.headlineMedium,
                color = TextPrimary,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = monthHeading(currentMonth),
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
            )
            Spacer(Modifier.height(20.dp))

            BudgetBody(
                state = state,
                vm = vm,
                showSetDialog = showSetDialog,
                editCat = editCat,
                onShowSetDialog = { showSetDialog = it },
                onEditCat = { editCat = it },
                scrollAnchor = scrollAnchor,
                onAnchorConsumed = onAnchorConsumed,
            )
            Spacer(Modifier.height(8.dp))
        }
    }
}

// ── Cover (small square watch-adjacent form factor) ────────────────────────────

@Composable
private fun BudgetCover(
    repository: ExpenseRepository,
    scrollAnchor: String? = null,
    onAnchorConsumed: () -> Unit = {},
) {
    val vm: BudgetViewModel = viewModel(
        factory = ledgerViewModelFactory {
            BudgetViewModel(
                it.useCases.getCategories, it.useCases.getBudget,
                it.useCases.setIncome, it.useCases.assignBudget,
            )
        },
    )
    val state by vm.uiState.collectAsStateWithLifecycle()
    var showSetDialog by remember { mutableStateOf(false) }
    var editCat by remember { mutableStateOf<BudgetCategoryStatus?>(null) }

    // Hero: total spent across all category caps vs. total assigned.
    val budget = state.budget
    val totalSpent = budget?.categories?.sumOf { it.spent } ?: 0.0
    val totalAssigned = budget?.categories?.sumOf { it.assigned } ?: 0.0
    val changeLabel = if (totalAssigned > 0.0) {
        "of ${formatWhole(totalAssigned)} budgeted"
    } else {
        null
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CanvasBlack)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 20.dp),
    ) {
        ResponsiveContainer {
            HeroNumber(
                label = "Spent",
                amount = totalSpent,
                change = changeLabel,
                changePositive = totalSpent <= totalAssigned || totalAssigned == 0.0,
            )
            Spacer(Modifier.height(20.dp))

            BudgetBody(
                state = state,
                vm = vm,
                showSetDialog = showSetDialog,
                editCat = editCat,
                onShowSetDialog = { showSetDialog = it },
                onEditCat = { editCat = it },
                scrollAnchor = scrollAnchor,
                onAnchorConsumed = onAnchorConsumed,
            )
            Spacer(Modifier.height(8.dp))
        }
    }
}

// ── Shared body — used by both Main and Cover ──────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BudgetBody(
    state: BudgetUiState,
    vm: BudgetViewModel,
    showSetDialog: Boolean,
    editCat: BudgetCategoryStatus?,
    onShowSetDialog: (Boolean) -> Unit,
    onEditCat: (BudgetCategoryStatus?) -> Unit,
    scrollAnchor: String? = null,
    onAnchorConsumed: () -> Unit = {},
) {
    val budget = state.budget
    val categoryNames = state.categoryNames
    val loading = state.loading
    val error = state.error
    val allCategories = state.allCategories

    // Notification deep-link: the anchor is the category name. Resolve each row's
    // display name the same way the row does, then scroll the match into view and
    // pulse it.
    fun resolvedName(cat: BudgetCategoryStatus): String =
        cat.category.takeIf { it.isNotBlank() }
            ?: categoryNames[cat.categoryId] ?: "Category ${cat.categoryId}"

    val bringReq = remember { BringIntoViewRequester() }
    var highlightCategory by remember { mutableStateOf<String?>(null) }
    val categories = budget?.categories.orEmpty()
    LaunchedEffect(scrollAnchor, categories) {
        val target = scrollAnchor
        if (target != null && categories.any { it.category == target || resolvedName(it) == target }) {
            bringReq.bringIntoView()
            highlightCategory = target
            onAnchorConsumed()
        }
    }

    if (loading) {
        Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = AccentTeal)
        }
    } else if (error != null) {
        BudgetEmpty(message = "Couldn't load budget: $error")
    } else {
        val b = budget
        if (b == null) {
            BudgetEmpty(message = "No budget data for this month yet.")
        } else {
            // Hero — Ready to assign.
            ReadyToAssignCard(b.readyToAssign)
            Spacer(Modifier.height(20.dp))

            // Income setter.
            IncomeSetterCard(
                onSet = { amount -> vm.setIncome(amount) },
            )
            Spacer(Modifier.height(20.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                BudgetSectionLabel("Category caps")
                Box(
                    modifier = Modifier
                        .background(SurfaceMid, RoundedCornerShape(12.dp))
                        .clickable { onShowSetDialog(true) }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Text("+ Set a budget", color = AccentTeal,
                        style = MaterialTheme.typography.labelLarge)
                }
            }
            Spacer(Modifier.height(12.dp))
            if (b.categories.isNotEmpty()) {
                b.categories.forEachIndexed { i, cat ->
                    val name = resolvedName(cat)
                    val isAnchor = cat.category == scrollAnchor || name == scrollAnchor
                    val highlighted = cat.category == highlightCategory || name == highlightCategory
                    Box(
                        modifier = if (isAnchor) {
                            Modifier.bringIntoViewRequester(bringReq)
                        } else {
                            Modifier
                        },
                    ) {
                        BudgetCategoryCard(
                            cat,
                            name,
                            onEdit = { onEditCat(cat) },
                            highlighted = highlighted,
                        )
                    }
                    if (i < b.categories.size - 1) Spacer(Modifier.height(12.dp))
                }
            } else {
                BudgetEmpty(message = "No caps yet — tap “+ Set a budget” to add one.")
            }
        }
    }

    // Set / edit a category cap (dialog renders outside scroll, inside composable scope).
    if (showSetDialog || editCat != null) {
        val editing = editCat
        SetCapDialog(
            categories = allCategories,
            preselect = editing?.categoryId,
            preselectName = editing?.let { it.category.takeIf { n -> n.isNotBlank() } ?: categoryNames[it.categoryId] },
            initialAmount = editing?.assigned,
            onDismiss = { onShowSetDialog(false); onEditCat(null) },
            onSave = { categoryId, amount ->
                vm.assign(categoryId, amount)
                onShowSetDialog(false); onEditCat(null)
            },
        )
    }
}

// ── Private composables ────────────────────────────────────────────────────────

@Composable
private fun SetCapDialog(
    categories: List<com.ledgernoir.tracker.data.api.CategoryDto>,
    preselect: Int?,
    preselectName: String?,
    initialAmount: Double?,
    onDismiss: () -> Unit,
    onSave: (Int, Double) -> Unit,
) {
    var selectedId by remember { mutableStateOf(preselect) }
    var amount by remember { mutableStateOf(initialAmount?.let { "%.0f".format(it) } ?: "") }
    val amt = amount.replace(",", "").toDoubleOrNull()
    val valid = selectedId != null && amt != null
    // Hide money-movement categories that aren't really "spend to budget".
    val budgetable = categories.filter {
        it.name.lowercase() !in setOf("transfers", "investments", "uncategorized")
    }
    androidx.compose.material3.AlertDialog(
        containerColor = com.ledgernoir.tracker.ui.theme.SurfaceDark,
        onDismissRequest = onDismiss,
        title = { Text(if (preselect != null) "Edit ${preselectName ?: "cap"}" else "Set a budget", color = TextPrimary) },
        text = {
            Column {
                if (preselect == null) {
                    Text("Category", color = TextSecondary, style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.height(8.dp))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 200.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        budgetable.forEach { c ->
                            val sel = selectedId == c.id
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(
                                        if (sel) AccentTeal.copy(alpha = 0.15f) else Color.Transparent,
                                        RoundedCornerShape(10.dp))
                                    .clickable { selectedId = c.id }
                                    .padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                CategoryIconChip(c.name, size = 28.dp)
                                Spacer(Modifier.width(10.dp))
                                Text(c.name.replaceFirstChar { it.uppercase() },
                                    color = if (sel) AccentTeal else TextPrimary,
                                    style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }
                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = it.filter { c -> c.isDigit() || c == '.' } },
                    label = { Text("Monthly cap (₹)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AccentTeal, focusedLabelColor = AccentTeal,
                        cursorColor = AccentTeal, focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(
                onClick = { if (valid) onSave(selectedId!!, amt!!) },
                enabled = valid,
            ) { Text("Save", color = if (valid) AccentTeal else TextSecondary) }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text("Cancel", color = TextSecondary)
            }
        },
    )
}

@Composable
private fun BudgetSectionLabel(text: String, color: Color = TextSecondary) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = color,
        letterSpacing = 1.4.sp,
    )
}

@Composable
private fun ReadyToAssignCard(amount: Double) {
    val animated by animateFloatAsState(
        targetValue = amount.toFloat(),
        animationSpec = tween(800),
        label = "readyCountUp",
    )
    CredCard(padding = 24) {
        BudgetSectionLabel("Ready to assign")
        Spacer(Modifier.height(12.dp))
        MoneyText(
            amount = animated.toDouble(),
            fontSize = 40.sp,
            color = AccentGreen,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun IncomeSetterCard(onSet: (Double) -> Unit) {
    var income by remember { mutableStateOf("") }
    val haptic = LocalHapticFeedback.current
    val fieldColors = TextFieldDefaults.colors(
        focusedContainerColor   = SurfaceMid,
        unfocusedContainerColor = SurfaceMid,
        focusedIndicatorColor   = AccentTeal,
        unfocusedIndicatorColor = Color.Transparent,
        cursorColor             = AccentTeal,
        focusedTextColor        = TextPrimary,
        unfocusedTextColor      = TextPrimary,
    )
    CredCard {
        BudgetSectionLabel("Set income")
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value         = income,
                onValueChange = { income = it },
                placeholder   = { Text("Monthly income (₹)", color = TextSecondary) },
                singleLine    = true,
                shape         = RoundedCornerShape(14.dp),
                colors        = fieldColors,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier      = Modifier.weight(1f),
            )
            Spacer(Modifier.width(12.dp))
            Button(
                onClick = {
                    val amt = income.toDoubleOrNull()
                    if (amt != null) {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onSet(amt)
                        income = ""
                    }
                },
                enabled = income.toDoubleOrNull() != null,
                colors  = ButtonDefaults.buttonColors(
                    containerColor = AccentTeal,
                    contentColor = CanvasBlack,
                ),
                shape   = RoundedCornerShape(14.dp),
            ) {
                Text("Set", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun BudgetCategoryCard(
    cat: BudgetCategoryStatus,
    categoryName: String,
    onEdit: () -> Unit = {},
    highlighted: Boolean = false,
) {
    val statusColor = when (cat.status) {
        "exceeded" -> DebitRose
        "warning"  -> Color(0xFFFBBF24)   // amber
        else       -> CreditGreen
    }
    // Pacing message: over cap, on pace to exceed, or on track.
    val paceLine: Pair<String, Color>? = when {
        cat.status == "exceeded" ->
            "Over by ${formatWhole(cat.spent - cat.assigned)}" to DebitRose
        cat.paceStatus == "at_risk" ->
            "On pace to exceed · projected ${formatWhole(cat.projected)}" to Color(0xFFF59E0B)
        else -> null
    }
    val fraction = if (cat.assigned > 0.0) {
        (cat.spent / cat.assigned).toFloat().coerceIn(0f, 1f)
    } else {
        if (cat.spent > 0.0) 1f else 0f
    }
    val animFraction by animateFloatAsState(
        targetValue = fraction,
        animationSpec = tween(700),
        label = "budgetBar",
    )

    // Brief teal border pulse when this row is the notification target; fades off
    // ~1200ms after the highlight begins (mirrors the Bills/Rewards pattern).
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
        label = "budgetRowPulse",
    )

    CredCard(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .border(2.dp, borderColor, RoundedCornerShape(16.dp))
            .clickable { onEdit() },
        padding = 16,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CategoryIconChip(categoryName, size = 40.dp)
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text  = categoryName.replaceFirstChar { it.uppercase() },
                    style = MaterialTheme.typography.bodyLarge,
                    color = TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text  = "${formatMoney(cat.spent)} of ${formatMoney(cat.assigned)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary,
                )
            }
            Spacer(Modifier.width(12.dp))
            MoneyText(
                amount = cat.remaining,
                fontSize = 15.sp,
                color = statusColor,
            )
        }
        Spacer(Modifier.height(12.dp))
        // Status-colored rounded progress bar.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .background(SurfaceMid, RoundedCornerShape(4.dp)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(animFraction)
                    .height(8.dp)
                    .background(statusColor, RoundedCornerShape(4.dp)),
            )
        }
        paceLine?.let { (msg, color) ->
            Spacer(Modifier.height(8.dp))
            Text(msg, style = MaterialTheme.typography.labelSmall, color = color,
                fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun BudgetEmpty(message: String) {
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
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

private fun monthHeading(month: String): String = runCatching {
    val ym = java.time.YearMonth.parse(month)
    "${ym.month.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.getDefault())} ${ym.year}"
}.getOrElse { month }
