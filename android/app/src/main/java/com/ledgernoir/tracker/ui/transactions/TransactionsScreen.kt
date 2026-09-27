package com.ledgernoir.tracker.ui.transactions

import com.ledgernoir.tracker.ui.designsystem.*

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ledgernoir.tracker.ui.common.ledgerViewModelFactory
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ledgernoir.tracker.data.repository.ExpenseRepository
import com.ledgernoir.tracker.data.api.ManualTxnPayload
import com.ledgernoir.tracker.data.api.TransactionDto
import com.ledgernoir.tracker.ui.theme.AccentTeal
import com.ledgernoir.tracker.ui.theme.CanvasBlack
import com.ledgernoir.tracker.ui.theme.CreditGreen
import com.ledgernoir.tracker.ui.theme.DebitRose
import com.ledgernoir.tracker.ui.theme.SurfaceDark
import com.ledgernoir.tracker.ui.theme.SurfaceMid
import com.ledgernoir.tracker.ui.theme.TextPrimary
import com.ledgernoir.tracker.ui.theme.TextSecondary
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val ISO_DATE: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE
private val DAY_HEADER_FMT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("EEE, dd MMM yyyy", Locale.getDefault())
private val TIME_FMT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault())

private fun millisToLocalDate(millis: Long): LocalDate =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate()

/**
 * Tolerantly parses an "occurred_at" string into a [LocalDateTime]. Handles forms
 * like "yyyy-MM-dd", "yyyy-MM-dd'T'HH:mm", "...:ss" and fractional seconds by
 * trimming to the first 19 chars before parsing; falls back to midnight on the date.
 */
private fun parseOccurred(raw: String): LocalDateTime? {
    return try {
        when {
            raw.length >= 19 -> LocalDateTime.parse(raw.substring(0, 19))
            raw.length >= 16 -> LocalDateTime.parse(raw.substring(0, 16))
            raw.length >= 10 -> LocalDate.parse(raw.substring(0, 10)).atStartOfDay()
            else -> null
        }
    } catch (e: Exception) {
        try {
            LocalDate.parse(raw.take(10)).atStartOfDay()
        } catch (e2: Exception) {
            null
        }
    }
}

/** Human-friendly day-group header: "Today" / "Yesterday" / "EEE, dd MMM yyyy". */
private fun dayLabel(date: LocalDate): String {
    val today = LocalDate.now()
    return when (date) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> date.format(DAY_HEADER_FMT)
    }
}

/** A flattened list entry — either a day header or a transaction row. */
sealed interface TxnListItem {
    data class Header(val label: String, val dayTotal: Double) : TxnListItem
    data class Txn(val txn: TransactionDto, val categoryName: String?) : TxnListItem
}

/**
 * Builds the grouped, flattened display list: transactions sorted newest-first,
 * grouped by calendar day, each group preceded by a header carrying that day's
 * total debit spend.
 */
internal fun buildGroupedItems(
    transactions: List<TransactionDto>,
    categoryNames: Map<Int, String>,
): List<TxnListItem> {
    val byDay = transactions
        .mapNotNull { t -> parseOccurred(t.occurredAt)?.let { it to t } }
        .sortedByDescending { it.first }
        .groupBy { it.first.toLocalDate() }

    val out = ArrayList<TxnListItem>()
    byDay.entries
        .sortedByDescending { it.key }
        .forEach { (day, entries) ->
            val dayTotal = entries
                .filter { it.second.direction != "credit" }
                .sumOf { it.second.amount }
            out.add(TxnListItem.Header(dayLabel(day), dayTotal))
            entries.forEach { (_, txn) ->
                out.add(TxnListItem.Txn(txn, txn.categoryId?.let { categoryNames[it] }))
            }
        }
    return out
}

