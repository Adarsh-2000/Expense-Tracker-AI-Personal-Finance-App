package com.ledgernoir.tracker.ui.transactions

import com.ledgernoir.tracker.ui.designsystem.*

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ledgernoir.tracker.data.api.TransactionDto
import com.ledgernoir.tracker.ui.theme.AccentTeal
import com.ledgernoir.tracker.ui.theme.CanvasBlack
import com.ledgernoir.tracker.ui.theme.CreditGreen
import com.ledgernoir.tracker.ui.theme.DisplayFamily
import com.ledgernoir.tracker.ui.theme.SurfaceMid
import com.ledgernoir.tracker.ui.theme.TextPrimary
import com.ledgernoir.tracker.ui.theme.TextSecondary
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

// ── Local date/time helpers (self-contained; does not depend on TransactionsScreen) ──

val DETAIL_DAY_FMT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("EEE, dd MMM", Locale.getDefault())
val DETAIL_TIME_FMT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault())

/** Tolerantly parses an "occurred_at" string into a [LocalDateTime]. */
fun parseOccurredLocal(raw: String): LocalDateTime? {
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

/** Uppercase day-group header: "TODAY" / "YESTERDAY" / "WED, 03 JUN". */
fun dayHeaderLabel(date: LocalDate): String {
    val today = LocalDate.now()
    val raw = when (date) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> date.format(DETAIL_DAY_FMT)
    }
    return raw.uppercase(Locale.getDefault())
}

/** Symbol for a transaction's native currency code. */
fun currencySymbolLocal(code: String?): String = when (code?.uppercase()) {
    "USD" -> "$"
    "EUR" -> "€"
    "GBP" -> "£"
    else -> "₹"
}

enum class SortBy { DATE, AMOUNT }

/** A flattened display entry for the date-grouped list. */
sealed interface DetailListItem {
    data class Header(val label: String, val dayTotal: Double) : DetailListItem
    data class Row(val txn: TransactionDto, val categoryName: String?) : DetailListItem
}

/** Builds the date-grouped flattened list (newest day first, newest txn first). */
fun buildGroupedDetailItems(
    transactions: List<TransactionDto>,
    categoryNames: Map<Int, String>,
): List<DetailListItem> {
    val byDay = transactions
        .mapNotNull { t -> parseOccurredLocal(t.occurredAt)?.let { it to t } }
        .sortedByDescending { it.first }
        .groupBy { it.first.toLocalDate() }

    val out = ArrayList<DetailListItem>()
    byDay.entries
        .sortedByDescending { it.key }
        .forEach { (day, entries) ->
            val dayTotal = entries
                .filter { it.second.direction != "credit" }
                .sumOf { it.second.amount }
            out.add(DetailListItem.Header(dayHeaderLabel(day), dayTotal))
            entries.forEach { (_, txn) ->
                out.add(DetailListItem.Row(txn, txn.categoryId?.let { categoryNames[it] }))
            }
        }
    return out
}

@Composable
fun SortToggle(sortBy: SortBy, onSelect: (SortBy) -> Unit) {
    Row(
        modifier = Modifier
            .background(SurfaceMid, RoundedCornerShape(20.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        SortChip("Date", selected = sortBy == SortBy.DATE) { onSelect(SortBy.DATE) }
        SortChip("Amount", selected = sortBy == SortBy.AMOUNT) { onSelect(SortBy.AMOUNT) }
    }
}

@Composable
fun SortChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .background(
                if (selected) AccentTeal else Color.Transparent,
                RoundedCornerShape(16.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) CanvasBlack else TextSecondary,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
        )
    }
}

@Composable
fun DetailDayHeader(header: DetailListItem.Header) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 18.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = header.label,
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondary,
            letterSpacing = 1.2.sp,
        )
        Text(
            text = formatMoney(header.dayTotal),
            style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
            color = TextSecondary,
            maxLines = 1,
            softWrap = false,
        )
    }
}

@Composable
fun DetailTxnRow(item: DetailListItem.Row) {
    val txn = item.txn
    val isCredit = txn.direction == "credit"
    val amountColor = if (isCredit) CreditGreen else TextPrimary
    val signPrefix = if (isCredit) "+ " else "− "
    val isForeign = txn.currency != null &&
        !txn.currency.equals("INR", true) && txn.originalAmount != null
    val primaryAmount = if (isForeign)
        signPrefix + currencySymbolLocal(txn.currency) + "%,.2f".format(txn.originalAmount)
    else
        signPrefix + "₹" + "%,.2f".format(txn.amount)
    val time = parseOccurredLocal(txn.occurredAt)?.format(DETAIL_TIME_FMT) ?: ""
    val subtitle = buildString {
        item.categoryName?.let { append(it.replaceFirstChar { c -> c.uppercase() }) }
        if (item.categoryName != null && time.isNotBlank()) append(" · ")
        if (time.isNotBlank()) append(time)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfaceMid.copy(alpha = 0.0f), RoundedCornerShape(16.dp))
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CategoryIconChip(item.categoryName, size = 40.dp)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = txn.merchant,
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = primaryAmount,
                style = TextStyle(
                    fontFamily = DisplayFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 17.sp,
                    fontFeatureSettings = "tnum",
                ),
                color = amountColor,
                maxLines = 1,
                softWrap = false,
            )
            if (isForeign) {
                Text(
                    text = "≈ ₹%,.0f".format(txn.amount),
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}
