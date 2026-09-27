# Money Radar — Android Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
>
> **Prerequisite:** the Money Radar **backend** plan (`2026-06-04-money-radar-backend.md`) must be implemented first — this consumes `/money-radar*`.

**Goal:** Show "hidden money" findings in the app via three entry points — a Dashboard "You might've missed" strip, a 4th Insights-hub tile, and a dedicated **Money Radar** screen — all reading the backend `/money-radar` API.

**Architecture:** Standard Ledger Noir layering — Retrofit DTOs in `ExpenseApi.kt` → `ExpenseRepository` (`Result`-wrapped) → use cases in `domain/UseCases.kt` → a `MoneyRadarViewModel` (`StateFlow<UiState>`) → Compose screen. The Dashboard strip reuses the reactive-badge pattern already used by the notification bell.

**Tech Stack:** Kotlin, Jetpack Compose (Material3), Moshi (codegen adapters), Retrofit, coroutines/Flow.

**Conventions (project standards):** senior-level idiomatic Compose; **no early returns inside composables**; reuse the design system (`CredCard`, `MoneyText`, `AccentTeal`, `AccentGreen`, `CanvasBlack`, `SurfaceMid`, `TextPrimary/Secondary/Muted`).

**Verify (no Compose unit tests in this module):** each code task ends by compiling.
Build command (from `android/`): `./gradlew :app:compileDebugKotlin`

**Spec:** `docs/superpowers/specs/2026-06-04-money-radar-design.md`

---

## File Structure

**Modify:**
- `data/api/ExpenseApi.kt` — add `FindingDto`, `MoneyRadarResponse`, `RadarSummary`, `RadarActionResponse` + 5 endpoints
- `data/repository/ExpenseRepository.kt` — add `moneyRadar()`, `radarSummary()`, `scanRadar()`, `markRadarSeen()`, `dismissFinding()`
- `domain/usecase/MoneyRadarUseCases.kt` — **create** the use cases
- `domain/UseCases.kt` — register them in the container
- `ui/insights/InsightsScreen.kt` — add `onOpenMoneyRadar` + a 4th hub tile with badge
- `ui/dashboard/DashboardViewModel.kt` — add `radarNewCount` to state + fetch
- `ui/dashboard/DashboardScreen.kt` — add the `MoneyRadarStripCard` + `onOpenMoneyRadar`
- `ui/shell/AppShell.kt` — add `Screen.MoneyRadar`, its `composable(...)`, and nav wiring from Dashboard + Insights

**Create:**
- `ui/insights/MoneyRadarUiState.kt`
- `ui/insights/MoneyRadarViewModel.kt`
- `ui/insights/MoneyRadarScreen.kt`

---

## Phase A — Data layer

### Task 1: DTOs + API endpoints

**Files:** Modify `data/api/ExpenseApi.kt`

- [ ] **Step 1: Add the DTOs**

Add near the other response DTOs (e.g. after `NotificationsResponse`):
```kotlin
@JsonClass(generateAdapter = true)
data class FindingDto(
    val id: Int,
    val type: String,
    val severity: String,
    val title: String,
    val detail: String,
    val amount: Double?,
    val currency: String,
    @Json(name = "occurred_at") val occurredAt: String,
    val status: String,
    val query: InsightQuery? = null,   // reuse the existing drill-down DTO
)

@JsonClass(generateAdapter = true)
data class MoneyRadarResponse(
    val findings: List<FindingDto>,
    @Json(name = "new_count") val newCount: Int,
)

@JsonClass(generateAdapter = true)
data class RadarSummary(
    @Json(name = "new_count") val newCount: Int,
    val top: List<FindingDto>,
)

@JsonClass(generateAdapter = true)
data class RadarActionResponse(val ok: Boolean = true, val scanned: Int? = null)
```

- [ ] **Step 2: Add the endpoints to the `ExpenseApi` interface**