@Composable
fun TransactionsScreen(repository: ExpenseRepository) {
    when (LocalFormFactor.current) {
        FormFactor.Cover -> TransactionsCover(repository)
        else -> TransactionsMain(repository)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionsMain(repository: ExpenseRepository) {
    val vm: TransactionsViewModel = viewModel(
        factory = ledgerViewModelFactory {
            TransactionsViewModel(
                it.useCases.getCategories, it.useCases.getTransactions,
                it.useCases.searchTransactions, it.useCases.getSpentRange,
            )
        },
    )
    val state by vm.uiState.collectAsStateWithLifecycle()

    val loading = state.loading
    val query = state.query
    val fromDate = state.fromDate
    val toDate = state.toDate
    val rangeSpent = state.rangeSpent
    val filterDirection = state.filterDirection
    val items = state.items

    var showSheet    by remember { mutableStateOf(false) }
    var detailTxn    by remember { mutableStateOf<TxnListItem.Txn?>(null) }
    val sheetState   = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val detailSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope        = rememberCoroutineScope()

    var showFromPicker by remember { mutableStateOf(false) }
    var showToPicker   by remember { mutableStateOf(false) }

    val haptic = LocalHapticFeedback.current

    Scaffold(
        containerColor = CanvasBlack,
        floatingActionButton = {
            FloatingActionButton(
                onClick          = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    showSheet = true
                },
                containerColor   = AccentTeal,
                contentColor     = CanvasBlack,
                shape            = RoundedCornerShape(18.dp),
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add transaction")
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter,
        ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 520.dp)
                .padding(horizontal = 16.dp, vertical = 20.dp),
        ) {
            Text(
                text  = "Transactions",
                style = MaterialTheme.typography.headlineMedium,
                color = TextPrimary,
            )
            Spacer(Modifier.height(16.dp))

            // All / Spends / Income segmented toggle.
            val segIndex = when (filterDirection) { "debit" -> 1; "credit" -> 2; else -> 0 }
            SegmentedFilter(
                options = listOf("All", "Spends", "Income"),
                selectedIndex = segIndex,
                onSelect = { i ->
                    vm.selectDirection(when (i) { 1 -> "debit"; 2 -> "credit"; else -> null })
                },
            )
            Spacer(Modifier.height(14.dp))

            val fieldColors = TextFieldDefaults.colors(
                focusedContainerColor   = SurfaceMid,
                unfocusedContainerColor = SurfaceMid,
                focusedIndicatorColor   = AccentTeal,
                unfocusedIndicatorColor = Color.Transparent,
                cursorColor             = AccentTeal,
                focusedTextColor        = TextPrimary,
                unfocusedTextColor      = TextPrimary,
            )
            OutlinedTextField(
                value         = query,
                onValueChange = { vm.onQueryChange(it) },
                placeholder   = { Text("Search merchants…", color = TextSecondary) },
                singleLine    = true,
                shape         = RoundedCornerShape(14.dp),
                colors        = fieldColors,
                modifier      = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))

            // Date-range filter row: From / To clickable pill fields.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                DateField(
                    label = fromDate?.format(ISO_DATE) ?: "From",
                    onClick = { showFromPicker = true },
                    modifier = Modifier.weight(1f),
                )
                DateField(
                    label = toDate?.format(ISO_DATE) ?: "To",
                    onClick = { showToPicker = true },
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Button(
                    onClick  = { vm.reload() },
                    colors   = ButtonDefaults.buttonColors(
                        containerColor = AccentTeal,
                        contentColor = CanvasBlack,
                    ),
                    shape    = RoundedCornerShape(14.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Apply", fontWeight = FontWeight.SemiBold)
                }
                Button(
                    onClick  = { vm.clearFilters() },
                    colors   = ButtonDefaults.buttonColors(
                        containerColor = SurfaceMid,
                        contentColor = TextPrimary,
                    ),
                    shape    = RoundedCornerShape(14.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Clear")
                }
            }

            rangeSpent?.let { spentValue ->
                Spacer(Modifier.height(14.dp))
                CredCard(padding = 16) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text     = "Spent ${fromDate?.format(ISO_DATE)} → ${toDate?.format(ISO_DATE)}",
                            style    = MaterialTheme.typography.labelMedium,
                            color    = TextSecondary,
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(12.dp))
                        MoneyText(amount = spentValue, fontSize = 18.sp, color = AccentTeal)
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            if (loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = AccentTeal)
                }
            } else if (items.isEmpty()) {
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
                            text  = "No transactions yet.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary,
                        )
                    }
                }
            } else {
                // Gentle one-time fade-in for the whole list on load.
                val listAlpha by animateFloatAsState(
                    targetValue = 1f,
                    animationSpec = tween(450),
                    label = "listFade",
                )
                LazyColumn(
                    modifier = Modifier.alpha(listAlpha),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    transactionItems(items) { detailTxn = it }
                }
            }
        }
        }

        if (showFromPicker) {
            val state = rememberDatePickerState(
                initialSelectedDateMillis = fromDate
                    ?.atStartOfDay(ZoneId.systemDefault())?.toInstant()?.toEpochMilli(),
            )
            DatePickerDialog(
                onDismissRequest = { showFromPicker = false },
                confirmButton = {
                    TextButton(onClick = {
                        state.selectedDateMillis?.let { vm.onFromDateSelected(millisToLocalDate(it)) }
                        showFromPicker = false
                    }) { Text("OK") }
                },
                dismissButton = {
                    TextButton(onClick = { showFromPicker = false }) { Text("Cancel") }
                },
            ) {
                DatePicker(state = state)
            }
        }

        if (showToPicker) {
            val state = rememberDatePickerState(
                initialSelectedDateMillis = toDate
                    ?.atStartOfDay(ZoneId.systemDefault())?.toInstant()?.toEpochMilli(),
            )
            DatePickerDialog(
                onDismissRequest = { showToPicker = false },
                confirmButton = {
                    TextButton(onClick = {
                        state.selectedDateMillis?.let { vm.onToDateSelected(millisToLocalDate(it)) }
                        showToPicker = false
                    }) { Text("OK") }
                },
                dismissButton = {
                    TextButton(onClick = { showToPicker = false }) { Text("Cancel") }
                },
            ) {
                DatePicker(state = state)
            }
        }

        if (showSheet) {
            ModalBottomSheet(
                onDismissRequest = { showSheet = false },
                sheetState       = sheetState,
                containerColor   = SurfaceDark,
            ) {
                ManualAddSheet(
                    repository = repository,
                    onAdded    = {
                        scope.launch { sheetState.hide() }
                        showSheet = false
                        vm.reload()
                    }
                )
            }
        }

        detailTxn?.let { detail ->
            ModalBottomSheet(
                onDismissRequest = { detailTxn = null },
                sheetState       = detailSheetState,
                containerColor   = SurfaceDark,
            ) {
                TransactionDetailSheet(detail)
            }
        }
    }
}

