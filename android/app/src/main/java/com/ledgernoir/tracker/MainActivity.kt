package com.ledgernoir.tracker

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.ledgernoir.tracker.ui.shell.AppShell
import com.ledgernoir.tracker.ui.theme.LedgerNoirTheme

class MainActivity : ComponentActivity() {

    private var smsPermissionGranted by mutableStateOf(false)
    private var showConsent by mutableStateOf(false)
    // Deep-link route from a tapped notification (e.g. "bills", "budget"); consumed by AppShell.
    private var pendingRoute by mutableStateOf<String?>(null)
    // Optional item id to scroll to on the target screen; consumed by AppShell.
    private var pendingAnchor by mutableStateOf<String?>(null)

    private val smsPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        smsPermissionGranted = results.getOrDefault(Manifest.permission.RECEIVE_SMS, false)
        showConsent = false
    }

    private fun hasSmsPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS) ==
            PackageManager.PERMISSION_GRANTED

    // Android 13+ posts notifications only after the user grants this. A new check
    // is kicked once the dialog resolves so events surface right away.
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { (application as LedgerNoirApp).checkNotificationsNow() }

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            (application as LedgerNoirApp).checkNotificationsNow()
            return
        }
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            (application as LedgerNoirApp).checkNotificationsNow()
        } else {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        smsPermissionGranted = hasSmsPermission()
        // Ask for consent (SMS on-device + email via Gmail) only if SMS isn't granted yet.
        showConsent = !smsPermissionGranted
        pendingRoute = intent?.getStringExtra(EXTRA_ROUTE)
        pendingAnchor = intent?.getStringExtra(EXTRA_ANCHOR)
        ensureNotificationPermission()

        setContent {
            LedgerNoirTheme {
                AppShell(
                    app = application as LedgerNoirApp,
                    smsPermissionGranted = smsPermissionGranted,
                    pendingRoute = pendingRoute,
                    onRouteConsumed = { pendingRoute = null },
                    pendingAnchor = pendingAnchor,
                    onAnchorConsumed = { pendingAnchor = null },
                )

                if (showConsent) {
                    AlertDialog(
                        onDismissRequest = { showConsent = false },
                        title = { Text("Allow access to your transactions") },
                        text = {
                            Text(
                                "Ledger Noir reads your bank's transaction alerts to track spending " +
                                    "automatically:\n\n" +
                                    "•  SMS — bank transaction texts on this device (needs your permission below)\n" +
                                    "•  Email — bank transaction emails via the Gmail account connected on your " +
                                    "server (manage under Settings → Sync Gmail)\n\n" +
                                    "Your data stays on your own server. We never read personal messages — only " +
                                    "bank-sender alerts are processed."
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                smsPermissionLauncher.launch(
                                    arrayOf(
                                        Manifest.permission.RECEIVE_SMS,
                                        Manifest.permission.READ_SMS,
                                    )
                                )
                            }) { Text("Allow SMS access") }
                        },
                        dismissButton = {
                            TextButton(onClick = { showConsent = false }) { Text("Not now") }
                        },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Notification tapped while the app is already running.
        intent.getStringExtra(EXTRA_ROUTE)?.let { pendingRoute = it }
        intent.getStringExtra(EXTRA_ANCHOR)?.let { pendingAnchor = it }
    }

    override fun onResume() {
        super.onResume()
        // Reflect permission changes made from system settings.
        smsPermissionGranted = hasSmsPermission()
    }

    companion object {
        const val EXTRA_ROUTE = "ln_route"
        const val EXTRA_ANCHOR = "ln_anchor"
    }
}
