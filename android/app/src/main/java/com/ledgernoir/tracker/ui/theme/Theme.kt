package com.ledgernoir.tracker.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// Fixed luxe dark scheme. NO dynamic color / Material You.
private val DarkColors = darkColorScheme(
    primary            = AccentTeal,
    onPrimary          = CanvasBlack,
    primaryContainer   = SurfaceMid,
    onPrimaryContainer = TextPrimary,
    secondary          = AccentGreen,
    onSecondary        = CanvasBlack,
    background         = CanvasBlack,
    onBackground       = TextPrimary,
    surface            = SurfaceDark,
    onSurface          = TextPrimary,
    surfaceVariant     = SurfaceMid,
    onSurfaceVariant   = TextSecondary,
    outline            = BorderGray,
    outlineVariant     = BorderGray,
    error              = DebitRose,
    onError            = CanvasBlack,
    scrim              = CanvasBlack,
)

@Composable
fun LedgerNoirTheme(content: @Composable () -> Unit) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                window.statusBarColor = CanvasBlack.toArgb()
                window.navigationBarColor = CanvasBlack.toArgb()
                val controller = WindowCompat.getInsetsController(window, view)
                // Dark canvas -> light (non-appearance) icons.
                controller.isAppearanceLightStatusBars = false
                controller.isAppearanceLightNavigationBars = false
            }
        }
    }
    MaterialTheme(
        colorScheme = DarkColors,
        typography  = AppTypography,
        content     = content,
    )
}
