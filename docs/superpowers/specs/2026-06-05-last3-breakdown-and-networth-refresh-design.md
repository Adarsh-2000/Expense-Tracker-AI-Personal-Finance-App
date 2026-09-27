# Last 3 Months breakdown screen + Net Worth freshness — Design

Date: 2026-06-05
Status: Approved (pending spec review)

## Summary

Two related improvements to the Ledger Noir Android app (+ one small backend change):

1. **Last 3 Months breakdown screen** — Tapping the dashboard "Last 3 Months" card
   opens a single screen that shows *both* a category chart (the "Where it went"
   donut) *and* the full transaction list, with tap-to-filter category chips
   (chosen layout: **Option D**).
2. **Net worth stays current** — The Net Worth card (dashboard) and Net Worth
   screen reflect new/edited investments, savings, and FDs by (a) refreshing the
   UI at the right times and (b) folding investment-portfolio sync into the main
   Gmail sync pipeline.

---

## Feature 1 — "Last 3 Months" breakdown screen (Option D)

### Current behaviour
The dashboard `Last3MonthsCard` (`DashboardScreen.kt`) calls
`onOpenRange(last3StartIso, last3EndIso, "Last 3 Months")`, which navigates to the
`txnrange/{start}/{end}/{title}` route → the generic `TransactionListScreen` (a
date-bounded, debit-only, date/amount-sortable list). There is no chart on it.

### Target behaviour
A new screen that combines chart + list in one view:

- **App bar:** `‹ Last 3 Months`
- **Donut** — category-colored, same visual language as the dashboard "Where it
  went" donut. Center shows `TOTAL ₹X`. When a category is selected, that slice is
  highlighted in place (other slices dimmed) and the center switches to the
  selected category name + its total. **Donut is redrawn highlight-in-place, not
  reduced to a single slice** (keeps context).
- **Filter chips row** — horizontally scrollable. `All` + one chip per category
  (colored dot + name), sorted by spend descending. Tapping a donut slice is
  equivalent to tapping its chip.
- **Summary line + sort toggle** — left: `N transactions` (or `Food · N
  transactions` when filtered); right: the existing **Date / Amount** sort toggle.
- **Transaction list** — the existing date-grouped (Date sort) / amount-sorted
  list rendering, filtered to the selected category.

### Data flow (single source of truth)
One `searchTransactions(start = last3Start, end = last3End, direction = "debit")`
call backs the whole screen:

- **Category totals** for the donut + chips are derived client-side by grouping the
  fetched transactions by `categoryId` and mapping ids → names (via
  `getCategories`). Uncategorized transactions group under a single "Uncategorized"
  bucket.
- **The filtered list** is the same in-memory list filtered by the selected
  `categoryId` (or unfiltered when `All`).

Because the donut, chips, and list all come from the same list, the donut total
always equals the sum of the visible rows. **No backend change for this feature.**

### Components & files
- **New:** `ui/transactions/Last3BreakdownScreen.kt` + `Last3BreakdownViewModel.kt`
  + `Last3BreakdownUiState.kt`. The ViewModel reuses
  `GetCategoriesUseCase` + `SearchTransactionsUseCase` (same as
  `TransactionListViewModel`) and exposes: transactions, category-name map, derived
  category totals (sorted), selected category id (nullable = All), and loading.
- **Extract shared donut:** the donut is currently `private fun CategoryDonut` in
  `DashboardScreen.kt`. Extract a reusable composable (e.g.
  `ui/designsystem/CategoryDonut.kt`) supporting an optional `selectedCategory`
  highlight param. `DashboardScreen` switches to the shared version (its existing
  "Where it went" donut keeps current appearance when no selection is passed).
- **Routing:** in `AppShell.kt`, point the `ROUTE_TXN_RANGE` composable
  (`txnrange/...`) at `Last3BreakdownScreen` instead of `TransactionListScreen`.
  The other drill-down routes (`txncat`, `txncard`, `txnmerchant`) keep using
  `TransactionListScreen` unchanged. Screen receives `title`, `startIso`, `endIso`,
  `onBack`.
- Reuse `styleFor(category).color`, `CategoryIconChip`, `MoneyText`,
  date-grouping/sort helpers. Where helpers are currently `private` in
  `TransactionListScreen.kt` and needed by the new screen, extract them to a shared
  location rather than duplicating.