Add beside `@GET("notifications")`:
```kotlin
    @GET("money-radar")
    suspend fun moneyRadar(): MoneyRadarResponse

    @GET("money-radar/summary")
    suspend fun radarSummary(): RadarSummary

    @POST("money-radar/scan")
    suspend fun scanRadar(): RadarActionResponse

    @POST("money-radar/seen")
    suspend fun markRadarSeen(): RadarActionResponse

    @POST("money-radar/{id}/dismiss")
    suspend fun dismissFinding(@Path("id") id: Int): RadarActionResponse
```
(`@Path` is already imported — used by `budget(@Path("month") ...)`.)

- [ ] **Step 3: Compile**

Run (from `android/`): `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/ledgernoir/tracker/data/api/ExpenseApi.kt
git commit -m "feat(money-radar): API DTOs + endpoints (android)"
```

---

### Task 2: Repository methods + use cases + container registration

**Files:** Modify `data/repository/ExpenseRepository.kt`, create `domain/usecase/MoneyRadarUseCases.kt`, modify `domain/UseCases.kt`

- [ ] **Step 1: Add repository methods**

Add beside `notifications()` in `ExpenseRepository.kt`:
```kotlin
    suspend fun moneyRadar(): Result<MoneyRadarResponse> =
        runCatching { api().moneyRadar() }

    suspend fun radarSummary(): Result<RadarSummary> =
        runCatching { api().radarSummary() }

    suspend fun scanRadar(): Result<RadarActionResponse> =
        runCatching { api().scanRadar() }

    suspend fun markRadarSeen(): Result<RadarActionResponse> =
        runCatching { api().markRadarSeen() }

    suspend fun dismissFinding(id: Int): Result<RadarActionResponse> =
        runCatching { api().dismissFinding(id) }
```
(Ensure the new DTO types resolve — they live in the same `data.api` package already imported by this file.)

- [ ] **Step 2: Create the use cases**

Create `domain/usecase/MoneyRadarUseCases.kt`:
```kotlin
package com.ledgernoir.tracker.domain.usecase

import com.ledgernoir.tracker.data.api.MoneyRadarResponse
import com.ledgernoir.tracker.data.api.RadarActionResponse
import com.ledgernoir.tracker.data.api.RadarSummary
import com.ledgernoir.tracker.data.repository.ExpenseRepository

/** Full Money Radar feed (all active findings + new-count). */
class GetMoneyRadarUseCase(private val repo: ExpenseRepository) {
    suspend operator fun invoke(): Result<MoneyRadarResponse> = repo.moneyRadar()
}

/** Lightweight summary for the Dashboard strip (new-count + top 3). */
class GetRadarSummaryUseCase(private val repo: ExpenseRepository) {
    suspend operator fun invoke(): Result<RadarSummary> = repo.radarSummary()
}

/** Marks all NEW findings as seen (called when the screen opens). */
class MarkRadarSeenUseCase(private val repo: ExpenseRepository) {
    suspend operator fun invoke(): Result<RadarActionResponse> = repo.markRadarSeen()
}

/** Dismisses a single finding. */
class DismissFindingUseCase(private val repo: ExpenseRepository) {
    suspend operator fun invoke(id: Int): Result<RadarActionResponse> = repo.dismissFinding(id)
}
```

- [ ] **Step 3: Register in the container**

In `domain/UseCases.kt`, add the imports and, after `val getNotifications = GetNotificationsUseCase(repo)`:
```kotlin
    // Money Radar
    val getMoneyRadar = GetMoneyRadarUseCase(repo)
    val getRadarSummary = GetRadarSummaryUseCase(repo)
    val markRadarSeen = MarkRadarSeenUseCase(repo)
    val dismissFinding = DismissFindingUseCase(repo)
```
Add to the import block at the top:
```kotlin
import com.ledgernoir.tracker.domain.usecase.DismissFindingUseCase
import com.ledgernoir.tracker.domain.usecase.GetMoneyRadarUseCase
import com.ledgernoir.tracker.domain.usecase.GetRadarSummaryUseCase
import com.ledgernoir.tracker.domain.usecase.MarkRadarSeenUseCase
```

- [ ] **Step 4: Compile**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/ledgernoir/tracker/data/repository/ExpenseRepository.kt \
        app/src/main/java/com/ledgernoir/tracker/domain/usecase/MoneyRadarUseCases.kt \
        app/src/main/java/com/ledgernoir/tracker/domain/UseCases.kt
