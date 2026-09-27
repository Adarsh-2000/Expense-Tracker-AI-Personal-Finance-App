# Flip 7 Redesign — Phases 3–5: Remaining Screens — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development. Checkbox steps.

**Goal:** Apply the proven foldable Main/Cover split to every remaining screen so the whole app works on the Galaxy Z Flip 7 main (6.9″) and cover (4.1″) screens.

**Architecture:** Identical dispatcher pattern proven on Dashboard/Bills/Transactions in Phases 1–2. Reuse Phase 1 components (`HeroNumber`, `CreditCardArt`, `CardCarousel`, `AllocationRing`, `MerchantLogo`, `SegmentedFilter`, `Space`) + kept `CredCard`/`MoneyText`.

**Tech Stack:** Kotlin/Compose (BOM 2024.04.01), Material3. No new deps.

---

## THE SPLIT RECIPE (apply to every screen below)
For a screen `fun XxxScreen(<params>)` whose ViewModel is created inside it via `viewModel(factory = ledgerViewModelFactory { XxxViewModel(...) })`:
1. **Rename** `fun XxxScreen(` → `fun XxxMain(` — same param list, body unchanged.
2. **Add dispatcher** with the BYTE-IDENTICAL public signature:
   ```kotlin
   @Composable
   fun XxxScreen(<params>) {
       when (LocalFormFactor.current) {              // com.ledgernoir.tracker.ui.designsystem.*
           FormFactor.Cover -> XxxCover(<args forwarded by name>)
           else -> XxxMain(<args forwarded by name>)
       }
   }
   ```
3. **Add `private fun XxxCover(<params>)`** — copy the EXACT `viewModel(factory = ...)` block from `XxxMain` (same nav destination → same VM key → same instance → state survives fold), then render the compact layout from the per-screen spec.

**Rules (all tasks):**
- Build env: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"; export PATH="$JAVA_HOME/bin:$PATH"`; gradle from `android/`. Gate: `./gradlew :app:assembleDebug -q && echo BUILD_OK`.
- Public `XxxScreen` signature must stay byte-identical (NavHost call sites unchanged).
- **No early-return inside a composable** (only allowed at top-of-function before any layout node).
- Read the real file first: confirm the VM ctor args, `XxxUiState` field names, and existing sub-composables; reuse them — do not duplicate/diverge logic. If a screen is already a simple single-column scroll that renders fine on a 361dp-wide cover, the Cover may reuse Main's body composables (extract a shared inner composable rather than copy-paste) — note this in the report.
- Reuse `HeroNumber` for the hero number; keep `CredCard`/`MoneyText` styling.
- Commit each task separately with the message shown.

---

## PHASE 3 — Insights cluster

### Task 1: Insights hub (`ui/insights/InsightsScreen.kt`, `fun InsightsScreen(...)`)
Hub of tiles (How I Pay, Save More, Budgets, Rewards, Money Radar) + a total-spent/allocation area.
- **Cover spec:** title "Insights"; if a spend total is in state, a `HeroNumber("Spent", total)`; then the tiles as a **single-column** list of full-width rows (icon + title + chevron), preserving any live badges (e.g., rewards expiring, radar alerts) the Main shows. Reuse the existing tile composable if present.
- Commit: `feat(android): Insights hub Main/Cover split`

### Task 2: Rewards (`ui/rewards/RewardsScreen.kt`, `fun RewardsScreen(...)`)
Has TOTAL REDEEMABLE, per-card points/value, REDEEM AT, RECENT, expiry strip.
- **Cover spec:** `HeroNumber("Total redeemable", <value>)`; the expiry alert strip (if `expiringPoints>0`); then a `CardCarousel` of `CreditCardArt(bank, last4, ...)` per reward card showing points + value (use the existing per-card data; pass utilizationPct=null, put "<points> pts · ₹<value>" via the card's content — if `CreditCardArt` can't show that, render each card as `CreditCardArt` + a caption row below). Tapping a card → existing detail nav if present.
- Commit: `feat(android): Rewards Main/Cover split (card-art)`

### Task 3: Money Radar (`ui/insights/MoneyRadarScreen.kt`, `fun MoneyRadarScreen(...)`)
A findings list.
- **Cover spec:** title + the same findings list, compact. This screen is already a single-column list — extract its list body into a shared inner composable and have both Main and Cover use it (Cover drops any wide header chrome). If Main already renders fine at 361dp, Cover may simply reuse the shared body.
- Commit: `feat(android): Money Radar Main/Cover split`

### Task 4: Save More (`ui/insights/SaveMoreScreen.kt`, `fun SaveMoreScreen(repository)`)
Subscriptions / leaks / goals list.
- **Cover spec:** single-column list reusing Main's section composables; trim any side-by-side rows to stacked. Extract shared body; Cover uses it.
- Commit: `feat(android): Save More Main/Cover split`

