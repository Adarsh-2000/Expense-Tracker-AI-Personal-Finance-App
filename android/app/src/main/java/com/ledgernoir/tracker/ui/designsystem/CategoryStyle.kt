package com.ledgernoir.tracker.ui.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** A category's visual identity: a brand color and a representative icon. */
data class CategoryStyle(val color: Color, val icon: ImageVector)

/**
 * Maps a category NAME (case-insensitive) to a [CategoryStyle]. Unknown or null
 * categories fall back to a neutral grey wallet chip.
 */
fun styleFor(category: String?): CategoryStyle = when (category?.lowercase()?.trim()) {
    "food", "dining", "restaurants" -> CategoryStyle(Color(0xFFFB923C), Icons.Filled.Restaurant)
    "groceries", "grocery"          -> CategoryStyle(Color(0xFF34D399), Icons.Filled.ShoppingCart)
    "shopping"                      -> CategoryStyle(Color(0xFFA78BFA), Icons.Filled.ShoppingBag)
    "travel", "transport"           -> CategoryStyle(Color(0xFF38BDF8), Icons.Filled.DirectionsCar)
    "bills", "utilities"            -> CategoryStyle(Color(0xFFFBBF24), Icons.Filled.Receipt)
    "bill payments", "bill_payments" -> CategoryStyle(Color(0xFFF59E0B), Icons.Filled.ReceiptLong)
    "entertainment"                 -> CategoryStyle(Color(0xFFF472B6), Icons.Filled.Movie)
    "investments", "investment"     -> CategoryStyle(Color(0xFF2DD4BF), Icons.Filled.TrendingUp)
    "transfers", "transfer"         -> CategoryStyle(Color(0xFF60A5FA), Icons.Filled.SwapHoriz)
    else                            -> CategoryStyle(Color(0xFF9CA3AF), Icons.Filled.AccountBalanceWallet)
}

/**
 * Premium rounded-square category chip: a [size] Box filled with the category
 * color at 15% alpha with the category icon centered and tinted in the full color.
 */
@Composable
fun CategoryIconChip(category: String?, size: Dp = 44.dp) {
    val style = styleFor(category)
    Box(
        modifier = Modifier
            .size(size)
            .background(style.color.copy(alpha = 0.15f), RoundedCornerShape(14.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = style.icon,
            contentDescription = category,
            tint = style.color,
            modifier = Modifier.size(size * 0.5f),
        )
    }
}