git commit -m "feat(money-radar): repository + use cases (android)"
```

---

## Phase B — Money Radar screen

### Task 3: UiState + ViewModel

**Files:** Create `ui/insights/MoneyRadarUiState.kt`, `ui/insights/MoneyRadarViewModel.kt`

- [ ] **Step 1: Create the UiState**

`ui/insights/MoneyRadarUiState.kt`:
```kotlin
package com.ledgernoir.tracker.ui.insights

import com.ledgernoir.tracker.data.api.FindingDto

data class MoneyRadarUiState(
    val loading: Boolean = true,
    val findings: List<FindingDto> = emptyList(),
    val newCount: Int = 0,
    val error: String? = null,
)
```

- [ ] **Step 2: Create the ViewModel**

`ui/insights/MoneyRadarViewModel.kt` — mirror `NotificationCenterViewModel` (ctor takes use cases; `StateFlow`; marks seen on load):
```kotlin
package com.ledgernoir.tracker.ui.insights

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ledgernoir.tracker.domain.usecase.DismissFindingUseCase
import com.ledgernoir.tracker.domain.usecase.GetMoneyRadarUseCase
import com.ledgernoir.tracker.domain.usecase.MarkRadarSeenUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class MoneyRadarViewModel(
    private val getMoneyRadar: GetMoneyRadarUseCase,
    private val markRadarSeen: MarkRadarSeenUseCase,
    private val dismissFinding: DismissFindingUseCase,
) : ViewModel() {

    private val _uiState = MutableStateFlow(MoneyRadarUiState())
    val uiState: StateFlow<MoneyRadarUiState> = _uiState.asStateFlow()

    init { load() }

    fun load() {
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true, error = null) }
            getMoneyRadar().onSuccess { resp ->
                _uiState.update {
                    it.copy(loading = false, findings = resp.findings, newCount = resp.newCount)
                }
                // Opening the screen clears the "new" state server-side.
                markRadarSeen()
            }.onFailure { e ->
                _uiState.update { it.copy(loading = false, error = e.message ?: "Failed to load") }
            }
        }
    }

    fun dismiss(id: Int) {
        viewModelScope.launch {
            dismissFinding(id).onSuccess {
                _uiState.update { s -> s.copy(findings = s.findings.filterNot { it.id == id }) }
            }
        }
    }
}
```

- [ ] **Step 3: Compile**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/ledgernoir/tracker/ui/insights/MoneyRadarUiState.kt \
        app/src/main/java/com/ledgernoir/tracker/ui/insights/MoneyRadarViewModel.kt
git commit -m "feat(money-radar): UiState + ViewModel (android)"
```

---

### Task 4: `MoneyRadarScreen`

Uniform finding cards (the backend returns a uniform `FindingDto`), grouped under severity section headers, severity-colored accent, optional drill-down via `query`, dismiss via overflow.

**Files:** Create `ui/insights/MoneyRadarScreen.kt`

- [ ] **Step 1: Create the screen**

