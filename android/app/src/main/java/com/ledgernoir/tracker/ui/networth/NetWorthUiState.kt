package com.ledgernoir.tracker.ui.networth

import com.ledgernoir.tracker.data.api.NetWorthComponents
import com.ledgernoir.tracker.data.api.ProjectionResponse

/**
 * Immutable snapshot of the Net Worth screen: the breakdown components, the grand
 * total, and the long-range projection. The composable renders purely from this.
 */
data class NetWorthUiState(
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val components: NetWorthComponents? = null,
    val total: Double = 0.0,
    val projection: ProjectionResponse? = null,
)
