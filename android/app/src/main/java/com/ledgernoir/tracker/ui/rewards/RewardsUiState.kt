package com.ledgernoir.tracker.ui.rewards

import com.ledgernoir.tracker.data.api.RewardCardDto

/**
 * State for the Rewards screen. [cards] drives the per-card list (already sorted
 * expiring-soonest-first by the backend); [totalValueInr]/[totalPoints] drive the
 * aggregate hero (shown only when more than one card).
 */
data class RewardsUiState(
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val totalValueInr: Double = 0.0,
    val totalPoints: Int = 0,
    val cards: List<RewardCardDto> = emptyList(),
)
