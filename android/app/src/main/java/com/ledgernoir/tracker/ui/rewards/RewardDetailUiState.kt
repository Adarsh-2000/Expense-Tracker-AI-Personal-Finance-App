package com.ledgernoir.tracker.ui.rewards

import com.ledgernoir.tracker.data.api.RewardCardDto

/**
 * State for the per-card detail screen. [card] is the single matching card from the
 * /rewards payload (null while loading or if not found).
 */
data class RewardDetailUiState(
    val loading: Boolean = true,
    val card: RewardCardDto? = null,
)
