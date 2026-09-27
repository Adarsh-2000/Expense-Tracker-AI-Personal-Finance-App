# Flip 7 Redesign — Phase 2: Money Core (Bills + Transactions) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Convert the Bills and Transactions screens to foldable-first Main/Cover layouts on the Galaxy Z Flip 7, using the Phase 1 foundation — CRED-Pay-style Bills with credit-card-art + utilization + due hero, and a Fi/Jupiter-style Transactions feed with merchant logos and a segmented filter.

**Architecture:** Same dispatcher pattern proven on the Dashboard in Phase 1: each screen becomes `XxxScreen` (dispatcher on `LocalFormFactor`) → `XxxMain` (existing content, renamed) + `XxxCover` (new compact tree), sharing one hoisted ViewModel instance (same nav destination → same VM key → state survives fold). Reuses Phase 1 components: `HeroNumber`, `CreditCardArt`, `CardCarousel`, `MerchantLogo`, `Space`, `Radii`, and kept `CredCard`/`MoneyText`.

**Tech Stack:** Kotlin, Jetpack Compose (BOM 2024.04.01), Material3. No new dependencies.

**Conventions (every task):**
- Build env: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"; export PATH="$JAVA_HOME/bin:$PATH:$HOME/Library/Android/sdk/platform-tools"`; gradle from `android/`.
- Compile gate: `./gradlew :app:assembleDebug -q && echo BUILD_OK`.
- **No early-return inside a composable** (allowed only at top-of-function before any layout node).
- Keep the public `XxxScreen(...)` signature byte-identical (NavHost call sites must not change).
- Keep `CredCard`/`MoneyText` styling; don't restyle the kept card system.
- Phase 1 components live in `com.ledgernoir.tracker.ui.designsystem` (wildcard-importable).

---

## File Structure (Phase 2)
**Modify:**
- `app/src/main/java/com/ledgernoir/tracker/ui/bills/BillsScreen.kt` — split into dispatcher + `BillsMain` + `BillsCover`; add a `CreditCardArt`-based card row.
- `app/src/main/java/com/ledgernoir/tracker/ui/transactions/TransactionsScreen.kt` — split into dispatcher + `TransactionsMain` + `TransactionsCover`; add `MerchantLogo` to rows; add a segmented filter.
**Create:**
- `app/src/main/java/com/ledgernoir/tracker/ui/designsystem/SegmentedFilter.kt` — a reusable 3-segment control (All · Spends · Income).

