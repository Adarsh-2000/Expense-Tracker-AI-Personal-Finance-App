package com.ledgernoir.tracker.ui.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * A 36dp rounded chip showing a merchant's initial over a stable per-merchant
 * color (hash-derived). A deterministic fallback when no brand asset exists —
 * gives the transaction feed instant visual recognition (Fi/Jupiter style).
 */
@Composable
fun MerchantLogo(merchant: String, modifier: Modifier = Modifier) {
    val palette = listOf(
        Color(0xFFFC8019), Color(0xFF5b8def), Color(0xFF34D399),
        Color(0xFFE94F8A), Color(0xFFFBBF24), Color(0xFF9C6ADE), Color(0xFF22B8CF),
    )
    val color = palette[merchant.hashCode().mod(palette.size)]
    val initial = merchant.trim().firstOrNull()?.uppercase() ?: "?"
    Box(
        modifier = modifier
            .size(36.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(color.copy(alpha = 0.22f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(initial, color = color, fontWeight = FontWeight.Bold, fontSize = 15.sp)
    }
}

@Preview(backgroundColor = 0xFF0B0B0D, showBackground = true)
@Composable
private fun MerchantLogoPreview() {
    Row {
        MerchantLogo("Swiggy")
        MerchantLogo("Uber")
        MerchantLogo("Amazon")
    }
}
