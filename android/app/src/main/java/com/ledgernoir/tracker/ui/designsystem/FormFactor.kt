package com.ledgernoir.tracker.ui.designsystem

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Which physical surface we're rendering on. Galaxy Z Flip 7 has two:
 *  - Cover  → 4.1" near-square (≈361x390dp): short, compact layouts.
 *  - Phone  → 6.9" main (≈407x950dp): the tall single-column feed.
 *  - Wide   → ≥600dp width (large/landscape/foldable-open tablets): safety fallback,
 *             renders the Phone tree inside a width-capped container.
 */
enum class FormFactor { Cover, Phone, Wide }

/** Pure breakpoint logic — unit tested. Width rule wins over the short-height rule. */
fun formFactorFor(widthDp: Int, heightDp: Int): FormFactor = when {
    widthDp >= 600 -> FormFactor.Wide
    heightDp < 480 -> FormFactor.Cover
    else -> FormFactor.Phone
}

/** Current surface. Defaults to Phone; real value supplied by [ProvideFormFactor]. */
val LocalFormFactor = staticCompositionLocalOf { FormFactor.Phone }

/**
 * Measures the available window and provides [LocalFormFactor]. Because it reads
 * constraints (not the Activity), it recomputes automatically when the window
 * resizes on fold/unfold — no Activity recreation required.
 */
@Composable
fun ProvideFormFactor(content: @Composable () -> Unit) {
    BoxWithConstraints {
        val ff = formFactorFor(widthDp = maxWidth.value.toInt(), heightDp = maxHeight.value.toInt())
        CompositionLocalProvider(LocalFormFactor provides ff, content = content)
    }
}
