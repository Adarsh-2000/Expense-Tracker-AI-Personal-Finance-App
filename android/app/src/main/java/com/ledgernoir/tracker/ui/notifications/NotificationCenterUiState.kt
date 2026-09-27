package com.ledgernoir.tracker.ui.notifications

import com.ledgernoir.tracker.data.api.NotificationDto

/** A notification plus whether the user has read it in the in-app center. */
data class CenterItem(val notification: NotificationDto, val read: Boolean)

/**
 * Immutable snapshot of the notification center: the items (unread first), the
 * unread count for the bell badge, and the loading flag.
 */
data class NotificationCenterUiState(
    val loading: Boolean = true,
    val items: List<CenterItem> = emptyList(),
    val unreadCount: Int = 0,
)
