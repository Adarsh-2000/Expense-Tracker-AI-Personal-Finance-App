package com.ledgernoir.tracker.ui.settings

import com.ledgernoir.tracker.ui.designsystem.*

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ledgernoir.tracker.ui.common.ledgerViewModelFactory
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ledgernoir.tracker.data.repository.ExpenseRepository
import com.ledgernoir.tracker.data.TokenStore
import com.ledgernoir.tracker.ui.theme.AccentTeal
import com.ledgernoir.tracker.ui.theme.CanvasBlack
import com.ledgernoir.tracker.ui.theme.DebitRose
import com.ledgernoir.tracker.ui.theme.SurfaceMid
import com.ledgernoir.tracker.ui.theme.TextPrimary
import com.ledgernoir.tracker.ui.theme.TextSecondary

// ── Public dispatcher — byte-identical signature ───────────────────────────────

@Composable
fun SettingsScreen(tokenStore: TokenStore, repository: ExpenseRepository) {
    when (com.ledgernoir.tracker.ui.designsystem.LocalFormFactor.current) {
        com.ledgernoir.tracker.ui.designsystem.FormFactor.Cover -> SettingsCover(tokenStore, repository)
        else -> SettingsMain(tokenStore, repository)
    }
}

// ── Main (phone / large) ───────────────────────────────────────────────────────

@Composable
private fun SettingsMain(tokenStore: TokenStore, repository: ExpenseRepository) {
    val vm: SettingsViewModel = viewModel(
        factory = ledgerViewModelFactory { SettingsViewModel(it.tokenStore, it.useCases.syncGmail) },
    )
    val state by vm.uiState.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CanvasBlack)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 20.dp),
    ) {
        ResponsiveContainer {
            Text(
                text  = "Settings",
                style = MaterialTheme.typography.headlineMedium,
                color = TextPrimary,
            )
            Spacer(Modifier.height(20.dp))

            SettingsBody(state = state, vm = vm)
            Spacer(Modifier.height(8.dp))
        }
    }
}

// ── Cover (small square watch-adjacent form factor) ────────────────────────────

@Composable
private fun SettingsCover(tokenStore: TokenStore, repository: ExpenseRepository) {
    val vm: SettingsViewModel = viewModel(
        factory = ledgerViewModelFactory { SettingsViewModel(it.tokenStore, it.useCases.syncGmail) },
    )
    val state by vm.uiState.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CanvasBlack)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        ResponsiveContainer {
            SettingsBody(state = state, vm = vm)
            Spacer(Modifier.height(8.dp))
        }
    }
}

// ── Shared body — used by both Main and Cover ──────────────────────────────────

@Composable
private fun SettingsBody(state: SettingsUiState, vm: SettingsViewModel) {
    val savedUrl   = state.savedUrl
    val draftUrl   = state.draftUrl
    val saved      = state.saved
    val syncing    = state.syncing
    val syncStatus = state.syncStatus
    val haptic     = LocalHapticFeedback.current

    val fieldColors = TextFieldDefaults.colors(
        focusedContainerColor   = SurfaceMid,
        unfocusedContainerColor = SurfaceMid,
        focusedIndicatorColor   = AccentTeal,
        unfocusedIndicatorColor = Color.Transparent,
        cursorColor             = AccentTeal,
        focusedTextColor        = TextPrimary,
        unfocusedTextColor      = TextPrimary,
    )

    // Backend URL
    CredCard {
        SettingsLabel("Backend URL")
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value         = draftUrl,
            onValueChange = { vm.onDraftUrlChange(it) },
            singleLine    = true,
            shape         = RoundedCornerShape(14.dp),
            colors        = fieldColors,
            placeholder   = { Text(TokenStore.DEFAULT_BASE_URL, color = TextSecondary) },
            modifier      = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text  = "Emulator default: http://10.0.2.2:8000",
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondary,
        )
        Spacer(Modifier.height(14.dp))
        Button(
            onClick = { vm.saveUrl() },
            enabled  = draftUrl != savedUrl || !saved,
            colors   = ButtonDefaults.buttonColors(
                containerColor = AccentTeal,
                contentColor = CanvasBlack,
            ),
            shape    = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (saved) "Saved" else "Save URL", fontWeight = FontWeight.SemiBold)
        }
    }
    Spacer(Modifier.height(16.dp))

    // App Lock
    CredCard {
        SettingsLabel("App Lock")
        Spacer(Modifier.height(8.dp))
        Text(
            text  = "PIN is set on the backend (ET_APP_PIN env var). Clear the token to force re-authentication on next launch.",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
        )
        Spacer(Modifier.height(14.dp))
        Button(
            onClick = { vm.lockNow() },
            colors  = ButtonDefaults.buttonColors(
                containerColor = DebitRose,
                contentColor = CanvasBlack,
            ),
            shape    = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Lock Now (clear token)", fontWeight = FontWeight.SemiBold)
        }
    }
    Spacer(Modifier.height(16.dp))

    // Sync Email (Gmail)
    CredCard {
        SettingsLabel("Sync Email (Gmail)")
        Spacer(Modifier.height(8.dp))
        Text(
            text  = "Pulls bank transaction emails from your connected Gmail. One-time setup required on the server (see GMAIL_SETUP.md).",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
        )
        Spacer(Modifier.height(14.dp))
        Button(
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                vm.syncEmail()
            },
            enabled = !syncing,
            colors  = ButtonDefaults.buttonColors(
                containerColor = AccentTeal,
                contentColor = CanvasBlack,
            ),
            shape    = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (syncing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = CanvasBlack,
                )
                Spacer(Modifier.width(8.dp))
                Text("Syncing…", fontWeight = FontWeight.SemiBold)
            } else {
                Text("Sync Email", fontWeight = FontWeight.SemiBold)
            }
        }
        if (syncStatus != null) {
            Spacer(Modifier.height(12.dp))
            Text(
                text  = syncStatus,
                style = MaterialTheme.typography.bodyMedium,
                color = AccentTeal,
            )
        }
    }
    Spacer(Modifier.height(16.dp))

    // SMS Forwarding
    CredCard {
        SettingsLabel("SMS Forwarding")
        Spacer(Modifier.height(8.dp))
        Text(
            text  = "The app silently forwards SMS from whitelisted bank senders to the backend. No parsing happens on-device — the raw text is sent as-is.",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
        )
        Spacer(Modifier.height(10.dp))
        Box(
            modifier = Modifier
                .background(AccentTeal.copy(alpha = 0.12f), RoundedCornerShape(10.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text(
                text  = "AXISBK · IDFCFB · BOBTXN",
                style = MaterialTheme.typography.labelMedium,
                color = AccentTeal,
            )
        }
    }
}

@Composable
private fun SettingsLabel(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = TextSecondary,
        letterSpacing = 1.4.sp,
    )
}