/**
 * Shared rendering of the grouped transaction list for both the Main and Cover
 * layouts: emits a [DayHeader] for header items and a [TransactionRow] (with
 * [MerchantLogo]) for transaction items. Keeping this in one place ensures the two
 * layouts never diverge.
 */
private fun LazyListScope.transactionItems(
    items: List<TxnListItem>,
    onTxnClick: (TxnListItem.Txn) -> Unit,
) {
    items.forEach { item ->
        when (item) {
            is TxnListItem.Header -> {
                item(key = "h-${item.label}") { DayHeader(item) }
            }
            is TxnListItem.Txn -> {
                item(key = "t-${item.txn.id}") {
                    TransactionRow(item) { onTxnClick(item) }
                }
            }
        }
    }
}

/**
 * Compact cover-display variant: shares the SAME [TransactionsViewModel] instance
 * setup as [TransactionsMain] and reuses [transactionItems] for identical row/header
 * rendering. Drops the date-range pills and search to stay minimal on the small
 * cover screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TransactionsCover(repository: ExpenseRepository) {
    val vm: TransactionsViewModel = viewModel(
        factory = ledgerViewModelFactory {
            TransactionsViewModel(
                it.useCases.getCategories, it.useCases.getTransactions,
                it.useCases.searchTransactions, it.useCases.getSpentRange,
            )
        },
    )
    val state by vm.uiState.collectAsStateWithLifecycle()

    val loading = state.loading
    val items = state.items
    val filterDirection = state.filterDirection

    var detailTxn by remember { mutableStateOf<TxnListItem.Txn?>(null) }
    val detailSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CanvasBlack)
            .padding(horizontal = 12.dp, vertical = 12.dp),
    ) {
        Text(
            text  = "Transactions",
            style = MaterialTheme.typography.titleLarge,
            color = TextPrimary,
        )
        Spacer(Modifier.height(10.dp))

        val segIndex = when (filterDirection) { "debit" -> 1; "credit" -> 2; else -> 0 }
        SegmentedFilter(
            options = listOf("All", "Spends", "Income"),
            selectedIndex = segIndex,
            onSelect = { i ->
                vm.selectDirection(when (i) { 1 -> "debit"; 2 -> "credit"; else -> null })
            },
        )
        Spacer(Modifier.height(10.dp))

        if (loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = AccentTeal)
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                transactionItems(items) { detailTxn = it }
            }
        }
    }

    detailTxn?.let { detail ->
        ModalBottomSheet(
            onDismissRequest = { detailTxn = null },
            sheetState       = detailSheetState,
            containerColor   = SurfaceDark,
        ) {
            TransactionDetailSheet(detail)
        }
    }
}

@Composable
private fun DateField(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .background(SurfaceMid, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text     = label,
            style    = MaterialTheme.typography.bodyMedium,
            color    = TextPrimary,
            maxLines = 1,
            softWrap = false,
        )
    }
}

@Composable
private fun DayHeader(header: TxnListItem.Header) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 18.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text  = header.label.uppercase(Locale.getDefault()),
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondary,
            letterSpacing = 1.2.sp,
        )
        Text(
            text  = formatMoney(header.dayTotal),
            style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
            color = TextSecondary,
            maxLines = 1,
            softWrap = false,
        )
    }
}

@Composable
private fun TransactionRow(item: TxnListItem.Txn, onClick: () -> Unit) {
    val txn = item.txn
    val isCredit    = txn.direction == "credit"
    val amountColor = if (isCredit) CreditGreen else TextPrimary
    val signPrefix  = if (isCredit) "+ " else "− "
    val isForeign   = txn.currency != null && !txn.currency.equals("INR", true) && txn.originalAmount != null
    val primaryAmount = if (isForeign)
        signPrefix + currencySymbol(txn.currency) + "%,.2f".format(txn.originalAmount)
    else
        signPrefix + "₹" + "%,.2f".format(txn.amount)
    val time        = parseOccurred(txn.occurredAt)?.format(TIME_FMT) ?: ""
    val subtitle    = buildString {
        item.categoryName?.let { append(it.replaceFirstChar { c -> c.uppercase() }) }
        if (item.categoryName != null && time.isNotBlank()) append(" · ")
        if (time.isNotBlank()) append(time)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfaceDark, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MerchantLogo(merchant = txn.merchant)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text     = txn.merchant,
                style    = MaterialTheme.typography.titleMedium,
                color    = TextPrimary,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotBlank()) {
                Text(
                    text     = subtitle,
                    style    = MaterialTheme.typography.labelSmall,
                    color    = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text     = primaryAmount,
                style    = androidx.compose.ui.text.TextStyle(
                    fontFamily = com.ledgernoir.tracker.ui.theme.DisplayFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 17.sp,
                    fontFeatureSettings = "tnum",
                ),
                color    = amountColor,
                maxLines = 1,
                softWrap = false,
            )
            if (isForeign) {
                Text(
                    text  = "≈ ₹%,.0f".format(txn.amount),
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}

/** Symbol for a transaction's native currency code. */
private fun currencySymbol(code: String?): String = when (code?.uppercase()) {
    "USD" -> "$"
    "EUR" -> "€"
    "GBP" -> "£"
    else -> "₹"
}

