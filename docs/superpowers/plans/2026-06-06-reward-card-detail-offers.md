# Reward Card Detail Screen + Offers Catalog Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Tapping a card on the Rewards screen opens a per-card detail screen showing the points hero, a curated benefits/offers catalog, a live-portal deep link, redemption options, and full history.

**Architecture:** No new endpoint — extend the static `reward_catalogs.py` with `offer_categories` and surface `portal_url` + `offer_categories` in the existing `/rewards` payload. A thin `RewardDetailViewModel` reuses `GetRewardsUseCase` and filters to one card by `bank+last4`. New `RewardDetailScreen` reached via a `rewarddetail/{bank}/{last4}` route from tappable cards on `RewardsScreen`.

**Tech Stack:** Python FastAPI + pytest; Kotlin + Jetpack Compose (Material3), Retrofit/Moshi, AndroidX Navigation.

---

## Testing conventions
- **Backend:** `cd backend && .venv/bin/pytest <path> -v` (in-memory `client` fixture, see `tests/test_api_rewards.py`).
- **Android:** compile gate `cd android && export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:compileDebugKotlin --console=plain 2>&1 | tail -4` → BUILD SUCCESSFUL. Final on-device verify in Task 6 via `run-app`.
- **No early returns inside composables** — if/else only.

---

## File Structure
- Modify `backend/app/data/reward_catalogs.py` — `offer_categories` per program + `offer_categories_for()`.
- Modify `backend/app/routers/rewards.py` — surface `portal_url` + `offer_categories`.
- Modify `backend/tests/test_api_rewards.py` — assert the new fields.
- Modify `android/.../data/api/ExpenseApi.kt` — `RewardOfferCategoryDto` + 2 new `RewardCardDto` fields.
- Create `android/.../ui/rewards/RewardDetailUiState.kt`, `RewardDetailViewModel.kt`, `RewardDetailScreen.kt`.
- Modify `android/.../ui/rewards/RewardsScreen.kt` — tappable cards + `onOpenCard`.
- Modify `android/.../ui/shell/AppShell.kt` — `rewarddetail/{bank}/{last4}` route.

---

## Task 1: Backend — offer_categories in catalog + /rewards payload

**Files:**
- Modify: `backend/app/data/reward_catalogs.py`
- Modify: `backend/app/routers/rewards.py`
- Test: `backend/tests/test_api_rewards.py`

- [ ] **Step 1: Write the failing tests**

Append to `backend/tests/test_api_rewards.py`:

```python
async def test_offer_categories_helper():
    from app.data.reward_catalogs import offer_categories_for
    idfc = offer_categories_for("IDFC_FIRST")
    assert idfc and all(o["title"] and o["kind"] for o in idfc)
    assert offer_categories_for("UNKNOWN") == []


async def test_rewards_card_includes_offers_and_portal(client):
    c, Session = client
    from app.repositories import CardRewardRepository
    async with Session() as s:
        await CardRewardRepository(s).upsert_for_card(
            bank="IDFC", last4="0684", program="IDFC_FIRST", points_balance=12400,
            earned=300, redeemed=0, expiring_points=0, expiring_date=None,
            statement_period="MAY 2026", as_of="2026-06-02")
        await s.commit()
    r = await c.get("/rewards")
    card = r.json()["cards"][0]
    assert card["portal_url"]
    assert card["offer_categories"]
    first = card["offer_categories"][0]
    assert first["title"] and first["description"] and first["kind"]
```

- [ ] **Step 2: Run to verify they fail**

Run: `cd backend && .venv/bin/pytest tests/test_api_rewards.py -k "offer_categories or includes_offers" -v`
Expected: FAIL — `ImportError: cannot import name 'offer_categories_for'`.

- [ ] **Step 3: Add `offer_categories` + helper to the catalog**

In `backend/app/data/reward_catalogs.py`, add an `"offer_categories": [...]` key inside the `IDFC_FIRST` dict (after its `partners` list):

```python
        "offer_categories": [
            {"title": "Airport lounge", "description": "Complimentary domestic lounge visits on milestone spends.", "kind": "lounge"},
            {"title": "Dining", "description": "Discounts at partner restaurants.", "kind": "dining"},
            {"title": "Movies", "description": "Buy-1-Get-1 movie tickets via partner.", "kind": "movies"},
            {"title": "Fuel", "description": "1% fuel surcharge waiver.", "kind": "fuel"},
            {"title": "Online brands", "description": "Accelerated reward points on online spends.", "kind": "shopping"},
            {"title": "Low forex markup", "description": "Reduced markup on international spends.", "kind": "forex"},
        ],
```