**Reference (read, don't modify):** `BillsUiState.kt` (`utilizationByAccount: Map<Int, CardUtilizationDto>`, `cards`, `bills`, `totalUnbilled`, `totalDue`), `BillsViewModel` (ctor `getBills, getCards, markBillPaid, getCardUtilization, setCardLimit`; methods `markBillPaid`, `setLimit`), `CardUtilizationDto` (`accountId, bank, last4, totalLimit, available, used, utilizationPct, source`), `CardStateDto` (`accountId, bank, last4, unbilled, currentBill{totalDue,dueDate,daysUntilDue}, ...`), `TransactionsUiState` (`items: List<TxnListItem>`, `filterDirection: String?`, `query`, `rangeSpent`), `TransactionsViewModel`.

---

## Task 1: SegmentedFilter component

**Files:**
- Create: `app/src/main/java/com/ledgernoir/tracker/ui/designsystem/SegmentedFilter.kt`

- [ ] **Step 1: Create the component + preview**

```kotlin
package com.ledgernoir.tracker.ui.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.ledgernoir.tracker.ui.theme.AccentTeal
import com.ledgernoir.tracker.ui.theme.SurfaceMid
import com.ledgernoir.tracker.ui.theme.TextSecondary

/**
 * A pill segmented control. [options] are the segment labels; [selectedIndex] is
 * the active one; [onSelect] fires with the tapped index. Selected segment fills
 * teal. Used for the Transactions All/Spends/Income filter.
 */
@Composable
fun SegmentedFilter(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(SurfaceMid)
            .padding(3.dp),
    ) {
        options.forEachIndexed { i, label ->
            val selected = i == selectedIndex
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (selected) AccentTeal else androidx.compose.ui.graphics.Color.Transparent)
                    .clickable { onSelect(i) }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (selected) androidx.compose.ui.graphics.Color(0xFF04201C) else TextSecondary,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                )
            }
        }
    }
}

@Preview(backgroundColor = 0xFF0B0B0D, showBackground = true)
@Composable
private fun SegmentedFilterPreview() {
    SegmentedFilter(options = listOf("All", "Spends", "Income"), selectedIndex = 0, onSelect = {})
}
```

- [ ] **Step 2: Compile gate** — `./gradlew :app:assembleDebug -q && echo BUILD_OK` → `BUILD_OK`.
- [ ] **Step 3: Commit**
```bash
git add app/src/main/java/com/ledgernoir/tracker/ui/designsystem/SegmentedFilter.kt
git commit -m "feat(android): SegmentedFilter pill control"
```

---

## Task 2: CreditCardArt-based card row for Bills

**Files:**
- Modify: `app/src/main/java/com/ledgernoir/tracker/ui/bills/BillsScreen.kt`

**Context:** Read `BillsScreen.kt`. It has a private `CardStateCard(c: CardStateDto, util: CardUtilizationDto?, onSetLimit, onClick)` rendering a card with an icon chip, unbilled/billed columns, a utilization bar, and "Adjust limit". We add a NEW private composable `BillCardArt` that renders the SAME card using the Phase 1 `CreditCardArt` face (gradient, last4, utilization + due overlay), keeping the "Adjust limit ›" / "Set limit ›" affordance below it. This will be used by `BillsMain`/`BillsCover` in Task 3. Do NOT delete `CardStateCard` yet (Task 3 swaps call sites).

- [ ] **Step 1: Add `BillCardArt`**

Add this private composable to `BillsScreen.kt` (uses existing imports + the designsystem wildcard; add imports as needed):
```kotlin
@Composable
private fun BillCardArt(
    c: com.ledgernoir.tracker.data.api.CardStateDto,
    util: com.ledgernoir.tracker.data.api.CardUtilizationDto?,
    onSetLimit: (Double) -> Unit,
    onClick: () -> Unit,
) {
    val due = c.currentBill?.totalDue
    val trailing = when {
        due != null && due > 0 -> "₹${"%,.0f".format(due)} due"
        else -> "no dues"
    }
    val pct = util?.takeIf { it.source != "unknown" && it.totalLimit > 0 }?.utilizationPct?.toFloat()
    Column(modifier = Modifier.clickable { onClick() }) {
        com.ledgernoir.tracker.ui.designsystem.CreditCardArt(
            bank = c.bank,
            last4 = c.last4,
            network = null,
            utilizationPct = pct,
            trailing = if (pct != null) trailing else null,
            modifier = Modifier.fillMaxWidth(),
            height = 150.dp,
        )
        Spacer(Modifier.height(8.dp))
        var showDialog by remember { mutableStateOf(false) }
        val label = if (pct != null) "Adjust limit ›" else "Set limit to track utilization ›"
        Text(
            label,
            color = AccentTeal,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clickable { showDialog = true },
        )
        if (showDialog) {
            SetLimitDialog(
                onDismiss = { showDialog = false },
                onConfirm = { v -> showDialog = false; onSetLimit(v) },
            )
        }
    }
}
```
> `SetLimitDialog` already exists in `BillsScreen.kt` (reuse it). Confirm `CardStateDto.currentBill?.totalDue` is the correct path by reading the DTO; adjust the field access to the real names if different (do not guess — read it).

- [ ] **Step 2: Compile gate** — `BUILD_OK`.
- [ ] **Step 3: Commit**
```bash
git add app/src/main/java/com/ledgernoir/tracker/ui/bills/BillsScreen.kt
git commit -m "feat(android): BillCardArt — credit-card-art bill row"
```

---

## Task 3: Split Bills into Main + Cover

**Files:**
- Modify: `app/src/main/java/com/ledgernoir/tracker/ui/bills/BillsScreen.kt`

**Context:** `BillsScreen(repository, onOpenCard, onOpenRewards)` creates `BillsViewModel` via `viewModel(factory = ledgerViewModelFactory { ... })`. State: `cards` (with `.cards`, `.totalUnbilled`, `.totalDue`), `bills`, `utilizationByAccount`. Follow the Phase 1 Dashboard split pattern exactly.

- [ ] **Step 1: Rename** the existing `fun BillsScreen(` → `fun BillsMain(`, same param list, body unchanged.

- [ ] **Step 2: Add dispatcher** (identical public signature, forward args):
```kotlin
@Composable
fun BillsScreen(
    repository: ExpenseRepository,
    onOpenCard: (accountId: Int, title: String) -> Unit = { _, _ -> },
    onOpenRewards: () -> Unit = {},
) {
    when (com.ledgernoir.tracker.ui.designsystem.LocalFormFactor.current) {
        com.ledgernoir.tracker.ui.designsystem.FormFactor.Cover ->
            BillsCover(repository, onOpenCard, onOpenRewards)
        else -> BillsMain(repository, onOpenCard, onOpenRewards)
    }
}
```
> Confirm the exact `BillsScreen` signature by reading the file and copy it verbatim (the params shown match the current screen; verify defaults).

- [ ] **Step 3: Add `BillsCover`** (same params, `private`). Copy the exact `viewModel(factory = ledgerViewModelFactory { BillsViewModel(...) })` block from `BillsMain` (same VM instance). Render compact: a "Total due now" `HeroNumber`, then a `CardCarousel` of `BillCardArt` for each card, then a "View rewards" row:
```kotlin
@Composable
private fun BillsCover(
    repository: ExpenseRepository,
    onOpenCard: (accountId: Int, title: String) -> Unit,
    onOpenRewards: () -> Unit,
) {
    val vm: BillsViewModel = viewModel(
        factory = ledgerViewModelFactory { BillsViewModel(/* COPY EXACT ARGS FROM BillsMain */) },
    )
    val state by vm.uiState.collectAsStateWithLifecycle()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CanvasBlack)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        com.ledgernoir.tracker.ui.designsystem.HeroNumber(
            label = "Total due now",
            amount = state.cards?.totalDue ?: 0.0,
            change = null,
        )
        Spacer(Modifier.height(12.dp))
        val cardList = state.cards?.cards ?: emptyList()
        com.ledgernoir.tracker.ui.designsystem.CardCarousel(count = cardList.size) { i ->
            val c = cardList[i]
            BillCardArt(
                c = c,
                util = state.utilizationByAccount[c.accountId],
                onSetLimit = { limit -> vm.setLimit(c.accountId, limit) },
                onClick = { onOpenCard(c.accountId, "${c.bank} ••${c.last4 ?: ""}") },
            )
        }
        Spacer(Modifier.height(12.dp))
        Box(
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                .clickable { onOpenRewards() }.background(SurfaceMid)
                .padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            Text("View card rewards →", color = AccentGreen,
                style = MaterialTheme.typography.labelLarge)
        }
    }
}
```
> Use the real field names from `BillsUiState`/`CardsResponse` (the existing `BillsMain` already references `cards?.cards`, `cards!!.totalDue`, `cards!!.totalUnbilled`, `state.utilizationByAccount[...]`, `vm.setLimit(...)` — mirror those exact accessors). Read `BillsMain` to copy them precisely. No early-return inside the Column.

- [ ] **Step 4 (optional): use BillCardArt in BillsMain** — In `BillsMain`'s "Your cards" loop, you MAY replace the `CardStateCard(...)` call with `BillCardArt(...)` for the CRED card-art look on the main screen too. Only do this if it compiles cleanly and preserves the existing data; otherwise leave `CardStateCard` in place. (Keeping both is acceptable for Phase 2.)

- [ ] **Step 5: Compile gate** — `BUILD_OK`.
- [ ] **Step 6: Commit**
```bash
git add app/src/main/java/com/ledgernoir/tracker/ui/bills/BillsScreen.kt
git commit -m "feat(android): Bills Main/Cover split (CRED-Pay style)"
```

---

## Task 4: Merchant logos in the Transactions rows

**Files:**
- Modify: `app/src/main/java/com/ledgernoir/tracker/ui/transactions/TransactionsScreen.kt`

**Context:** Read `TransactionsScreen.kt`. It renders transaction rows (a private row composable, around the `LazyColumn`/list section). Each row shows a merchant + amount. Add a leading `MerchantLogo(merchant)` (Phase 1 component) to the row, to the left of the text, so the feed gets recognizable colored initials.

- [ ] **Step 1: Find the row composable** that renders a single `TransactionDto` (it shows the merchant name and amount). Add a leading `MerchantLogo`:
```kotlin
// inside the row's Row(...) { ... }, as the first child:
com.ledgernoir.tracker.ui.designsystem.MerchantLogo(merchant = txn.merchant)
Spacer(Modifier.width(12.dp))
```
Adjust to the actual row structure: the existing leading element (if any icon/avatar) is replaced by `MerchantLogo`; keep the merchant name + category + amount layout intact. Read the row composable and integrate cleanly (the merchant field is `TransactionDto.merchant`).

- [ ] **Step 2: Compile gate** — `BUILD_OK`.
- [ ] **Step 3: Commit**
```bash
git add app/src/main/java/com/ledgernoir/tracker/ui/transactions/TransactionsScreen.kt
git commit -m "feat(android): merchant logos in the transaction feed"
```

---

## Task 5: Segmented filter + Split Transactions into Main + Cover

**Files:**
- Modify: `app/src/main/java/com/ledgernoir/tracker/ui/transactions/TransactionsScreen.kt`

**Context:** `TransactionsScreen(repository)` creates `TransactionsViewModel` via `viewModel(...)`. State has `filterDirection: String?` (null=All, "debit"=Spends, "credit"=Income) and a method to set it (read the VM — find the setter, e.g. `vm.setFilterDirection(...)` or similar; use the real method name). The screen currently renders three filter chips; replace them with the `SegmentedFilter` from Task 1. Then split into Main/Cover per the Dashboard pattern.

- [ ] **Step 1: Replace the filter chips with SegmentedFilter.** Find the three All/Spending/Income chips. Replace with:
```kotlin
val segIndex = when (state.filterDirection) { "debit" -> 1; "credit" -> 2; else -> 0 }
com.ledgernoir.tracker.ui.designsystem.SegmentedFilter(
    options = listOf("All", "Spends", "Income"),
    selectedIndex = segIndex,
    onSelect = { i -> vm.setFilterDirection(when (i) { 1 -> "debit"; 2 -> "credit"; else -> null }) },
)
```
> Use the REAL view-model setter for the filter direction (read `TransactionsViewModel` to find its exact name and signature; it currently powers the existing chips' onClick). Keep the existing list/search/date-range behavior.

- [ ] **Step 2: Rename** `fun TransactionsScreen(` → `fun TransactionsMain(`, same param list (`repository: ExpenseRepository`), body unchanged (now using SegmentedFilter).

- [ ] **Step 3: Add dispatcher:**
```kotlin
@Composable
fun TransactionsScreen(repository: ExpenseRepository) {
    when (com.ledgernoir.tracker.ui.designsystem.LocalFormFactor.current) {
        com.ledgernoir.tracker.ui.designsystem.FormFactor.Cover -> TransactionsCover(repository)
        else -> TransactionsMain(repository)
    }
}
```

- [ ] **Step 4: Add `TransactionsCover(repository)`** (`private`). Copy the exact `viewModel(...)` block from `TransactionsMain` (same VM instance). Render compact: the `SegmentedFilter`, then a `LazyColumn` of the same transaction rows (reuse the row composable that now includes `MerchantLogo`), trimmed of the date-range pills/search-expanded UI (keep a search icon if simple). Minimal compact version:
```kotlin
@Composable
private fun TransactionsCover(repository: ExpenseRepository) {
    val vm: TransactionsViewModel = viewModel(
        factory = ledgerViewModelFactory { TransactionsViewModel(/* COPY EXACT ARGS FROM TransactionsMain */) },
    )
    val state by vm.uiState.collectAsStateWithLifecycle()
    val segIndex = when (state.filterDirection) { "debit" -> 1; "credit" -> 2; else -> 0 }
    Column(
        modifier = Modifier.fillMaxSize().background(CanvasBlack)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text("Transactions", style = MaterialTheme.typography.titleMedium,
            color = TextPrimary, fontFamily = DisplayFamily)
        Spacer(Modifier.height(10.dp))
        com.ledgernoir.tracker.ui.designsystem.SegmentedFilter(
            options = listOf("All", "Spends", "Income"),
            selectedIndex = segIndex,
            onSelect = { i -> vm.setFilterDirection(when (i) { 1 -> "debit"; 2 -> "credit"; else -> null }) },
        )
        Spacer(Modifier.height(10.dp))
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(state.items) { item -> /* reuse the existing per-item row composable */ }
        }
    }
}
```
> Replace the `viewModel(...)` args, the filter setter name, the `state.items` type (`List<TxnListItem>`), and the row rendering with the REAL ones from `TransactionsMain` (read the file — `TxnListItem` is a sealed/grouped type with date headers + rows; reuse the existing `when`/row composable so cover matches main). Import `androidx.compose.foundation.lazy.items`, `DisplayFamily`, `TextPrimary` as needed. No early-return inside the Column.

- [ ] **Step 5: Compile gate** — `BUILD_OK`.
- [ ] **Step 6: Commit**
```bash
git add app/src/main/java/com/ledgernoir/tracker/ui/transactions/TransactionsScreen.kt
git commit -m "feat(android): Transactions Main/Cover split + segmented filter"
```

---

## Phase 2 Acceptance
- `:app:assembleDebug` clean; existing unit tests still pass.
- Bills: `BillsScreen` dispatches by form factor; Main shows the bills/cards (card-art optional), Cover shows due hero + card-art carousel + rewards link; public signature unchanged.
- Transactions: segmented filter replaces chips; rows show merchant logos; `TransactionsScreen` dispatches by form factor; public signature unchanged.
- No forbidden early-returns; on-device (when available): both screens render on main + cover, fold keeps state.

## On-device verification (owed, needs Flip 7)
Install and verify Bills + Transactions on the main 6.9″ and cover 4.1″ screens, and that folding preserves state/scroll. (Phase 1's Dashboard verification is also owed.)