### Task 5: How I Pay (`ui/insights/SpendingInsightsScreen.kt`, `fun SpendingInsightsScreen(...)`)
Cards/UPI/when breakdown.
- **Cover spec:** lead with the key hero (total or top method) via `HeroNumber` if a total exists; then the breakdown rows single-column. Reuse Main's breakdown composables.
- Commit: `feat(android): How I Pay Main/Cover split`

### Task 6: Budget (`ui/budget/BudgetScreen.kt`, `fun BudgetScreen(repository)`)
Budget caps / overspend.
- **Cover spec:** `HeroNumber` for the month's budget-vs-spent headline (use the real fields); then per-category budget rows (compact, single-column) reusing Main's row composable.
- Commit: `feat(android): Budget Main/Cover split`

---

## PHASE 4 — Wealth

### Task 7: Portfolio (`ui/portfolio/PortfolioScreen.kt`, `fun PortfolioScreen(repository)`)
PORTFOLIO VALUE, US STOCKS, ALLOCATION (Stocks/Funds/Others), Sync.
- **Cover spec:** `HeroNumber("Portfolio value", <value>, change=<day change label>)`; an `AllocationRing` built from the allocation slices (Stocks/Funds/Others fractions → `RingSlice(fraction, color)`) with a small legend; a compact holdings summary (US Stocks aggregate row). Reuse the real allocation data from state.
- Commit: `feat(android): Portfolio Main/Cover split (allocation ring)`

### Task 8: Savings (`ui/savings/SavingsScreen.kt`, `fun SavingsScreen(repository)`)
TOTAL LIQUID, accounts, FDs.
- **Cover spec:** `HeroNumber("Total liquid", <total>)`; account rows (bank chip + last4 + balance, reuse Main's row); FDs collapsed to a "<n> FDs ›" row or a compact list.
- Commit: `feat(android): Savings Main/Cover split`

### Task 9: Net Worth (`ui/networth/NetWorthScreen.kt`, `fun NetWorthScreen(repository, onBack)`)
- **Cover spec:** back affordance + `HeroNumber("Net worth", <value>)` + the breakdown (assets/liabilities) single-column, reusing Main's breakdown composable. Forward `onBack`.
- Commit: `feat(android): Net Worth Main/Cover split`

---

## PHASE 5 — Utility

### Task 10: Settings (`ui/settings/SettingsScreen.kt`, `fun SettingsScreen(tokenStore, repository)`)
- **Cover spec:** single-column section rows (Account / Server / Data sources / About / Sign out). This screen is largely single-column already — extract the body into a shared inner composable; Cover reuses it with tighter padding and any two-column source grid stacked. Forward both params.
- Commit: `feat(android): Settings Main/Cover split`

### Task 11: Notifications (`ui/notifications/NotificationsScreen.kt`, `fun NotificationsScreen(...)`)
- **Cover spec:** the notifications list, compact; extract shared list body; Cover reuses it. Forward all params.
- Commit: `feat(android): Notifications Main/Cover split`

### Task 12: Sign-in & Onboarding (`ui/auth/SignInScreen.kt`, `ui/auth/OnboardingScreen.kt`)
These are centered single-purpose screens.
- **Cover spec (SignIn):** the existing centered block already adapts; ensure the wordmark/button fit a 390dp-tall cover (use `heroFontSize()`/smaller wordmark on Cover via `LocalFormFactor`, keep the button above the gesture inset). A full dispatcher is optional — if the existing layout already fits the cover (it's centered + scroll-free), make the wordmark size form-factor-aware in place rather than a full split. Do the minimal change that makes it look right on both. Same for Onboarding.
- Commit: `feat(android): Sign-in & Onboarding cover-fit`

### Task 13: Detail screens (`ui/rewards/RewardDetailScreen.kt`, `ui/transactions/Last3BreakdownScreen.kt`, `ui/transactions/TransactionListScreen.kt`)
These are already vertically-scrolling single-column detail screens wrapped in `ResponsiveContainer`.
- **Cover spec:** verify each renders acceptably at 361dp wide (they use vertical scroll + capped width). Where a screen has a side-by-side header or oversized hero, make the hero/type form-factor-aware via `LocalFormFactor` (smaller on Cover). No full split needed unless a screen genuinely breaks; if it does, apply the recipe. Report which screens needed changes.
- Commit: `feat(android): detail screens cover-fit`

---

## Acceptance (whole app)
- `:app:assembleDebug` clean; unit tests still pass.
- Every primary screen dispatches by form factor (or is verified cover-safe); all public signatures unchanged; no forbidden early-returns.
- On-device (when Flip 7 connected): each screen renders on main 6.9″ and cover 4.1″; fold keeps state.

## On-device verification (owed)
Install and walk every screen on the main + cover screens of the Flip 7, plus fold↔unfold, once the device is connectable (VPN off, wireless debugging port).
