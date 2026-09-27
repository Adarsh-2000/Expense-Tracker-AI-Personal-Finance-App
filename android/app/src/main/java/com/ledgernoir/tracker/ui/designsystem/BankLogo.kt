package com.ledgernoir.tracker.ui.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest

/** SMS bank name (normalized) -> Clearbit logo URL, or null if unknown. */
fun bankLogoUrl(bank: String): String? {
    val domain = when (bank.trim().uppercase().replace(" BANK", "").trim()) {
        "AXIS" -> "axisbank.com"
        "IDFC", "IDFC FIRST" -> "idfcfirstbank.com"
        "HDFC" -> "hdfcbank.com"
        "ICICI" -> "icicibank.com"
        "SBI" -> "sbi.co.in"
        "KOTAK" -> "kotak.com"
        "BOB", "BANK OF BARODA" -> "bankofbaroda.in"
        "AMEX", "AMERICAN EXPRESS" -> "americanexpress.com"
        "AU" -> "aubank.in"
        "YES" -> "yesbank.in"
        "RBL" -> "rblbank.com"
        "INDUSIND" -> "indusind.com"
        "HSBC" -> "hsbc.co.in"
        "CITI", "CITIBANK" -> "citibank.com"
        else -> null
    }
    return domain?.let { "https://logo.clearbit.com/$it" }
}

/**
 * A white rounded chip showing the bank's logo (fetched from Clearbit and disk-cached
 * by Coil). Falls back to the bank name in bold dark text when the logo is unknown,
 * blocked, or offline.
 */
@Composable
fun BankLogo(bank: String, modifier: Modifier = Modifier) {
    val url = bankLogoUrl(bank)
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color.White)
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .heightIn(min = 22.dp),
        contentAlignment = Alignment.Center,
    ) {
        val nameText: @Composable () -> Unit = {
            Text(
                bank.uppercase(),
                color = Color(0xFF111111),
                fontWeight = FontWeight.ExtraBold,
                fontSize = 12.sp,
                maxLines = 1,
            )
        }
        if (url == null) {
            nameText()
        } else {
            SubcomposeAsyncImage(
                model = ImageRequest.Builder(LocalContext.current).data(url).crossfade(true).build(),
                contentDescription = bank,
                contentScale = ContentScale.Fit,
                modifier = Modifier.heightIn(max = 22.dp),
                loading = { nameText() },
                error = { nameText() },
            )
        }
    }
}
