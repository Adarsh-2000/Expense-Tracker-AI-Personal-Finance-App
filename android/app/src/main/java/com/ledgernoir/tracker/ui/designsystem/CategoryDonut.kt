package com.ledgernoir.tracker.ui.designsystem

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ledgernoir.tracker.data.api.SummaryItem
import com.ledgernoir.tracker.ui.theme.SurfaceMid
import com.ledgernoir.tracker.ui.theme.TextPrimary
import com.ledgernoir.tracker.ui.theme.TextSecondary

/**
 * Centered donut of category spend with the total in the middle. When
 * [selectedCategory] is non-null, that slice is drawn at full opacity (others
 * dimmed) and the center shows the selected category's name + total.
 */
@Composable
fun CategoryDonut(
    summary: List<SummaryItem>,
    modifier: Modifier = Modifier,
    selectedCategory: String? = null,
    sizeDp: Int = 184,
) {
    val grandTotal = summary.sumOf { it.total }.takeIf { it > 0.0 } ?: 0.0
    val sel = selectedCategory?.let { s ->
        summary.firstOrNull { it.category.equals(s, ignoreCase = true) }
    }
    val centerLabel = sel?.category?.replaceFirstChar { it.uppercase() } ?: "TOTAL"
    val centerAmount = sel?.total ?: grandTotal

    val sweepProgress by animateFloatAsState(
        targetValue = 1f,
        animationSpec = tween(900),
        label = "donutSweep",
    )
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(sizeDp.dp)) {
            val strokeWidth = 30f
            val inset = strokeWidth / 2f
            val arcSize = Size(size.width - strokeWidth, size.height - strokeWidth)
            val topLeft = Offset(inset, inset)
            if (grandTotal <= 0.0) {
                drawArc(
                    color = SurfaceMid, startAngle = 0f, sweepAngle = 360f,
                    useCenter = false, topLeft = topLeft, size = arcSize,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
                )
            } else {
                var startAngle = -90f
                summary.sortedByDescending { it.total }.forEach { item ->
                    val sweep = (item.total / grandTotal * 360.0).toFloat() * sweepProgress
                    val isDimmed = sel != null && !item.category.equals(sel.category, true)
                    drawArc(
                        color = styleFor(item.category).color.copy(alpha = if (isDimmed) 0.18f else 1f),
                        startAngle = startAngle, sweepAngle = sweep, useCenter = false,
                        topLeft = topLeft, size = arcSize,
                        style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
                    )
                    startAngle += sweep
                }
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = centerLabel.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary, letterSpacing = 1.4.sp,
            )
            Spacer(Modifier.height(4.dp))
            MoneyText(amount = centerAmount, fontSize = 22.sp, color = TextPrimary)
        }
    }
}