@Composable
private fun TransactionDetailSheet(item: TxnListItem.Txn) {
    val txn = item.txn
    val isCredit = txn.direction == "credit"
    val occurred = parseOccurred(txn.occurredAt)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CategoryIconChip(item.categoryName, size = 48.dp)
            Spacer(Modifier.width(14.dp))
            Column {
                Text(
                    text  = txn.merchant,
                    style = MaterialTheme.typography.titleLarge,
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text  = item.categoryName?.replaceFirstChar { it.uppercase() } ?: "Uncategorized",
                    style = MaterialTheme.typography.labelMedium,
                    color = TextSecondary,
                )
            }
        }
        Text(
            text  = (if (isCredit) "+ ₹" else "− ₹") + "%,.2f".format(txn.amount),
            style = MaterialTheme.typography.displayMedium.copy(
                fontSize = 34.sp,
                fontFeatureSettings = "tnum",
            ),
            color = if (isCredit) CreditGreen else TextPrimary,
            maxLines = 1,
            softWrap = false,
        )
        DetailRow("Date", occurred?.toLocalDate()?.format(DAY_HEADER_FMT) ?: txn.occurredAt.take(10))
        occurred?.let { DetailRow("Time", it.format(TIME_FMT)) }
        DetailRow("Direction", txn.direction.replaceFirstChar { it.uppercase() })
        txn.note?.takeIf { it.isNotBlank() }?.let { DetailRow("Note", it) }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
        Spacer(Modifier.width(16.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = TextPrimary,
            modifier = Modifier.weight(1f, fill = false),
        )
    }
}

