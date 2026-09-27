package com.ledgernoir.tracker.ui.notifications

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ledgernoir.tracker.data.TokenStore
import com.ledgernoir.tracker.data.api.NotificationDto
import com.ledgernoir.tracker.domain.usecase.GetNotificationsUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Owns the in-app notification center. The feed is stateless on the server, so
 * read-state is local: items the user marked read live in [TokenStore]. Items are
 * sorted unread-first; reading one (or all) updates the persisted read set, which
 * also drives the bell badge elsewhere.
 */
class NotificationCenterViewModel(
    private val getNotifications: GetNotificationsUseCase,
    private val tokenStore: TokenStore,
) : ViewModel() {

    private val _uiState = MutableStateFlow(NotificationCenterUiState())
    val uiState: StateFlow<NotificationCenterUiState> = _uiState.asStateFlow()

    private var feed: List<NotificationDto> = emptyList()

    init {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(loading = true)
            feed = getNotifications().getOrNull()?.notifications ?: emptyList()
            recompute(tokenStore.readNotificationIds.first())
            _uiState.value = _uiState.value.copy(loading = false)
        }
    }

    fun markRead(id: String) {
        viewModelScope.launch {
            tokenStore.markNotificationsRead(setOf(id))
            recompute(tokenStore.readNotificationIds.first())
        }
    }

    fun markAllRead() {
        viewModelScope.launch {
            tokenStore.markNotificationsRead(feed.map { it.id }.toSet())
            recompute(tokenStore.readNotificationIds.first())
        }
    }

    private fun recompute(readIds: Set<String>) {
        val items = feed
            .map { CenterItem(it, read = it.id in readIds) }
            .sortedBy { it.read }  // unread (false) first
        _uiState.value = _uiState.value.copy(
            items = items,
            unreadCount = items.count { !it.read },
        )
    }
}