And inside the `AXIS_EDGE` dict (after its `partners` list):

```python
        "offer_categories": [
            {"title": "Dining Delights", "description": "Savings at partner restaurants.", "kind": "dining"},
            {"title": "Airport lounge", "description": "Complimentary lounge access on eligible cards.", "kind": "lounge"},
            {"title": "Fuel", "description": "Fuel surcharge waiver.", "kind": "fuel"},
            {"title": "EDGE partner brands", "description": "Offers across shopping & travel partners.", "kind": "shopping"},
        ],
```

Then add the helper at the bottom of the file (after `points_to_inr`):

```python
def offer_categories_for(program: str) -> list[dict]:
    cat = _CATALOG.get(program)
    return cat.get("offer_categories", []) if cat else []
```

- [ ] **Step 4: Surface the fields in the router**

In `backend/app/routers/rewards.py`, inside the `out_cards.append({...})` dict (where `cat = catalog_for(card.program) or {}` is already in scope), add these two keys:

```python
            "portal_url": cat.get("portal_url"),
            "offer_categories": cat.get("offer_categories", []),
```

- [ ] **Step 5: Run to verify they pass**

Run: `cd backend && .venv/bin/pytest tests/test_api_rewards.py -v`
Expected: PASS (all, including the 2 new).

- [ ] **Step 6: Commit**

```bash
cd /Users/harshkhandelwal/Documents/expense-tracker
git add backend/app/data/reward_catalogs.py backend/app/routers/rewards.py backend/tests/test_api_rewards.py
git commit -m "$(cat <<'EOF'
feat(backend): per-card offer categories + portal in /rewards

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 2: Android — offer DTO + RewardCardDto fields

**Files:**
- Modify: `android/app/src/main/java/com/ledgernoir/tracker/data/api/ExpenseApi.kt`

- [ ] **Step 1: Add the offer DTO + extend RewardCardDto**

In `data/api/ExpenseApi.kt`, add near the other reward DTOs:

```kotlin
@JsonClass(generateAdapter = true)
data class RewardOfferCategoryDto(
    val title: String,
    val description: String,
    val kind: String,
)
```

Then add these two properties to `RewardCardDto` (e.g. after `redemptionOptions`):

```kotlin
    @Json(name = "portal_url") val portalUrl: String? = null,
    @Json(name = "offer_categories") val offerCategories: List<RewardOfferCategoryDto> = emptyList(),
```

- [ ] **Step 2: Compile**

Run: `cd android && export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:compileDebugKotlin --console=plain 2>&1 | tail -4`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
cd /Users/harshkhandelwal/Documents/expense-tracker
git add android/app/src/main/java/com/ledgernoir/tracker/data/api/ExpenseApi.kt
git commit -m "$(cat <<'EOF'
feat(android): reward offer-category DTO + card portal/offers fields

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 3: Android — RewardDetailUiState + RewardDetailViewModel

**Files:**
- Create: `android/app/.../ui/rewards/RewardDetailUiState.kt`
- Create: `android/app/.../ui/rewards/RewardDetailViewModel.kt`

- [ ] **Step 1: Create the UiState**

Create `ui/rewards/RewardDetailUiState.kt`:

```kotlin
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
```

- [ ] **Step 2: Create the ViewModel**

Create `ui/rewards/RewardDetailViewModel.kt`:

```kotlin
package com.ledgernoir.tracker.ui.rewards

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ledgernoir.tracker.domain.usecase.GetRewardsUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Backs [RewardDetailScreen]. Fetches /rewards and exposes the single card matching
 * [bank]/[last4]. Reuses GetRewardsUseCase (no dedicated endpoint — only a couple of
 * cards). refresh() re-fetches on resume.
 */
class RewardDetailViewModel(
    private val getRewards: GetRewardsUseCase,
    private val bank: String,
    private val last4: String,
) : ViewModel() {

    private val _uiState = MutableStateFlow(RewardDetailUiState())
    val uiState: StateFlow<RewardDetailUiState> = _uiState.asStateFlow()

    init { load() }

    fun refresh() = load()

    private fun load() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(loading = true)
            getRewards().onSuccess { resp ->
                val match = resp.cards.firstOrNull {
                    it.bank.equals(bank, ignoreCase = true) && it.last4 == last4
                }
                _uiState.value = _uiState.value.copy(card = match)
            }
            _uiState.value = _uiState.value.copy(loading = false)
        }
    }
}
```

- [ ] **Step 3: Compile**

Run: `cd android && export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:compileDebugKotlin --console=plain 2>&1 | tail -4`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
cd /Users/harshkhandelwal/Documents/expense-tracker
git add android/app/src/main/java/com/ledgernoir/tracker/ui/rewards/RewardDetailUiState.kt android/app/src/main/java/com/ledgernoir/tracker/ui/rewards/RewardDetailViewModel.kt
git commit -m "$(cat <<'EOF'
feat(android): RewardDetailUiState + RewardDetailViewModel

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 4: Android — RewardDetailScreen

**Files:**
- Create: `android/app/.../ui/rewards/RewardDetailScreen.kt`

- [ ] **Step 1: Create the screen**

Create `ui/rewards/RewardDetailScreen.kt`:

```kotlin
package com.ledgernoir.tracker.ui.rewards

