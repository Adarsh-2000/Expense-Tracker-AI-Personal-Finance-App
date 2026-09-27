package com.ledgernoir.tracker.ui.auth

import android.app.Activity
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.ledgernoir.tracker.data.repository.ExpenseRepository
import com.ledgernoir.tracker.data.TokenStore
import com.ledgernoir.tracker.ui.theme.AccentGreen
import com.ledgernoir.tracker.ui.theme.AccentTeal
import com.ledgernoir.tracker.ui.theme.BorderGray
import com.ledgernoir.tracker.ui.theme.CanvasBlack
import com.ledgernoir.tracker.ui.theme.CreditGreen
import com.ledgernoir.tracker.ui.theme.DebitRose
import com.ledgernoir.tracker.ui.theme.SurfaceDark
import com.ledgernoir.tracker.ui.theme.TextMuted
import com.ledgernoir.tracker.ui.theme.TextSecondary
import kotlinx.coroutines.launch

/**
 * Premium "Continue with Google" sign-in screen. Replaces the legacy PIN lock.
 *
 * Flow: fetch the Web client ID from the backend (GET /auth/config) → show the
 * native Google account picker via Credential Manager (GetGoogleIdOption) →
 * obtain a Google ID token → POST it to /auth/verify-google for verification →
 * store the returned session token and invoke [onSignedIn]. Failures surface an
 * inline message with a Retry affordance.
 */
@Composable
fun SignInScreen(
    repository: ExpenseRepository,
    tokenStore: TokenStore,
    onSignedIn: () -> Unit,
) {
    val isCover = com.ledgernoir.tracker.ui.designsystem.LocalFormFactor.current ==
        com.ledgernoir.tracker.ui.designsystem.FormFactor.Cover
    var loading      by remember { mutableStateOf(false) }
    var errorMsg     by remember { mutableStateOf<String?>(null) }
    var signedEmail  by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current

    fun attempt() {
        loading = true
        errorMsg = null
        scope.launch {
            // Sign-in REQUIRES the native Google picker + a server-verified Google ID
            // token (real proof of account possession, bound to a one-time server nonce).
            // There is intentionally NO device-key fallback — that would let anyone who
            // captured the key mint a session over the cleartext LAN.
            val webClientId = repository.authConfig().getOrNull()
            if (webClientId.isNullOrBlank()) {
                errorMsg = "Google sign-in isn't configured on the server."
                loading = false
                return@launch
            }
            val nonce = repository.authNonce().getOrNull()
            if (nonce.isNullOrBlank()) {
                errorMsg = "Couldn't reach the server to start a secure sign-in."
                loading = false
                return@launch
            }

            try {
                val credentialManager = CredentialManager.create(context)
                val signInOption = GetSignInWithGoogleOption.Builder(webClientId)
                    .setNonce(nonce)
                    .build()
                val request = GetCredentialRequest.Builder()
                    .addCredentialOption(signInOption)
                    .build()
                val activity = context as? Activity ?: context
                val result = credentialManager.getCredential(activity, request)
                val cred = result.credential
                if (cred is CustomCredential &&
                    cred.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
                ) {
                    val idToken = GoogleIdTokenCredential.createFrom(cred.data).idToken
                    val resp = repository.verifyGoogle(idToken, nonce).getOrNull()
                    if (resp?.authorized == true && resp.token != null) {
                        signedEmail = resp.email
                        tokenStore.setToken(resp.token!!)
                        onSignedIn()
                    } else {
                        errorMsg = "Server rejected the Google account. Make sure this " +
                            "is the account connected on the server."
                        loading = false
                    }
                } else {
                    errorMsg = "Unexpected credential type from Google."
                    loading = false
                }
            } catch (e: GetCredentialException) {
                // Surface the real reason so device-side issues are actionable.
                errorMsg = "Google sign-in failed: ${e.message}\n\n" +
                    "If this says 'reauth failed' or 'no credentials', re-add your " +
                    "Google account: Settings → Accounts → Google."
                loading = false
            } catch (e: Exception) {
                errorMsg = "Google sign-in error: ${e.message}"
                loading = false
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(CanvasBlack),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp),
        ) {
            // Wordmark — oversized display family with teal→emerald gradient brush.
            Text(
                text = "Ledger\nNoir",
                style = MaterialTheme.typography.displayLarge.copy(
                    brush = Brush.linearGradient(listOf(AccentTeal, AccentGreen)),
                    fontWeight = FontWeight.Bold,
                    fontSize = if (isCover) 34.sp else 56.sp,
                    lineHeight = if (isCover) 34.sp else 56.sp,
                ),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "Your private money, beautifully organised.",
                style = MaterialTheme.typography.bodyLarge,
                color = TextSecondary,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(if (isCover) 14.dp else 28.dp))

            // Continue with Google button — white-ish card surface.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFFF3F4F6))
                    .border(
                        1.dp,
                        AccentTeal.copy(alpha = 0.6f),
                        RoundedCornerShape(16.dp),
                    )
                    .let { m -> if (!loading) m.clickable {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        attempt()
                    } else m }
                    .padding(vertical = 17.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (loading) {
                    CircularProgressIndicator(
                        color = AccentTeal,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.size(12.dp))
                    Text(
                        text = "Signing in…",
                        color = Color(0xFF1F2937),
                        fontWeight = FontWeight.SemiBold,
                    )
                } else {
                    GoogleGlyph()
                    Spacer(Modifier.size(12.dp))
                    Text(
                        text = "Continue with Google",
                        color = Color(0xFF1F2937),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            if (signedEmail != null) {
                Text(
                    text = "Signed in as $signedEmail",
                    style = MaterialTheme.typography.labelMedium,
                    color = CreditGreen,
                )
            }

            if (errorMsg != null && !loading) {
                Spacer(Modifier.height(4.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(SurfaceDark, RoundedCornerShape(12.dp))
                        .border(1.dp, BorderGray.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = errorMsg!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = DebitRose,
                        textAlign = TextAlign.Center,
                    )
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(AccentTeal.copy(alpha = 0.15f))
                            .clickable { attempt() }
                            .padding(horizontal = 24.dp, vertical = 10.dp),
                    ) {
                        Text(
                            text = "Retry",
                            color = AccentTeal,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }

            Spacer(Modifier.height(if (isCover) 14.dp else 28.dp))

            Text(
                text = "Verified by your own server · your data never leaves it.",
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** A simple colored "G" glyph standing in for the Google mark. */
@Composable
private fun GoogleGlyph() {
    Text(
        text = "G",
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
        color = Color(0xFF4285F4),
    )
}