`ui/insights/MoneyRadarScreen.kt`:
```kotlin
package com.ledgernoir.tracker.ui.insights

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ledgernoir.tracker.data.api.FindingDto
import com.ledgernoir.tracker.data.repository.ExpenseRepository
import com.ledgernoir.tracker.ui.common.ledgerViewModelFactory
import com.ledgernoir.tracker.ui.designsystem.CredCard
import com.ledgernoir.tracker.ui.designsystem.MoneyText
import com.ledgernoir.tracker.ui.theme.AccentGreen
import com.ledgernoir.tracker.ui.theme.AccentTeal
import com.ledgernoir.tracker.ui.theme.CanvasBlack
import com.ledgernoir.tracker.ui.theme.SurfaceMid
import com.ledgernoir.tracker.ui.theme.TextMuted
import com.ledgernoir.tracker.ui.theme.TextPrimary
import com.ledgernoir.tracker.ui.theme.TextSecondary

private fun severityColor(severity: String): Color = when (severity) {
    "security" -> Color(0xFFEF4444)
    "warning" -> Color(0xFFFBBF24)
    "action" -> AccentTeal
    "positive" -> AccentGreen
    else -> TextSecondary
}

private fun severityLabel(severity: String): String = when (severity) {
    "security" -> "Needs attention"
    "warning" -> "Worth a look"
    "action" -> "New commitments"
    "positive" -> "Money owed back"
    else -> "Good to know"
}

private val SEVERITY_ORDER = listOf("security", "action", "warning", "positive", "info")

/**
 * MONEY RADAR — charges & commitments the app surfaces nowhere else: auto-pay
 * mandates, forex markup, hidden card costs, duplicates, refunds, declines.
 *
 * @param onDrillDown (merchant, categoryId) — opens the existing transaction list.
 */
@Composable
fun MoneyRadarScreen(
    repository: ExpenseRepository,
    onDrillDown: (merchant: String?, categoryId: Int?) -> Unit = { _, _ -> },
) {
    val vm: MoneyRadarViewModel = viewModel(
        factory = ledgerViewModelFactory {
            MoneyRadarViewModel(it.useCases.getMoneyRadar, it.useCases.markRadarSeen,
                it.useCases.dismissFinding)
        },
    )
    val state by vm.uiState.collectAsStateWithLifecycle()
    val grouped = state.findings.groupBy { it.severity }

    Column(
        modifier = Modifier.fillMaxSize().background(CanvasBlack),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp, 20.dp),
        ) {
            item {
                Text("Money Radar", style = MaterialTheme.typography.headlineMedium, color = TextPrimary)
                Spacer(Modifier.height(4.dp))
                Text("Charges & commitments you might've missed.",
                    style = MaterialTheme.typography.bodyMedium, color = TextMuted)
                Spacer(Modifier.height(20.dp))
            }

            when {
                state.loading -> item {
                    Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = AccentTeal)
                    }
                }
                state.findings.isEmpty() -> item {
                    CredCard {
                        Text(state.error ?: "Nothing on your radar — you're all clear. ✨",
                            style = MaterialTheme.typography.bodyMedium, color = TextSecondary,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp))
                    }
                }
                else -> SEVERITY_ORDER.forEach { sev ->
                    val rows = grouped[sev].orEmpty()
                    if (rows.isNotEmpty()) {
                        item {
                            Spacer(Modifier.height(4.dp))
                            Text(severityLabel(sev).uppercase(),
                                style = MaterialTheme.typography.labelSmall,
                                color = severityColor(sev), letterSpacing = 1.4.sp)
                            Spacer(Modifier.height(10.dp))
                        }
                        items(rows, key = { it.id }) { finding ->
                            FindingCard(
                                finding = finding,
                                onDismiss = { vm.dismiss(finding.id) },
                                onClick = {
                                    val q = finding.query
                                    if (q != null) onDrillDown(q.merchant, q.categoryId)
                                },
                            )
                            Spacer(Modifier.height(12.dp))
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(8.dp)) }
        }
    }
}

@Composable
private fun FindingCard(finding: FindingDto, onDismiss: () -> Unit, onClick: () -> Unit) {
    val accent = severityColor(finding.severity)
    CredCard {
        Row(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.size(10.dp).clip(CircleShape).background(accent),
            )
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(finding.title, style = MaterialTheme.typography.bodyLarge, color = TextPrimary,
                    fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(finding.detail, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            }
            Spacer(Modifier.width(12.dp))
            Column(horizontalAlignment = Alignment.End) {
                if (finding.amount != null) {
                    MoneyText(amount = finding.amount, fontSize = 16.sp, color = TextPrimary, showPaise = false)
                }
                Spacer(Modifier.height(6.dp))
                Box(
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(SurfaceMid)
                        .clickable(onClick = onDismiss).padding(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    Text("Dismiss", style = MaterialTheme.typography.labelSmall, color = TextMuted)
                }
            }
        }
    }
}
```

> Note: confirm `InsightQuery` exposes `merchant` and `categoryId` (it backs the existing nudge drill-down in `InsightsScreen`). If the property is named differently, match it here.