import com.ledgernoir.tracker.ui.designsystem.*

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ledgernoir.tracker.data.api.RewardCardDto
import com.ledgernoir.tracker.data.api.RewardOfferCategoryDto
import com.ledgernoir.tracker.data.repository.ExpenseRepository
import com.ledgernoir.tracker.ui.common.ledgerViewModelFactory
import com.ledgernoir.tracker.ui.theme.AccentGreen
import com.ledgernoir.tracker.ui.theme.AccentTeal
import com.ledgernoir.tracker.ui.theme.CanvasBlack
import com.ledgernoir.tracker.ui.theme.DisplayFamily
import com.ledgernoir.tracker.ui.theme.SurfaceMid
import com.ledgernoir.tracker.ui.theme.TextMuted
import com.ledgernoir.tracker.ui.theme.TextPrimary
import com.ledgernoir.tracker.ui.theme.TextSecondary

private val AmberWarn = Color(0xFFFBBF24)

private fun cardAccent(bank: String): Color = when (bank.uppercase()) {
    "AXIS" -> Color(0xFFAE275F)
    "BOB" -> Color(0xFFF26522)
    "IDFC" -> Color(0xFF9C1D26)
    else -> AccentTeal
}

private fun offerEmoji(kind: String): String = when (kind) {
    "lounge", "travel" -> "✈️"
    "dining" -> "🍽️"
    "movies" -> "🎬"
    "fuel" -> "⛽"
    "shopping" -> "🛍️"
    "forex" -> "🌍"
    else -> "🎁"
}