### Edge cases
- Empty range → empty state card (reuse existing "No transactions" pattern); donut
  shows the empty ring.
- Selecting a category with then-zero rows is not reachable (chips only exist for
  categories that have spend).
- Foreign-currency rows: list rendering already handles this (reuse `DetailTxnRow`).

---

## Feature 2 — Net worth stays current

### 2a — UI refresh (card + screen)
- **Net Worth screen** (`NetWorthScreen` / `NetWorthViewModel`):
  - Add `refresh()` to the ViewModel (re-runs the existing full + projection fetch).
  - Re-fetch on `Lifecycle.Event.ON_RESUME` so returning to the screen after an
    edit/sync shows fresh numbers.
  - Add pull-to-refresh (Material3 `PullToRefreshBox`) wrapping the scroll content,
    calling `refresh()`.
- **Dashboard net worth card** (`DashboardViewModel` / `DashboardScreen`):
  - Re-fetch net worth on dashboard `ON_RESUME` (today it loads once at init and
    again only on `SyncPhase.Done`). Add a lightweight `refreshNetWorth()` that
    calls `getNetWorth()` and updates state, invoked on resume — so returning from
    Savings/Portfolio after adding an FD / editing a balance updates the card
    without a full reload.

### 2b — Ingestion (close the gap)
**Gap:** `POST /sync/gmail` (`app/routers/sync.py`) ingests email transactions,
runs the Money Radar scan, and dispatches pushes — but it does **not** sync the
investment portfolio. Indian holdings (PDF) and US stocks are only refreshed by an
explicit `POST /sync/portfolio` (`app/routers/portfolio.py`), so a normal email
sync leaves investments — and therefore net worth — stale.

**Change:**
- Factor the portfolio-sync core out of the `sync_portfolio` route handler into a
  reusable service function (e.g. `sync_investments(session)` in
  `app/services/networth.py` or a new `app/services/portfolio_sync.py`). It runs
  the US aggregate sync (`sync_us_portfolio`, already a service fn) and, when a PAN
  is set, the Indian holdings statement parse/replace. Returns a small status dict.
- The existing `POST /sync/portfolio` route calls this shared function (behaviour
  unchanged).
- `POST /sync/gmail` calls `sync_investments(session)` **best-effort / silently**,
  wrapped in `try/except` with `rollback` on failure (same pattern as the Money
  Radar scan and push dispatch already in that endpoint). A portfolio failure must
  never break transaction ingestion, and **no new error is surfaced** in the sync
  result. Net-worth recomputation is already live on read, so no further wiring is
  needed.

**Not in scope:** FDs remain manual-entry only (no email parser); they are covered
by 2a's refresh. Savings balances already update live via the SMS balance scan
(`resolved_balance` reads freshest of SMS-scanned vs manual on every read).

### Components & files
- Backend: `app/services/networth.py` (or new `portfolio_sync.py`),
  `app/routers/portfolio.py`, `app/routers/sync.py`.
- Android: `ui/networth/NetWorthViewModel.kt`, `ui/networth/NetWorthScreen.kt`,
  `ui/dashboard/DashboardViewModel.kt`, `ui/dashboard/DashboardScreen.kt`.

---

## Testing
- **Backend:** unit test for `sync_investments` (PAN present vs absent; US-only;
  failure path is swallowed). Test that `/sync/gmail` still returns its normal
  result shape when portfolio sync raises (mock it to throw). Keep the existing
  suite green.
- **Android:** manual verification on the wireless device (run-app skill):
  1. Tap "Last 3 Months" → donut + chips + list; tap a chip → donut highlight,
     list filters, center/summary update, totals consistent; Date/Amount sort still
     works; back returns to dashboard.
  2. Add an FD on Savings → back to dashboard → net worth card updated; open Net
     Worth screen → breakdown reflects it; pull-to-refresh works.
  3. Run an email sync → investments refresh and net worth reflects them.

## Architecture notes
- Follows existing repository/use-case/entity layering (global standard:
  repository + entity patterns; async/await for IO). All new data access goes
  through existing use cases / repositories.
- New Compose screens kept small and focused; shared visuals (donut, list helpers)
  extracted rather than duplicated.
