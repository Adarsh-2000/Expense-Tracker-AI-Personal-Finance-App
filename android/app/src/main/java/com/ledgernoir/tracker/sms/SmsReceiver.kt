package com.ledgernoir.tracker.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.ledgernoir.tracker.LedgerNoirApp
import com.ledgernoir.tracker.data.api.SmsPayload
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Receives all incoming SMS broadcasts and forwards bank messages to the backend.
 *
 * ── Reliability note ──────────────────────────────────────────────────────────
 * On Android 8+ (API 26) static BroadcastReceivers for implicit broadcasts were
 * restricted. SMS_RECEIVED is an EXEMPT implicit broadcast (listed in the
 * system exemption list) so it still reaches statically-declared receivers even
 * when the app is not running — no foreground service or WorkManager needed.
 *
 * However, some OEM launchers (Xiaomi, Oppo, Vivo) will kill background
 * processes aggressively. If SMS forwarding misses messages on such devices,
 * the fallback is to use a persistent foreground notification service. The
 * current implementation handles the common case (stock AOSP / emulator).
 * ──────────────────────────────────────────────────────────────────────────────
 */
class SmsReceiver : BroadcastReceiver() {

    private val filter = SmsFilter()
    private val scope  = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val isoFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")
        .withZone(ZoneOffset.UTC)

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isNullOrEmpty()) return

        // Group PDUs by originating address into full message bodies
        val grouped = messages.groupBy { it.originatingAddress ?: "" }

        grouped.forEach { (sender, pdus) ->
            if (!filter.isWhitelisted(sender)) return@forEach

            val body        = pdus.joinToString(separator = "") { it.messageBody }
            val timestampMs = pdus.first().timestampMillis
            val externalId  = "sms-$timestampMs-${sha256Short(body)}"
            val receivedAt  = isoFormatter.format(Instant.ofEpochMilli(timestampMs))

            val app = context.applicationContext as LedgerNoirApp
            val pendingResult = goAsync()

            scope.launch {
                try {
                    app.repository.ingestSms(
                        SmsPayload(
                            sender     = sender,
                            body       = body,
                            receivedAt = receivedAt,
                            externalId = externalId,
                        )
                    )
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }

    /** First 8 hex chars of SHA-256 — enough for idempotency, not sensitive. */
    private fun sha256Short(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
        return digest.take(4).joinToString("") { "%02x".format(it) }
    }
}