- [ ] **Step 2: Compile**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL (fix any design-system import names if they differ — e.g. confirm `CredCard`, `MoneyText` package paths from `InsightsScreen.kt`).

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/ledgernoir/tracker/ui/insights/MoneyRadarScreen.kt
git commit -m "feat(money-radar): Money Radar screen (android)"
```

---

### Task 5: Route + Insights-hub tile

**Files:** Modify `ui/shell/AppShell.kt`, `ui/insights/InsightsScreen.kt`

- [ ] **Step 1: Add the route to the `Screen` sealed class**

In `ui/shell/AppShell.kt`, beside the other objects (after `Notifications`):
```kotlin
    object MoneyRadar   : Screen("money_radar",  "Money Radar")
```

- [ ] **Step 2: Add the `composable` block**

In `AppShell.kt`, beside `composable(Screen.Notifications.route) { ... }`:
```kotlin
            composable(Screen.MoneyRadar.route) {
                MoneyRadarScreen(
                    repository = repository,
                    onDrillDown = { _, catId ->
                        // Category-backed findings open the full transaction list; merchant-only
                        // findings are informative on their own and don't navigate.
                        if (catId != null) navController.navigate(Screen.Transactions.route)
                    },
                )
            }
```
> Use the same `repository` handle the other `composable` blocks receive. Keep the drill-down minimal — the finding card is informative on its own; a richer category+date drill-down can reuse the Insights `txncat/...` route later if wanted.

- [ ] **Step 3: Wire navigation from the Insights hub**

In `AppShell.kt`, where `InsightsScreen(...)` is invoked (~line 291), add the new callback:
```kotlin
                    onOpenMoneyRadar = { navController.navigate(Screen.MoneyRadar.route) },
```

In `ui/insights/InsightsScreen.kt`, add the parameter to the signature:
```kotlin
    onOpenMoneyRadar: () -> Unit = {},
```
and add a 4th tile in the hub. Replace the second tile `Row` (the Budgets row) so it includes Money Radar:
```kotlin
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        InsightsNavTile("Budgets", "Caps · overspend alerts",
                            Color(0xFFFBBF24), Modifier.weight(1f), onOpenBudget)
                        InsightsNavTile("Money Radar", "Mandates · forex · fees",
                            Color(0xFFEF4444), Modifier.weight(1f), onOpenMoneyRadar)
                    }
```

- [ ] **Step 4: Compile**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/ledgernoir/tracker/ui/shell/AppShell.kt \
        app/src/main/java/com/ledgernoir/tracker/ui/insights/InsightsScreen.kt
git commit -m "feat(money-radar): route + Insights-hub tile (android)"
```

---

## Phase C — Dashboard strip + badge

### Task 6: `radarNewCount` in DashboardViewModel

**Files:** Modify `ui/dashboard/DashboardViewModel.kt`, `ui/dashboard/DashboardUiState` (wherever `unreadNotifications` lives)

- [ ] **Step 1: Add to the dashboard state + fetch**

Mirror the existing `unreadNotifications` badge. Add a field to the dashboard UI state:
```kotlin
    val radarNewCount: Int = 0,
    val radarTop: List<com.ledgernoir.tracker.data.api.FindingDto> = emptyList(),
```
Inject the use case into `DashboardViewModel` constructor (beside `getNotifications`):
```kotlin
    private val getRadarSummary: com.ledgernoir.tracker.domain.usecase.GetRadarSummaryUseCase,
```
And in the same place the VM loads notifications (its `init`/load), add:
```kotlin
        viewModelScope.launch {
            getRadarSummary().onSuccess { s ->
                _uiState.update { it.copy(radarNewCount = s.newCount, radarTop = s.top) }
            }
        }
```
Update the `DashboardViewModel` construction site (the `ledgerViewModelFactory { ... }` in `DashboardScreen.kt`) to pass `it.useCases.getRadarSummary`.

- [ ] **Step 2: Compile**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/ledgernoir/tracker/ui/dashboard/DashboardViewModel.kt \
        app/src/main/java/com/ledgernoir/tracker/ui/dashboard/DashboardScreen.kt
