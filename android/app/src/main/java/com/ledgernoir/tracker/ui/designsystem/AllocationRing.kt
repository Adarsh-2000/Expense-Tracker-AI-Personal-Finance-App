package com.ledgernoir.tracker.ui.designsystem

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** One ring slice: a [fraction] of the whole (0..1) drawn in [color]. */
data class RingSlice(val fraction: Float, val color: Color)

/**
 * A donut ring built from [slices] (fractions should sum to ≤ 1). [center] renders
 * inside the hole (e.g. a total). Extends the existing donut idiom for allocation.
 */
@Composable
fun AllocationRing(
    slices: List<RingSlice>,
    modifier: Modifier = Modifier,
    diameter: Dp = 78.dp,
    stroke: Dp = 11.dp,
    center: @Composable () -> Unit = {},
) {
    Box(modifier = modifier.size(diameter), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(diameter)) {
            var start = -90f
            val sw = stroke.toPx()
            slices.forEach { s ->
                val sweep = s.fraction.coerceIn(0f, 1f) * 360f
                drawArc(
                    color = s.color,
                    startAngle = start,
                    sweepAngle = sweep,
                    useCenter = false,
                    style = Stroke(width = sw, cap = StrokeCap.Butt),
                )
                start += sweep
            }
        }
        center()
    }
}

@Preview(backgroundColor = 0xFF0B0B0D, showBackground = true)
@Composable
private fun AllocationRingPreview() {
    AllocationRing(
        slices = listOf(
            RingSlice(0.62f, Color(0xFF2DD4BF)),
            RingSlice(0.31f, Color(0xFFFBBF24)),
            RingSlice(0.07f, Color(0xFFFF5C5C)),
        ),
    )
}
