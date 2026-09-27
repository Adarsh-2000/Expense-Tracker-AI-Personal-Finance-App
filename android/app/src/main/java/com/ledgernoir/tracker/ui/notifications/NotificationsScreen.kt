package com.ledgernoir.tracker.ui.notifications

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ledgernoir.tracker.data.api.NotificationDto
import com.ledgernoir.tracker.ui.common.ledgerViewModelFactory
import com.ledgernoir.tracker.ui.designsystem.CredCard
import com.ledgernoir.tracker.ui.designsystem.ResponsiveContainer
import com.ledgernoir.tracker.ui.theme.AccentGreen
import com.ledgernoir.tracker.ui.theme.AccentTeal
import com.ledgernoir.tracker.ui.theme.CanvasBlack
import com.ledgernoir.tracker.ui.theme.DebitRose
import com.ledgernoir.tracker.ui.theme.DisplayFamily
import com.ledgernoir.tracker.ui.theme.SurfaceMid
import com.ledgernoir.tracker.ui.theme.TextMuted
import com.ledgernoir.tracker.ui.theme.TextPrimary
import com.ledgernoir.tracker.ui.theme.TextSecondary

private fun typeIcon(type: String): ImageVector = when (type) {
    "bill" -> Icons.Filled.CreditCard
    "goal" -> Icons.Filled.Flag
    "budget" -> Icons.Filled.PieChart
    "spending" -> Icons.Filled.TrendingUp
    "digest" -> Icons.Filled.Receipt
    else -> Icons.Filled.Notifications
}

// ── Public dispatcher — byte-identical signature ───────────────────────────────

@Composable
fun NotificationsScreen(
    onBack: () -> Unit,
    onOpenRoute: (route: String, anchor: String?) -> Unit,
) {
    when (com.ledgernoir.tracker.ui.designsystem.LocalFormFactor.current) {
        com.ledgernoir.tracker.ui.designsystem.FormFactor.Cover -> NotificationsCover(onBack, onOpenRoute)
        else -> NotificationsMain(onBack, onOpenRoute)
    }
}

// ── Main (phone / large) ───────────────────────────────────────────────────────

@Composable
private fun NotificationsMain(
    onBack: () -> Unit,
    onOpenRoute: (route: String, anchor: String?) -> Unit,
) {
    val vm: NotificationCenterViewModel = viewModel(
        factory = ledgerViewModelFactory {
            NotificationCenterViewModel(it.useCases.getNotifications, it.tokenStore)
        },
    )
    val state by vm.uiState.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CanvasBlack)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 20.dp),
    ) {
        ResponsiveContainer {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onBack() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = TextSecondary)
                }
                Spacer(Modifier.width(4.dp))
                Text(
                    text = "Notifications",
                    style = MaterialTheme.typography.headlineMedium,
                    color = TextPrimary,
                    fontFamily = DisplayFamily,
                    modifier = Modifier.weight(1f),
                )
                if (state.unreadCount > 0) {
                    Text(
                        text = "Mark all read",
                        style = MaterialTheme.typography.labelLarge,
                        color = AccentTeal,
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { vm.markAllRead() }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            NotificationsBody(state = state, vm = vm, onOpenRoute = onOpenRoute)
        }
    }
}

// ── Cover (small square watch-adjacent form factor) ────────────────────────────

@Composable
private fun NotificationsCover(
    onBack: () -> Unit,
    onOpenRoute: (route: String, anchor: String?) -> Unit,
) {
    val vm: NotificationCenterViewModel = viewModel(
        factory = ledgerViewModelFactory {
            NotificationCenterViewModel(it.useCases.getNotifications, it.tokenStore)
        },
    )
    val state by vm.uiState.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CanvasBlack)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { onBack() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = TextSecondary)
            }
            Spacer(Modifier.width(4.dp))
            Text(
                text = "Notifications",
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary,
                fontFamily = DisplayFamily,
                modifier = Modifier.weight(1f),
            )
            if (state.unreadCount > 0) {
                Text(
                    text = "Mark all read",
                    style = MaterialTheme.typography.labelSmall,
                    color = AccentTeal,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { vm.markAllRead() }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        NotificationsBody(state = state, vm = vm, onOpenRoute = onOpenRoute)
    }
}

// ── Shared body — used by both Main and Cover ──────────────────────────────────

@Composable
private fun NotificationsBody(
    state: NotificationCenterUiState,
    vm: NotificationCenterViewModel,
    onOpenRoute: (route: String, anchor: String?) -> Unit,
) {
    when {
        state.loading -> Box(
            Modifier.fillMaxWidth().padding(40.dp),
            contentAlignment = Alignment.Center,
        ) { CircularProgressIndicator(color = AccentTeal) }

        state.items.isEmpty() -> EmptyNotifications()

        else -> state.items.forEach { item ->
            NotificationCard(
                notification = item.notification,
                read = item.read,
                onClick = {
                    vm.markRead(item.notification.id)
                    item.notification.route?.let { onOpenRoute(it, item.notification.anchor) }
                },
            )
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun NotificationCard(
    notification: NotificationDto,
    read: Boolean,
    onClick: () -> Unit,
) {
    val accent = if (notification.severity == "warning") DebitRose else AccentTeal
    CredCard(modifier = Modifier.clickable { onClick() }) {
        Row(verticalAlignment = Alignment.Top) {
            // Unread marker: a small accent dot; read items show none.
            Box(
                modifier = Modifier
                    .padding(top = 6.dp)
                    .size(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (read) Color.Transparent else accent),
            )
            Spacer(Modifier.width(10.dp))
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(accent.copy(alpha = 0.15f), RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    typeIcon(notification.type), notification.type,
                    tint = if (read) TextMuted else accent,
                    modifier = Modifier.size(18.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = notification.title,
                    color = if (read) TextSecondary else TextPrimary,
                    fontWeight = if (read) FontWeight.Normal else FontWeight.SemiBold,
                    style = MaterialTheme.typography.titleSmall,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = notification.body,
                    color = TextMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun EmptyNotifications() {
    CredCard {
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .background(AccentGreen.copy(alpha = 0.15f), RoundedCornerShape(18.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Notifications, "None", tint = AccentGreen,
                    modifier = Modifier.size(28.dp))
            }
            Spacer(Modifier.height(14.dp))
            Text(
                text = "You're all caught up",
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Bill reminders, budget alerts and nudges will show up here.",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
            )
        }
    }
}