git commit -m "feat(money-radar): dashboard radar summary in viewmodel (android)"
```

---

### Task 7: `MoneyRadarStripCard` on the Dashboard

**Files:** Modify `ui/dashboard/DashboardScreen.kt`, `ui/shell/AppShell.kt`

- [ ] **Step 1: Add the strip composable**

Add to `DashboardScreen.kt`:
```kotlin
@Composable
private fun MoneyRadarStripCard(newCount: Int, summary: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(SurfaceMid).clickable(onClick = onClick).padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Visibility, contentDescription = null, tint = Color(0xFFEF4444),
                modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("You might've missed", style = MaterialTheme.typography.titleSmall,
                    color = TextPrimary, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(2.dp))
                Text(summary, style = MaterialTheme.typography.labelSmall, color = TextMuted,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(8.dp))
            Text("See all →", style = MaterialTheme.typography.labelMedium, color = Color(0xFFEF4444))
        }
    }
}
```
(Use `Icons.Filled.Visibility`; add the import `androidx.compose.material.icons.filled.Visibility`. Reuse imports already present in the file for the rest.)

- [ ] **Step 2: Render it conditionally near the top of the dashboard content**

Where the dashboard lays out its top cards, add (using the state from Task 6):
```kotlin
            if (state.radarNewCount > 0) {
                val summary = state.radarTop.take(2).joinToString(" · ") { it.title }
                    .ifBlank { "${state.radarNewCount} new finding${if (state.radarNewCount == 1) "" else "s"}" }
                MoneyRadarStripCard(state.radarNewCount, summary, onOpenMoneyRadar)
                Spacer(Modifier.height(16.dp))
            }
```

- [ ] **Step 3: Add the `onOpenMoneyRadar` param to `DashboardScreen` + wire from AppShell**

In `DashboardScreen.kt` signature, beside `onOpenNotifications`:
```kotlin
    onOpenMoneyRadar: () -> Unit = {},
```
In `AppShell.kt`, where `DashboardScreen(...)` is invoked, add:
```kotlin
                    onOpenMoneyRadar = { navController.navigate(Screen.MoneyRadar.route) },
```

- [ ] **Step 4: Compile**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/ledgernoir/tracker/ui/dashboard/DashboardScreen.kt \
        app/src/main/java/com/ledgernoir/tracker/ui/shell/AppShell.kt
git commit -m "feat(money-radar): dashboard 'You might've missed' strip (android)"
```

---

## Phase D — Build & verify on device

### Task 8: Full build + manual smoke

- [ ] **Step 1: Assemble the debug APK**

Run (from `android/`): `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: Run against the backend seeded with `emu.db`**

Start the backend on `emu.db` (see backend plan Task 16), install the app, and verify:
- Dashboard shows the **"You might've missed"** strip with a count when findings exist.
- Tapping it (and the Insights **Money Radar** tile) opens the screen.
- Findings are grouped (Spotify price hike, Anthropic duplicate, mandates, forex markup, the ₹20,430 credit balance).
- **Dismiss** removes a finding; reopening the screen clears the dashboard badge (seen).

- [ ] **Step 3: Commit any UI fixes**

```bash
git add -A && git commit -m "fix(money-radar): UI polish after device smoke"
```

---

## Self-Review

**Spec coverage (§8):** dashboard strip → Tasks 6–7; Money Radar screen → Tasks 3–4; Insights 4th tile → Task 5; data layer (`MoneyRadarApi`, repo, use case) → Tasks 1–2. The "both" entry-point decision (strip + screen + tile) is fully covered.

**Type consistency:** `FindingDto` fields (Task 1) are consumed unchanged by the ViewModel (Task 3) and screen (Task 4). `RadarSummary.top: List<FindingDto>` (Task 1) matches `radarTop` in dashboard state (Task 6) and the strip summary (Task 7). Use-case names (`getMoneyRadar`, `getRadarSummary`, `markRadarSeen`, `dismissFinding`) are identical across `MoneyRadarUseCases.kt`, the container, the ViewModel factory, and the dashboard factory.

**Assumptions to verify during execution (each flagged inline):** the exact property names on `InsightQuery` (`merchant`/`categoryId`), the design-system import paths (`CredCard`, `MoneyText`, theme colors) — both lifted from `InsightsScreen.kt`; and the dashboard UI-state file name/badge pattern (mirror `unreadNotifications`). These are confirmed by `./gradlew :app:compileDebugKotlin` at the end of each task.
```
