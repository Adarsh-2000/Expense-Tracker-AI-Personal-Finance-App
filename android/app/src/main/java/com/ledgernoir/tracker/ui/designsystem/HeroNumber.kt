package com.ledgernoir.tracker.ui.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ledgernoir.tracker.ui.theme.AccentGreen
import com.ledgernoir.tracker.ui.theme.CanvasBlack
import com.ledgernoir.tracker.ui.theme.DebitRose
import com.ledgernoir.tracker.ui.theme.TextPrimary
import com.ledgernoir.tracker.ui.theme.TextSecondary

/**
 * The dominating hero block: a small uppercase [label], a big [amount] in the
 * display typeface (size ramps by form factor), and an optional colored [change]
 * line (green up / rose down). Reuses [MoneyText] for the amount.
 */
@Composable
fun HeroNumber(
    label: String,
    amount: Double,
    modifier: Modifier = Modifier,
    change: String? = null,
    changePositive: Boolean = true,
    showPaise: Boolean = false,
) {
    Column(modifier = modifier) {
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondary,
            letterSpacing = 1.6.sp,
        )
        Spacer(Modifier.height(Space.s))
        MoneyText(amount = amount, fontSize = heroFontSize(), color = TextPrimary, showPaise = showPaise)
        if (change != null) {
            Spacer(Modifier.height(Space.xs))
            Text(
                text = change,
                style = MaterialTheme.typography.labelMedium,
                color = if (changePositive) AccentGreen else DebitRose,
            )
        }
    }
}

@Preview(backgroundColor = 0xFF0B0B0D, showBackground = true)
@Composable
private fun HeroNumberPreview() {
    Column(Modifier.background(CanvasBlack).padding(16.dp)) {
        HeroNumber(label = "Net worth", amount = 1842300.0, change = "▲ 2.1%  ₹38,400 · this month")
    }
}
