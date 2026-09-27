package com.ledgernoir.tracker.ui.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ledgernoir.tracker.ui.theme.BorderGray
import com.ledgernoir.tracker.ui.theme.CreditGreen
import com.ledgernoir.tracker.ui.theme.DebitRose
import com.ledgernoir.tracker.ui.theme.DisplayFamily
import com.ledgernoir.tracker.ui.theme.SurfaceDark
import com.ledgernoir.tracker.ui.theme.TextPrimary

/**
 * Money formatting helper — grouped (Indian-style %,). With paise (two decimals)
 * by default; pass [showPaise] = false for whole-rupee formatting (used by the
 * oversized heroes to avoid horizontal overflow on large amounts).
 */
fun formatMoney(amount: Double, showPaise: Boolean = true): String =
    if (showPaise) "₹ %,.2f".format(amount) else "₹ %,.0f".format(amount)

/** Whole-rupee money string (no paise), e.g. "₹ 300,426". */
fun formatWhole(amount: Double): String = formatMoney(amount, showPaise = false)

/**
 * Premium CRED-style card surface: a soft dark drop shadow underneath, a
 * SurfaceDark fill with 20dp rounded corners, and a 1dp hairline border.
 * Reused across screens — same name + content lambda signature as before.
 */
@Composable
fun CredCard(
    modifier: Modifier = Modifier,
    padding: Int = 20,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .shadow(
                elevation = 8.dp,
                shape = shape,
                clip = false,
                ambientColor = Color.Black,
                spotColor = Color.Black,
            )
            .background(SurfaceDark, shape)
            .border(1.dp, BorderGray, shape)
            .padding(padding.dp),
        content = content,
    )
}

/**
 * Money text rendered with the display typeface and tabular figures so digits
 * align. Optionally prefixes a +/- sign and tints by sign (credit / debit).
 *
 * @param amount the raw value; rendered via [formatMoney].
 * @param showSign when true, prefixes "+"/"−" and colors green/rose.
 * @param color explicit color override; when null, derived from [showSign] sign.
 */
@Composable
fun MoneyText(
    amount: Double,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 16.sp,
    showSign: Boolean = false,
    showPaise: Boolean = true,
    color: Color? = null,
) {
    val magnitude = formatMoney(kotlin.math.abs(amount), showPaise = showPaise)
    val text = when {
        !showSign -> magnitude
        amount < 0 -> "− $magnitude"
        else -> "+ $magnitude"
    }
    val resolvedColor = color ?: when {
        !showSign -> TextPrimary
        amount < 0 -> DebitRose
        else -> CreditGreen
    }
    Text(
        text = text,
        modifier = modifier,
        color = resolvedColor,
        style = TextStyle(
            fontFamily = DisplayFamily,
            fontSize = fontSize,
            fontFeatureSettings = "tnum",
        ),
    )
}
