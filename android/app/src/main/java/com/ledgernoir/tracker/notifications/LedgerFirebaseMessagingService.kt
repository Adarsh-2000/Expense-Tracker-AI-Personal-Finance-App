package com.ledgernoir.tracker.notifications

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.ledgernoir.tracker.LedgerNoirApp
import com.ledgernoir.tracker.data.api.NotificationDto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Receives FCM pushes and the device's registration token.
 *
 * - [onNewToken] registers the (rotating) FCM token with the backend so it can
 *   target this device.
 * - [onMessageReceived] fires for foreground messages (and data-only messages in
 *   the background); we render them through the same [NotificationHelper] the
 *   poll-based system uses, so pushed and local notifications look identical and
 *   deep-link the same way. Background messages that carry a `notification` payload
 *   are shown by the system tray automatically — no code needed.
 */
class LedgerFirebaseMessagingService : FirebaseMessagingService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNewToken(token: String) {
        val repo = (application as LedgerNoirApp).repository
        scope.launch { repo.registerPushToken(token) }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        val notif = message.notification
        val title = data["title"] ?: notif?.title ?: "Ledger Noir"
        val body = data["body"] ?: notif?.body ?: ""
        // Stable id keeps a given event from stacking duplicate notifications.
        val id = data["id"] ?: message.messageId ?: title
        NotificationHelper.post(
            applicationContext,
            NotificationDto(
                id = id,
                type = data["type"] ?: "push",
                severity = data["severity"] ?: "info",
                title = title,
                body = body,
                route = data["route"],
                anchor = data["anchor"]?.takeIf { it.isNotBlank() },
            ),
        )
    }
}
