package com.ledgernoir.tracker.ui.settings

import com.ledgernoir.tracker.data.TokenStore

/**
 * Immutable snapshot of the Settings screen: the persisted backend URL, the
 * editable draft, the saved flag, and the Gmail-sync progress + status line.
 */
data class SettingsUiState(
    val savedUrl: String = TokenStore.DEFAULT_BASE_URL,
    val draftUrl: String = TokenStore.DEFAULT_BASE_URL,
    val saved: Boolean = false,
    val syncing: Boolean = false,
    val syncStatus: String? = null,
)
