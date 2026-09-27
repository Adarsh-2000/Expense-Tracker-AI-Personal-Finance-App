package com.ledgernoir.tracker.ui.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 8pt-based spacing scale used across the redesign. */
object Space {
    val xs: Dp = 4.dp
    val s: Dp = 8.dp
    val m: Dp = 12.dp
    val l: Dp = 16.dp
    val xl: Dp = 24.dp
    val xxl: Dp = 32.dp
}

/** Corner radii. */
object Radii {
    val card: Dp = 20.dp
    val cardArt: Dp = 18.dp
    val pill: Dp = 20.dp
}

/** Type sizes that ramp down on the small cover screen. Read inside composables. */
@Composable
fun heroFontSize(): TextUnit = when (LocalFormFactor.current) {
    FormFactor.Cover -> 27.sp
    else -> 36.sp
}

@Composable
fun titleFontSize(): TextUnit = when (LocalFormFactor.current) {
    FormFactor.Cover -> 18.sp
    else -> 22.sp
}
