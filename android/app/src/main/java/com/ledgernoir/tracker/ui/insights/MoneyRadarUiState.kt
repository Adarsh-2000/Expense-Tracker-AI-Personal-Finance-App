package com.ledgernoir.tracker.ui.insights

import com.ledgernoir.tracker.data.api.FindingDto

data class MoneyRadarUiState(
    val loading: Boolean = true,
    val findings: List<FindingDto> = emptyList(),
    val newCount: Int = 0,
    val error: String? = null,
)