@Composable
fun RewardDetailScreen(
    repository: ExpenseRepository,
    bank: String,
    last4: String,
    onBack: () -> Unit,
) {
    val vm: RewardDetailViewModel = viewModel(
        factory = ledgerViewModelFactory { RewardDetailViewModel(it.useCases.getRewards, bank, last4) },
    )
    val state by vm.uiState.collectAsStateWithLifecycle()
    val haptics = LocalHapticFeedback.current
    val context = LocalContext.current
    val card = state.card

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
                        .clickable {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            onBack()
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = TextSecondary)
                }
                Spacer(Modifier.width(4.dp))
                Text(
                    text = card?.let { "${it.displayName} ••${it.last4}" } ?: "Card",
                    style = MaterialTheme.typography.headlineSmall,
                    color = TextPrimary, fontFamily = DisplayFamily,
                )
            }
            Spacer(Modifier.height(20.dp))

            if (state.loading && card == null) {
                Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = AccentTeal)
                }
            } else if (card == null) {
                CredCard {
                    Text("Card not found", color = TextPrimary,
                        style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                }
            } else {
                DetailHero(card)
                if (card.offerCategories.isNotEmpty()) {
                    Spacer(Modifier.height(20.dp))
                    SectionLabel("Card benefits & offers")
                    Spacer(Modifier.height(10.dp))
                    CredCard {
                        card.offerCategories.forEachIndexed { i, o ->
                            OfferRow(o)
                            if (i < card.offerCategories.size - 1) Spacer(Modifier.height(14.dp))
                        }
                    }
                }
                if (!card.portalUrl.isNullOrBlank()) {
                    Spacer(Modifier.height(12.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(AccentTeal.copy(alpha = 0.16f))
                            .clickable {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                runCatching {
                                    context.startActivity(
                                        Intent(Intent.ACTION_VIEW, Uri.parse(card.portalUrl)))
                                }
                            }
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("Explore live offers →", color = AccentTeal,
                            style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    }
                }
                if (card.redemptionOptions.isNotEmpty()) {
                    Spacer(Modifier.height(20.dp))
                    SectionLabel("Redeem points")
                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        card.redemptionOptions.forEach { opt ->
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(20.dp))
                                    .background(SurfaceMid)
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                            ) {
                                Text(opt.label, style = MaterialTheme.typography.labelMedium,
                                    color = TextSecondary, maxLines = 1, softWrap = false)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(20.dp))
                SectionLabel("History")
                Spacer(Modifier.height(10.dp))
                if (card.recentEvents.isEmpty()) {
                    Text("No redemptions yet.", color = TextSecondary,
                        style = MaterialTheme.typography.bodyMedium)
                } else {
                    CredCard {
                        card.recentEvents.forEachIndexed { i, ev ->
                            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                val label = if (ev.kind == "voucher")
                                    "🎟 ${ev.voucherCode ?: "Voucher"}"
                                else (ev.merchant ?: "Redeemed")
                                Text(label, color = TextPrimary, modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                                val right = if (ev.kind == "voucher")
                                    "exp ${ev.voucherExpiry ?: "—"}"
                                else "${ev.points ?: 0} pts · ${ev.occurredAt}"
                                Text(right, color = TextMuted,
                                    style = MaterialTheme.typography.labelSmall)
                            }
                            if (i < card.recentEvents.size - 1) Spacer(Modifier.height(6.dp))
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun DetailHero(card: RewardCardDto) {
    val accent = cardAccent(card.bank)
    CredCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(accent))
            Spacer(Modifier.width(10.dp))
            Text("${card.bank} ••${card.last4}", color = TextPrimary,
                style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f))
            Text(card.displayName, color = TextSecondary,
                style = MaterialTheme.typography.labelMedium)
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text("${card.pointsBalance} pts", color = TextPrimary, fontSize = 24.sp,
                fontFamily = DisplayFamily, fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f))
            MoneyText(amount = card.valueInr, fontSize = 20.sp, color = AccentGreen,
                showPaise = false)
        }
        Spacer(Modifier.height(4.dp))
        val asOf = if (card.stale) "as of ${card.asOf} · may be outdated" else "as of ${card.asOf}"
        Text(asOf, color = TextMuted, style = MaterialTheme.typography.labelSmall)
        if (card.expiringPoints > 0 && card.expiringDate != null) {
            Spacer(Modifier.height(10.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(AmberWarn.copy(alpha = 0.14f))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Text("⚠ ${card.expiringPoints} pts expire ${card.expiringDate}",
                    color = AmberWarn, style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun OfferRow(o: RewardOfferCategoryDto) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(offerEmoji(o.kind), fontSize = 20.sp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(o.title, color = TextPrimary, style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(2.dp))
            Text(o.description, color = TextSecondary, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelSmall,
        color = TextSecondary, letterSpacing = 1.4.sp)
}
```

- [ ] **Step 2: Compile**

Run: `cd android && export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:compileDebugKotlin --console=plain 2>&1 | tail -6`
Expected: BUILD SUCCESSFUL. (If `ResponsiveContainer`/`CredCard`/`MoneyText` fail to resolve, cross-check imports against `ui/rewards/RewardsScreen.kt`.)

- [ ] **Step 3: Commit**

```bash
cd /Users/harshkhandelwal/Documents/expense-tracker
git add android/app/src/main/java/com/ledgernoir/tracker/ui/rewards/RewardDetailScreen.kt
git commit -m "$(cat <<'EOF'
feat(android): RewardDetailScreen (benefits, portal link, history)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 5: Android — tappable cards + navigation route

**Files:**
- Modify: `android/app/.../ui/rewards/RewardsScreen.kt`
- Modify: `android/app/.../ui/shell/AppShell.kt`

- [ ] **Step 1: Make Rewards cards tappable**

In `ui/rewards/RewardsScreen.kt`:

Change the `RewardsScreen` signature to add the callback:
```kotlin
fun RewardsScreen(
    repository: ExpenseRepository,
    onBack: () -> Unit,
    onOpenCard: (bank: String, last4: String) -> Unit = { _, _ -> },
) {
```

Change the call site (currently `RewardCard(card)`) to pass the click:
```kotlin
                state.cards.forEachIndexed { i, card ->
                    RewardCard(card, onClick = { onOpenCard(card.bank, card.last4) })
                    if (i < state.cards.size - 1) Spacer(Modifier.height(16.dp))
                }
```

Change `private fun RewardCard(card: RewardCardDto)` to accept the click and apply it to the
`CredCard` (which accepts a `modifier`):
```kotlin
@Composable
private fun RewardCard(card: RewardCardDto, onClick: () -> Unit = {}) {
    val accent = cardAccent(card.bank)
    CredCard(modifier = Modifier.clickable(onClick = onClick)) {
```
Add the import if missing: `import androidx.compose.foundation.clickable`.

- [ ] **Step 2: Add the route in AppShell**

In `ui/shell/AppShell.kt`:

1. Add the import: `import com.ledgernoir.tracker.ui.rewards.RewardDetailScreen`
2. Add a route constant near `ROUTE_TXN_CARD` (line ~92):
```kotlin
private const val ROUTE_REWARD_DETAIL = "rewarddetail/{bank}/{last4}"
```
3. In the `hideBottomBar` matcher (near line ~160 where `ROUTE_TXN_CARD` is listed), add:
```kotlin
                currentDest?.route == ROUTE_REWARD_DETAIL ||
```
4. Update the existing `RewardsScreen(...)` invocation (the `composable("rewards")` block) to pass:
```kotlin
                    onOpenCard = { bank, last4 -> navController.navigate("rewarddetail/$bank/$last4") },
```
5. Add the detail composable next to the `composable("rewards")` block:
```kotlin
            composable(
                route = ROUTE_REWARD_DETAIL,
                arguments = listOf(
                    navArgument("bank") { type = NavType.StringType },
                    navArgument("last4") { type = NavType.StringType },
                ),
            ) { entry ->
                RewardDetailScreen(
                    repository = app.repository,
                    bank = entry.arguments?.getString("bank") ?: "",
                    last4 = entry.arguments?.getString("last4") ?: "",
                    onBack = { navController.popBackStack() },
                )
            }
```
(`NavType` and `navArgument` are already imported in this file — confirm.)

- [ ] **Step 3: Compile**

Run: `cd android && export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:compileDebugKotlin --console=plain 2>&1 | tail -6`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
cd /Users/harshkhandelwal/Documents/expense-tracker
git add android/app/src/main/java/com/ledgernoir/tracker/ui/rewards/RewardsScreen.kt android/app/src/main/java/com/ledgernoir/tracker/ui/shell/AppShell.kt
git commit -m "$(cat <<'EOF'
feat(android): open per-card reward detail from Rewards screen

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 6: Build, install, verify on device

**Use the `run-app` skill.** Device `SM-S901E` at `192.168.1.2` (re-discover port via
`adb mdns services`). Backend should be running on `:8000` with seeded card rewards (the
two `CardReward` rows seeded during the prior feature verification persist in `emu.db`).

- [ ] **Step 1: Backend suite green**

Run: `cd backend && .venv/bin/pytest -q`
Expected: all pass.

- [ ] **Step 2: Build + install + launch**

```bash
cd /Users/harshkhandelwal/Documents/expense-tracker/android
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
ADB=~/Library/Android/sdk/platform-tools/adb
DEV=$($ADB devices | awk '/device$/{print $1; exit}')
./gradlew :app:assembleDebug --console=plain 2>&1 | tail -3      # BUILD SUCCESSFUL
$ADB -s "$DEV" install -r app/build/outputs/apk/debug/app-debug.apk
$ADB -s "$DEV" reverse tcp:8000 tcp:8000
$ADB -s "$DEV" shell am start -n com.ledgernoir.tracker/.MainActivity
```

- [ ] **Step 3: Verify the detail screen**

Insights → Rewards tile → tap the **IDFC FIRST** card. Screenshot
(`$ADB -s "$DEV" exec-out screencap -p > /tmp/d.png`) and confirm:
  - App bar "FIRST Rewards ••0684"; hero shows points + ≈₹ + expiry banner.
  - "CARD BENEFITS & OFFERS" lists the curated rows (lounge, dining, movies, fuel, …).
  - "Explore live offers →" opens the IDFC portal in a browser (then return to app).
  - "REDEEM POINTS" chips + "HISTORY" shows the MAKEMYTRIP redemption.
  - Back returns to the Rewards list. Repeat for the Axis card.

- [ ] **Step 4: Confirm backend suite still green**

Run: `cd backend && .venv/bin/pytest -q`
Expected: all pass.

---

## Self-Review notes (author)
- **Spec coverage:** catalog offer_categories + helper → Task 1; router fields → Task 1;
  DTOs → Task 2; detail VM/UiState → Task 3; RewardDetailScreen (hero/benefits/portal/
  redeem/history) → Task 4; tappable cards + route → Task 5; on-device verify → Task 6.
- **Type consistency:** `offerCategories`/`portalUrl` (camelCase via `@Json`) match the
  router snake_case keys. `onOpenCard(bank, last4)` signature consistent Tasks 4/5.
  `RewardDetailViewModel(getRewards, bank, last4)` consistent Tasks 3/4. `cardAccent` and
  amber ramp reused from RewardsScreen verbatim (intentional small duplication; both are
  private to the rewards package).
- **YAGNI:** no new endpoint (filters the existing /rewards payload); no earn-rule parsing.