@Composable
private fun ManualAddSheet(repository: ExpenseRepository, onAdded: () -> Unit) {
    var merchant  by remember { mutableStateOf("") }
    var amount    by remember { mutableStateOf("") }
    var direction by remember { mutableStateOf("debit") }
    var note      by remember { mutableStateOf("") }
    var saving    by remember { mutableStateOf(false) }
    var error     by remember { mutableStateOf<String?>(null) }
    val scope     = rememberCoroutineScope()
    val haptic    = LocalHapticFeedback.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text  = "Add Transaction",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        OutlinedTextField(
            value         = merchant,
            onValueChange = { merchant = it },
            label         = { Text("Merchant") },
            singleLine    = true,
            modifier      = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value         = amount,
            onValueChange = { amount = it },
            label         = { Text("Amount (₹)") },
            singleLine    = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier      = Modifier.fillMaxWidth(),
        )
        // Direction toggle
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("debit", "credit").forEach { d ->
                val selected = direction == d
                Button(
                    onClick = { direction = d },
                    colors  = ButtonDefaults.buttonColors(
                        containerColor = if (selected) AccentTeal else SurfaceMid,
                    ),
                ) {
                    Text(d.replaceFirstChar { it.uppercase() })
                }
            }
        }
        OutlinedTextField(
            value         = note,
            onValueChange = { note = it },
            label         = { Text("Note (optional)") },
            singleLine    = true,
            modifier      = Modifier.fillMaxWidth(),
        )
        error?.let {
            Text(text = it, color = DebitRose, style = MaterialTheme.typography.labelSmall)
        }
        Button(
            enabled  = merchant.isNotBlank() && amount.isNotBlank() && !saving,
            onClick  = {
                val amtDouble = amount.toDoubleOrNull()
                if (amtDouble == null) {
                    error = "Invalid amount"
                } else {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    saving = true
                    scope.launch {
                        repository.createManual(
                            ManualTxnPayload(
                                amount     = amtDouble,
                                direction  = direction,
                                merchant   = merchant,
                                occurredAt = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                                note       = note.ifBlank { null },
                            )
                        ).fold(
                            onSuccess = { onAdded() },
                            onFailure = { error = it.message; saving = false },
                        )
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (saving) "Saving…" else "Save")
        }
        Spacer(Modifier.height(8.dp))
    }
}
